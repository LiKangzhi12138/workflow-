package com.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RestTemplateConfigLoggingSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SensitiveHttpLogSanitizer sanitizer = new SensitiveHttpLogSanitizer(objectMapper);

    @Test
    void redactsNestedSecretsAndSensitiveHeadersWhileKeepingOperationalFields() {
        String json = """
                {
                  "workflowId": 37,
                  "jobId": "job-37",
                  "validationMode": "WEIGHTS_PROTOCOL_V1",
                  "callbackSecret": "very-secret-value",
                  "nested": {
                    "accessToken": "nested-token",
                    "password": "nested-password",
                    "definitionId": "YOLOV8N_SHEEP_V1"
                  }
                }
                """;
        String sanitized = sanitizer.sanitizeText(json);

        assertFalse(sanitized.contains("very-secret-value"));
        assertFalse(sanitized.contains("nested-token"));
        assertFalse(sanitized.contains("nested-password"));
        assertTrue(sanitized.contains("\"workflowId\":37"));
        assertTrue(sanitized.contains("YOLOV8N_SHEEP_V1"));
        assertFalse(sanitizer.sanitizeText("\"unstructured-secret-value\"")
                .contains("unstructured-secret-value"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bearer-value");
        headers.add(HttpHeaders.COOKIE, "SESSION=session-value");
        headers.add(HttpHeaders.SET_COOKIE, "SESSION=response-value");
        headers.add("X-Api-Key", "api-key-value");
        headers.add("X-Workflow-Id", "37");

        String loggedHeaders = sanitizer.sanitizeHeaders(headers).toString();
        assertFalse(loggedHeaders.contains("bearer-value"));
        assertFalse(loggedHeaders.contains("session-value"));
        assertFalse(loggedHeaders.contains("response-value"));
        assertFalse(loggedHeaders.contains("api-key-value"));
        assertTrue(loggedHeaders.contains("X-Workflow-Id=[37]"));
    }

    @Test
    void interceptorLogsV1SummaryButSendsOriginalBodyUnchanged(CapturedOutput output) throws Exception {
        String requestJson = """
                {
                  "workflowId": 37,
                  "jobId": "job-37",
                  "jobType": "WORKFLOW_VALIDATION",
                  "validationMode": "WEIGHTS_PROTOCOL_V1",
                  "datasetPath": "/storage/dataset",
                  "runtimeProfileId": "YOLO_RUNTIME_V1",
                  "callbackSecret": "very-secret-value",
                  "trustedModelDefinition": {
                    "definitionId": "YOLOV8N_SHEEP_V1",
                    "definitionSha256": "302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2",
                    "architectureSignature": "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a",
                    "definitionPayload": {"architecture": {"backbone": "large-architecture-value"}}
                  },
                  "globalWeights": {
                    "assetId": 77,
                    "weightsPath": "/storage/global_weights.pt",
                    "expectedSha256": "532fb6fa32b57a8df6403813b7a2fafe92bdfde8a4ca2737b44694d14170a23f"
                  }
                }
                """;
        byte[] originalBody = requestJson.getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> transmittedBody = new AtomicReference<>();

        HttpRequest request = mock(HttpRequest.class);
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setBearerAuth("request-bearer-value");
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(request.getURI()).thenReturn(URI.create(
                "http://service-python:8000/internal/jobs?access_token=query-token-value"
        ));
        when(request.getHeaders()).thenReturn(requestHeaders);

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add(HttpHeaders.SET_COOKIE, "SESSION=response-cookie-value");
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(
                "{\"code\":\"OK\",\"refreshToken\":\"response-token-value\"}"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        ClientHttpRequestExecution execution = (actualRequest, actualBody) -> {
            transmittedBody.set(actualBody.clone());
            return response;
        };

        RestTemplateConfig.PythonJsonLoggingInterceptor interceptor =
                new RestTemplateConfig.PythonJsonLoggingInterceptor(sanitizer);
        ClientHttpResponse actualResponse = interceptor.intercept(request, originalBody, execution);

        assertSame(response, actualResponse);
        assertArrayEquals(originalBody, transmittedBody.get());
        assertTrue(new String(transmittedBody.get(), StandardCharsets.UTF_8).contains("very-secret-value"));

        String logs = output.getAll();
        assertFalse(logs.contains("very-secret-value"));
        assertFalse(logs.contains("request-bearer-value"));
        assertFalse(logs.contains("response-cookie-value"));
        assertFalse(logs.contains("response-token-value"));
        assertFalse(logs.contains("query-token-value"));
        assertFalse(logs.contains("large-architecture-value"));
        assertTrue(logs.contains("YOLOV8N_SHEEP_V1"));
        assertTrue(logs.contains("WEIGHTS_PROTOCOL_V1"));
        assertTrue(logs.contains("job-37"));
        assertEquals(1, countOccurrences(logs, "pythonJsonRestTemplate outbound request"));
    }

    private int countOccurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
