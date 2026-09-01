package com.workflow.service.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonFederatedAggregateRequest;
import com.workflow.dto.python.PythonFederatedAggregateResponse;
import com.workflow.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonFederatedClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregateSendsExpectedJsonHeadersAndBody() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonFederatedClient client = new PythonFederatedClient(restTemplate, pythonProperties(), objectMapper);

        PythonFederatedAggregateRequest request = requestPayload();
        String expectedBody = objectMapper.writeValueAsString(request);
        String responseBody = """
                {
                  "code": "OK",
                  "message": "federated aggregation completed",
                  "data": {
                    "status": "COMPLETED",
                    "strategy": "FEDML",
                    "sourceModelCount": 2,
                    "globalModelPath": "/tmp/global_model.pt",
                    "summary": "done",
                    "errorMessage": null,
                    "detailMessage": null
                  }
                }
                """;

        server.expect(requestTo("http://python-service/internal/federated/aggregate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json(expectedBody))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        PythonFederatedAggregateResponse response = client.aggregate(request);

        assertEquals("OK", response.getCode());
        assertEquals("/tmp/global_model.pt", response.getData().getGlobalModelPath());
        server.verify();
    }

    @Test
    void aggregateMaps422BodyMissingToClearBusinessMessage() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonFederatedClient client = new PythonFederatedClient(restTemplate, pythonProperties(), objectMapper);

        server.expect(requestTo("http://python-service/internal/federated/aggregate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}
                                """));

        BusinessException ex = assertThrows(BusinessException.class, () -> client.aggregate(requestPayload()));

        assertEquals("联邦学习聚合失败：Python 接口未正确接收到请求体", ex.getMessage());
        server.verify();
    }

    private PythonIntegrationProperties pythonProperties() {
        PythonIntegrationProperties properties = new PythonIntegrationProperties();
        properties.setPythonBaseUrl("http://python-service");
        return properties;
    }

    private PythonFederatedAggregateRequest requestPayload() {
        PythonFederatedAggregateRequest request = new PythonFederatedAggregateRequest();
        request.setWorkflowId(101L);
        request.setStrategy("FEDML");
        request.setModelPaths(List.of("/models/a.pt", "/models/b.pt"));
        request.setOutputModelPath("/models/global_model.pt");
        return request;
    }
}
