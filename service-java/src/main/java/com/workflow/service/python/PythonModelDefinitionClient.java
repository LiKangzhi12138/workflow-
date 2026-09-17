package com.workflow.service.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonModelDefinitionCheckRequest;
import com.workflow.dto.python.PythonModelDefinitionCheckResponse;
import com.workflow.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
@Slf4j
public class PythonModelDefinitionClient {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties properties;

    public PythonModelDefinitionClient(
            @Qualifier("pythonJsonRestTemplate") RestTemplate restTemplate,
            PythonIntegrationProperties properties
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public PythonModelDefinitionCheckResponse check(JsonNode definition, String runtimeProfileId) {
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("modelDefinition must not be null");
        }
        if (runtimeProfileId == null || runtimeProfileId.isBlank()) {
            throw new IllegalArgumentException("runtimeProfileId must not be blank");
        }

        String url = properties.getPythonBaseUrl() + "/internal/model-definitions/check";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        PythonModelDefinitionCheckRequest request =
                new PythonModelDefinitionCheckRequest(definition, runtimeProfileId);

        try {
            ResponseEntity<PythonModelDefinitionCheckResponse> response = restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(request, headers),
                    PythonModelDefinitionCheckResponse.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new BusinessException(
                        "RUNTIME_CHECK_FAILED",
                        "Python 模型定义检查服务未返回有效响应。"
                );
            }
            return response.getBody();
        } catch (HttpStatusCodeException ex) {
            log.warn(
                    "Python model definition check failed: runtimeProfileId={}, statusCode={}",
                    runtimeProfileId,
                    ex.getStatusCode()
            );
            throw new BusinessException(
                    "RUNTIME_CHECK_FAILED",
                    "Python 模型定义检查服务调用失败。",
                    ex
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn(
                    "Python model definition check failed unexpectedly: runtimeProfileId={}",
                    runtimeProfileId,
                    ex
            );
            throw new BusinessException(
                    "RUNTIME_CHECK_FAILED",
                    "Python 模型定义检查服务暂不可用。",
                    ex
            );
        }
    }
}
