package com.workflow.service.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.SensitiveHttpLogSanitizer;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.exception.BusinessException;
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
import java.util.Map;

@Service
@Slf4j
public class PythonJobClient {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final SensitiveHttpLogSanitizer logSanitizer;

    public PythonJobClient(@Qualifier("pythonJsonRestTemplate") RestTemplate restTemplate,
                           PythonIntegrationProperties properties,
                           ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.logSanitizer = new SensitiveHttpLogSanitizer(objectMapper);
    }

    public PythonCreateJobResponse createJob(PythonCreateJobRequest request) {
        validateRequest(request);

        String url = properties.getPythonBaseUrl() + "/internal/jobs";
        String requestBody = writeRequestBody(request);
        HttpHeaders headers = buildJsonHeaders();

        log.info(
                "Calling Python create job: jobType={}, validationMode={}, workflowId={}, standaloneValidationId={}, jobId={}, url={}, contentType={}, accept={}, modelPath={}, datasetPath={}, callbackUrl={}, payloadBytes={}",
                request.getJobType(),
                request.getValidationMode(),
                request.getWorkflowId(),
                request.getStandaloneValidationId(),
                request.getJobId(),
                url,
                headers.getContentType(),
                headers.getAccept(),
                request.getModelPath(),
                request.getDatasetPath(),
                request.getCallbackUrl(),
                requestBody.getBytes(StandardCharsets.UTF_8).length
        );

        try {
            HttpEntity<PythonCreateJobRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<PythonCreateJobResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, PythonCreateJobResponse.class);

            log.info(
                    "Python create job response received: jobType={}, workflowId={}, standaloneValidationId={}, statusCode={}, responseBody={}",
                    request.getJobType(),
                    request.getWorkflowId(),
                    request.getStandaloneValidationId(),
                    response.getStatusCode(),
                    logSanitizer.sanitizeText(writeLogBody(response.getBody()))
            );

            PythonCreateJobResponse body = validateResponse(response);
            log.info(
                    "Python job accepted, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}",
                    request.getJobType(),
                    request.getWorkflowId(),
                    request.getStandaloneValidationId(),
                    body.getData().getJobId(),
                    body.getData().getStatus()
            );
            return body;
        } catch (HttpStatusCodeException ex) {
            String responseBody = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
            String summary = resolveRemoteSummary(responseBody);
            log.error(
                    "Python create job HTTP error: jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, statusCode={}, responseBody={}",
                    request.getJobType(),
                    request.getWorkflowId(),
                    request.getStandaloneValidationId(),
                    request.getJobId(),
                    ex.getStatusCode(),
                    logSanitizer.sanitizeText(responseBody)
            );
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_FAILED", summary, ex);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            String summary = summarizeUnexpectedJobError(ex.getMessage());
            log.error(
                    "Python create job invocation failed unexpectedly: jobType={}, workflowId={}, standaloneValidationId={}, exceptionType={}",
                    request.getJobType(),
                    request.getWorkflowId(),
                    request.getStandaloneValidationId(),
                    ex.getClass().getSimpleName()
            );
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_FAILED", summary, ex);
        }
    }

    public void checkSegmentationDataset(JsonNode trustedDefinition, String runtimeProfileId, String datasetPath) {
        String url = properties.getPythonBaseUrl() + "/internal/validation/check-dataset-v1";
        Map<String, Object> payload = Map.of(
                "modelDefinition", trustedDefinition,
                "runtimeProfileId", runtimeProfileId,
                "datasetPath", datasetPath
        );
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload, buildJsonHeaders()), JsonNode.class);
            JsonNode result = response.getBody();
            if (result == null || !result.path("passed").asBoolean(false)) {
                throw new BusinessException("DATASET_PREFLIGHT_FAILED", "数据集验证未通过，请检查图片与标签。");
            }
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() != 422) {
                throw new BusinessException("RUNTIME_REQUEST_FAILED", "模型运行服务暂不可用，请稍后重试。", ex);
            }
            String code = "DATASET_PREFLIGHT_FAILED";
            try {
                JsonNode detail = objectMapper.readTree(ex.getResponseBodyAsString(StandardCharsets.UTF_8)).path("detail");
                String remoteCode = detail.path("reasonCode").asText();
                if (List.of("MASK_MISSING", "MASK_LABEL_INVALID", "DATASET_INVALID", "PATH_NOT_ALLOWED",
                        "MODEL_DEFINITION_MISMATCH", "RUNTIME_REQUEST_SCHEMA_INVALID").contains(remoteCode)) {
                    code = remoteCode;
                }
            } catch (Exception ignored) {
                // Remote errors are untrusted; a generic message is safer than their raw body.
            }
            String message = switch (code) {
                case "MASK_MISSING" -> "图片与标签文件名不匹配，或缺少对应的 PNG 标签。";
                case "MASK_LABEL_INVALID" -> "分割标签包含模型定义之外的类别值。";
                case "DATASET_INVALID" -> "分割数据集需要可读取、尺寸一致的 images/ 与 masks/ 文件。";
                case "PATH_NOT_ALLOWED" -> "验证数据集不在受控存储目录内。";
                case "MODEL_DEFINITION_MISMATCH" -> "数据集检查的模型定义不一致。";
                case "RUNTIME_REQUEST_SCHEMA_INVALID" -> "模型运行服务请求格式不匹配。";
                default -> "分割数据集检查失败，请查看服务端日志。";
            };
            throw new BusinessException(code, message, ex);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException("RUNTIME_UNAVAILABLE", "模型运行服务连接失败，请稍后重试。", ex);
        }
    }

    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private PythonCreateJobResponse validateResponse(ResponseEntity<PythonCreateJobResponse> response) {
        if (response == null || response.getBody() == null) {
            throw new BusinessException("PYTHON_CREATE_JOB_EMPTY_RESPONSE", "Python 创建任务失败：Python 服务返回了空响应");
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new BusinessException(
                    "PYTHON_CREATE_JOB_HTTP_STATUS_INVALID",
                    "Python 创建任务失败：Python 服务返回异常状态 " + response.getStatusCode()
            );
        }

        PythonCreateJobResponse body = response.getBody();
        if (!"OK".equals(body.getCode())) {
            throw new BusinessException(
                    "PYTHON_CREATE_JOB_FAILED",
                    summarizeUnexpectedJobError(body.getMessage())
            );
        }
        if (body.getData() == null || !StringUtils.hasText(body.getData().getJobId())) {
            throw new BusinessException("PYTHON_CREATE_JOB_INVALID_RESPONSE", "Python 创建任务失败：响应缺少 jobId");
        }
        return body;
    }

    private String writeRequestBody(PythonCreateJobRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            throw new BusinessException("PYTHON_CREATE_JOB_SERIALIZE_FAILED", "Python 创建任务失败：请求体序列化失败", ex);
        }
    }

    private void validateRequest(PythonCreateJobRequest request) {
        if (request == null) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：请求体不能为空");
        }
        if (!StringUtils.hasText(request.getJobId())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：jobId 不能为空");
        }
        if (!StringUtils.hasText(request.getJobType())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：jobType 不能为空");
        }
        if (!StringUtils.hasText(request.getModelPath())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：modelPath 不能为空");
        }
        if (!StringUtils.hasText(request.getDatasetPath())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：datasetPath 不能为空");
        }
        if (!StringUtils.hasText(request.getCallbackUrl())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：callbackUrl 不能为空");
        }
        if (!StringUtils.hasText(request.getCallbackSecret())) {
            throw new BusinessException("PYTHON_CREATE_JOB_REQUEST_INVALID", "Python 创建任务失败：callbackSecret 不能为空");
        }
        if ("WEIGHTS_PROTOCOL_V1".equals(request.getValidationMode())) {
            if (!StringUtils.hasText(request.getRuntimeProfileId())
                    || request.getTrustedModelDefinition() == null
                    || request.getGlobalWeights() == null) {
                throw new BusinessException(
                        "PYTHON_CREATE_JOB_REQUEST_INVALID",
                        "weights-only Validation 请求缺少可信模型定义或全局权重证据。"
                );
            }
        }
    }

    private String resolveRemoteSummary(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "Python 创建任务失败：Python 服务没有返回错误详情";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.hasNonNull("summary")) {
                return summarizeUnexpectedJobError(root.get("summary").asText());
            }
            if (root.hasNonNull("detail")) {
                return summarizeUnexpectedJobError(root.get("detail").toString());
            }
        } catch (Exception ex) {
            log.warn(
                    "Failed to parse Python create job error body as JSON, responseBytes={}",
                    responseBody.getBytes(StandardCharsets.UTF_8).length
            );
        }
        return summarizeUnexpectedJobError(responseBody);
    }

    private String summarizeUnexpectedJobError(String rawMessage) {
        String normalized = rawMessage == null ? "" : rawMessage.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("\"loc\":[\"body\"]")
                || lower.contains("field required")
                || lower.contains("unprocessable_entity")
                || lower.contains("unprocessable entity")) {
            return "Python 创建任务失败：Python 接口未正确接收到请求体";
        }
        if (lower.contains("standalonevalidationid is required")) {
            return "Python 创建任务失败：standaloneValidationId 缺失";
        }
        if (lower.contains("workflowid is required")) {
            return "Python 创建任务失败：workflowId 缺失";
        }
        if (!StringUtils.hasText(normalized)) {
            return "Python 创建任务失败：请查看 Python 服务日志";
        }
        return normalized.length() > 180 ? normalized.substring(0, 177) + "..." : normalized;
    }

    private String writeLogBody(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            log.warn("Failed to serialize Python create job response body for logging.", ex);
            return String.valueOf(body);
        }
    }
}
