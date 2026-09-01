package com.workflow.controller;

import com.workflow.config.PythonIntegrationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
public class HealthController {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties pythonIntegrationProperties;

    public HealthController(RestTemplate restTemplate,
                            PythonIntegrationProperties pythonIntegrationProperties) {
        this.restTemplate = restTemplate;
        this.pythonIntegrationProperties = pythonIntegrationProperties;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "code", "OK",
                "message", "java ok"
        );
    }

    @GetMapping("/api/health/python")
    public Object pythonHealth() {
        return restTemplate.getForObject(
                pythonIntegrationProperties.getPythonBaseUrl() + "/internal/ping",
                Object.class
        );
    }
}
