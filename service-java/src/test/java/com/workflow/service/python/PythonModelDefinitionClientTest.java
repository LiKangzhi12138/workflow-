package com.workflow.service.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonModelDefinitionCheckResponse;
import com.workflow.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonModelDefinitionClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private PythonModelDefinitionClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        PythonIntegrationProperties properties = new PythonIntegrationProperties();
        properties.setPythonBaseUrl("http://python-service");
        client = new PythonModelDefinitionClient(restTemplate, properties);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldCallInternalDefinitionCheckEndpoint() throws Exception {
        server.expect(once(), requestTo("http://python-service/internal/model-definitions/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.runtimeProfileId").value("YOLO_RUNTIME_V1"))
                .andExpect(jsonPath("$.modelDefinition.code").value("YOLOV8N_SHEEP_V1"))
                .andRespond(withSuccess("""
                        {
                          "available":true,
                          "availabilityState":"AVAILABLE",
                          "definitionCode":"YOLOV8N_SHEEP_V1",
                          "reasonCode":"AVAILABLE"
                        }
                        """, MediaType.APPLICATION_JSON));

        PythonModelDefinitionCheckResponse response = client.check(
                objectMapper.readTree("{\"code\":\"YOLOV8N_SHEEP_V1\"}"),
                "YOLO_RUNTIME_V1"
        );

        assertTrue(response.getAvailable());
        assertEquals("AVAILABLE", response.getReasonCode());
        server.verify();
    }

    @Test
    void shouldMapPythonFailureToRuntimeCheckFailed() throws Exception {
        server.expect(requestTo("http://python-service/internal/model-definitions/check"))
                .andRespond(withServerError());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> client.check(objectMapper.readTree("{\"code\":\"x\"}"), "YOLO_RUNTIME_V1")
        );

        assertEquals("RUNTIME_CHECK_FAILED", exception.getCode());
    }
}
