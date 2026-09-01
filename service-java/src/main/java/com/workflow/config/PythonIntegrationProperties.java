package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.integration")
public class PythonIntegrationProperties {

    private String pythonBaseUrl;

    private String javaBaseUrl;

    private String callbackSecret;

    private String algorithmType = "YOLOv10";

    private String pythonResultRootPath;

    private String pythonSampleRootPath;

    private Integer connectTimeoutMs = 10000;

    private Integer readTimeoutMs = 600000;

    public String maskedCallbackSecret() {
        if (callbackSecret == null || callbackSecret.isBlank()) {
            return "(not-set)";
        }
        if (callbackSecret.length() <= 4) {
            return "****";
        }
        return callbackSecret.substring(0, 2) + "****" + callbackSecret.substring(callbackSecret.length() - 2);
    }
}
