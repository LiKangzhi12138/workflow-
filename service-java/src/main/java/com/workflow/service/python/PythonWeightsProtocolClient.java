package com.workflow.service.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonWeightsValidationResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class PythonWeightsProtocolClient {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties properties;

    public PythonWeightsProtocolClient(
            @Qualifier("pythonJsonRestTemplate") RestTemplate restTemplate,
            PythonIntegrationProperties properties
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public PythonWeightsValidationResponse validateUpload(
            String weightsPath,
            JsonNode manifest,
            JsonNode descriptor,
            JsonNode trustedModelDefinition
    ) {
        return restTemplate.postForObject(
                properties.getPythonBaseUrl() + "/internal/model-packages/validate-upload",
                Map.of(
                        "weightsPath", weightsPath,
                        "manifest", manifest,
                        "descriptor", descriptor,
                        "trustedModelDefinition", trustedModelDefinition
                ),
                PythonWeightsValidationResponse.class
        );
    }
}
