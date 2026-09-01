package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.security")
public class WorkflowSecurityProperties {

    private String allowedOrigins = "";
    private String allowedOriginPatterns = "";

    public List<String> resolvedAllowedOrigins() {
        return splitCsv(allowedOrigins);
    }

    public List<String> resolvedAllowedOriginPatterns() {
        return splitCsv(allowedOriginPatterns);
    }

    private List<String> splitCsv(String raw) {
        return java.util.Arrays.stream((raw == null ? "" : raw).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
