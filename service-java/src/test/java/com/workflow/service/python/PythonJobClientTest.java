package com.workflow.service.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonJobClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createJobSendsExpectedJsonHeadersAndBody() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonJobClient client = new PythonJobClient(restTemplate, pythonProperties(), objectMapper);

        PythonCreateJobRequest request = requestPayload();
        String expectedBody = objectMapper.writeValueAsString(request);
        String responseBody = """
                {
                  "code": "OK",
                  "message": "job accepted",
                  "data": {
                    "jobId": "job-123",
                    "status": "ACCEPTED"
                  }
                }
                """;

        server.expect(requestTo("http://python-service/internal/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json(expectedBody))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        PythonCreateJobResponse response = client.createJob(request);

        assertEquals("OK", response.getCode());
        assertEquals("job-123", response.getData().getJobId());
        server.verify();
    }

    @Test
    void createJobMaps422BodyMissingToClearBusinessMessage() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonJobClient client = new PythonJobClient(restTemplate, pythonProperties(), objectMapper);

        server.expect(requestTo("http://python-service/internal/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}
                                """));

        BusinessException ex = assertThrows(BusinessException.class, () -> client.createJob(requestPayload()));

        assertEquals("Python 创建任务失败：Python 接口未正确接收到请求体", ex.getMessage());
        server.verify();
    }

    private PythonIntegrationProperties pythonProperties() {
        PythonIntegrationProperties properties = new PythonIntegrationProperties();
        properties.setPythonBaseUrl("http://python-service");
        return properties;
    }

    private PythonCreateJobRequest requestPayload() {
        PythonCreateJobRequest request = new PythonCreateJobRequest();
        request.setJobId("job-123");
        request.setJobType("STANDALONE_VALIDATION");
        request.setStandaloneValidationId(55L);
        request.setModelPath("/models/sample.pt");
        request.setDatasetPath("/datasets/sample");
        request.setAlgorithmType("YOLOv10");
        request.setCallbackUrl("http://java-service/api/internal/standalone/callback");
        request.setCallbackSecret("secret");
        return request;
    }
}
