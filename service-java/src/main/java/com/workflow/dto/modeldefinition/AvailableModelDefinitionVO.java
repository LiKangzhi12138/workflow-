package com.workflow.dto.modeldefinition;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AvailableModelDefinitionVO {

    private Long id;
    private String code;
    private String displayName;
    private String modelFamily;
    private String version;
    private String variant;
    private String taskType;
    private String framework;
    private String frameworkVersion;
    private List<String> classes;
    private String runtimeProfileId;
    private Boolean workflowSelectable;
    private ModelDefinitionAvailabilityVO availability;
}
