package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.model-definition-registry")
public class ModelDefinitionRegistryProperties {

    private boolean enabled = false;
    private Set<String> workflowSelectableRuntimeProfiles =
            new LinkedHashSet<>(Set.of("YOLO_RUNTIME_V1", "INTERNIMAGE_RUNTIME_V1"));

    public boolean isWorkflowSelectable(String runtimeProfileId) {
        return runtimeProfileId != null && workflowSelectableRuntimeProfiles.contains(runtimeProfileId);
    }
}
