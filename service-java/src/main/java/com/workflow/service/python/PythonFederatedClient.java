package com.workflow.service.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.SensitiveHttpLogSanitizer;
import com.workflow.dto.python.PythonFederatedAggregateRequest;
import com.workflow.dto.python.PythonFederatedAggregateResponse;
import com.workflow.exception.BusinessException;
import com.workflow.support.WorkflowErrorSummarySupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Slf4j
public class PythonFederatedClient {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final SensitiveHttpLogSanitizer logSanitizer;

    public PythonFederatedClient(@Qualifier("pythonJsonRestTemplate") RestTemplate restTemplate,
                                 PythonIntegrationProperties properties,
                                 ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.logSanitizer = new SensitiveHttpLogSanitizer(objectMapper);
    }

    public PythonFederatedAggregateResponse aggregate(PythonFederatedAggregateRequest request) {
        validateRequest(request);
        String url = properties.getPythonBaseUrl() + "/internal/federated/aggregate";
        String requestBody = writeRequestBody(request);
        HttpHeaders headers = buildJsonHeaders();

        log.info(
                "Calling Python federated aggregate: workflowId={}, strategy={}, modelCount={}, outputModelPath={}, url={}, contentType={}, accept={}, payloadBytes={}, requestBody={}",
                request.getWorkflowId(),
                request.getStrategy(),
                request.getModelPaths().size(),
                request.getOutputModelPath(),
                url,
                headers.getContentType(),
                headers.getAccept(),
                requestBody.getBytes(StandardCharsets.UTF_8).length,
                logSanitizer.sanitizeText(requestBody)
        );

        try {
            HttpEntity<PythonFederatedAggregateRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<PythonFederatedAggregateResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, PythonFederatedAggregateResponse.class);

            log.info(
                    "Python federated aggregate response received: workflowId={}, statusCode={}, responseBody={}",
                    request.getWorkflowId(),
                    response.getStatusCode(),
                    logSanitizer.sanitizeText(writeLogBody(response.getBody()))
            );

            return validateResponse(request, response);
        } catch (HttpStatusCodeException ex) {
            String responseBody = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
            String summary = resolveRemoteSummary(responseBody);
            log.error(
                    "Python federated aggregate HTTP error: workflowId={}, strategy={}, statusCode={}, contentType={}, responseBody={}",
                    request.getWorkflowId(),
                    request.getStrategy(),
                    ex.getStatusCode(),
                    ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getContentType(),
                    logSanitizer.sanitizeText(responseBody)
            );
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_FAILED", summary, ex);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            String summary = WorkflowErrorSummarySupport.summarizeFederatedAggregation(ex);
            log.error(
                    "Python federated aggregate invocation failed unexpectedly: workflowId={}, strategy={}, exceptionType={}",
                    request.getWorkflowId(),
                    request.getStrategy(),
                    ex.getClass().getSimpleName()
            );
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_FAILED", summary, ex);
        }
    }

    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private PythonFederatedAggregateResponse validateResponse(PythonFederatedAggregateRequest request,
                                                              ResponseEntity<PythonFederatedAggregateResponse> response) {
        if (response == null || response.getBody() == null) {
            throw new BusinessException(
                    "PYTHON_FEDERATED_EMPTY_RESPONSE",
                    "联邦学习聚合失败：Python 聚合服务未返回有效响应"
            );
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new BusinessException(
                    "PYTHON_FEDERATED_HTTP_STATUS_INVALID",
                    "联邦学习聚合失败：Python 聚合服务返回异常状态 " + response.getStatusCode()
            );
        }

        PythonFederatedAggregateResponse body = response.getBody();
        if (!"OK".equals(body.getCode())) {
            String summary = resolveResponseSummary(body);
            log.warn(
                    "Python federated aggregate returned business error: workflowId={}, strategy={}, summary={}, detail={}",
                    request.getWorkflowId(),
                    request.getStrategy(),
                    summary,
                    logSanitizer.sanitizeText(resolveResponseDetail(body))
            );
            throw new BusinessException("PYTHON_FEDERATED_FAILED", summary);
        }

        if (body.getData() == null || !StringUtils.hasText(body.getData().getGlobalModelPath())) {
            throw new BusinessException(
                    "PYTHON_FEDERATED_INVALID_RESPONSE",
                    "联邦学习聚合失败：Python 聚合服务未返回全局模型路径"
            );
        }
        return body;
    }

    private String resolveResponseSummary(PythonFederatedAggregateResponse body) {
        String raw = null;
        if (body != null && body.getData() != null && StringUtils.hasText(body.getData().getErrorMessage())) {
            raw = body.getData().getErrorMessage();
        } else if (body != null) {
            raw = body.getMessage();
        }
        return WorkflowErrorSummarySupport.summarizeFederatedAggregation(raw);
    }

    private String resolveResponseDetail(PythonFederatedAggregateResponse body) {
        if (body != null && body.getData() != null && StringUtils.hasText(body.getData().getDetailMessage())) {
            return body.getData().getDetailMessage();
        }
        return body != null ? body.getMessage() : null;
    }

    private String resolveRemoteSummary(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return WorkflowErrorSummarySupport.summarizeFederatedAggregation((String) null);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("summary")) {
                return WorkflowErrorSummarySupport.summarizeFederatedAggregation(root.get("summary").asText());
            }
            if (root.hasNonNull("detail")) {
                return WorkflowErrorSummarySupport.summarizeFederatedAggregation(root.get("detail").toString());
            }
            PythonFederatedAggregateResponse parsed =
                    objectMapper.treeToValue(root, PythonFederatedAggregateResponse.class);
            return resolveResponseSummary(parsed);
        } catch (Exception ex) {
            log.warn(
                    "Failed to parse Python federated aggregate error body as JSON, responseBytes={}",
                    responseBody.getBytes(StandardCharsets.UTF_8).length
            );
            return WorkflowErrorSummarySupport.summarizeFederatedAggregation(responseBody);
        }
    }

    private String writeRequestBody(PythonFederatedAggregateRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new BusinessException(
                    "PYTHON_FEDERATED_REQUEST_SERIALIZE_FAILED",
                    "联邦学习聚合失败：请求体序列化失败",
                    ex
            );
        }
    }

    private void validateRequest(PythonFederatedAggregateRequest request) {
        if (request == null) {
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_INVALID", "联邦学习聚合失败：聚合请求不能为空");
        }
        if (request.getWorkflowId() == null) {
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_INVALID", "联邦学习聚合失败：workflowId 不能为空");
        }
        if (!StringUtils.hasText(request.getStrategy())) {
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_INVALID", "联邦学习聚合失败：strategy 不能为空");
        }
        if (request.getModelPaths() == null || request.getModelPaths().isEmpty()) {
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_INVALID", "联邦学习聚合失败：modelPaths 不能为空");
        }
        if (!StringUtils.hasText(request.getOutputModelPath())) {
            throw new BusinessException("PYTHON_FEDERATED_REQUEST_INVALID", "联邦学习聚合失败：outputModelPath 不能为空");
        }
    }

    private String writeLogBody(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            log.warn("Failed to serialize Python federated aggregate response body for logging.", ex);
            return String.valueOf(body);
        }
    }
}
