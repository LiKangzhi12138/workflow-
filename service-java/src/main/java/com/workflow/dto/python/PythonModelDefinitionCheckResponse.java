package com.workflow.dto.python;

import lombok.Data;

@Data
public class PythonModelDefinitionCheckResponse {

    private Boolean available;
    private String availabilityState;
    private Object definitionId;
    private String definitionCode;
    private String modelFamily;
    private String variant;
    private String taskType;
    private Boolean adapterAvailable;
    private Boolean runtimeAvailable;
    private Boolean definitionValid;
    private Boolean definitionEnabled;
    private Boolean runtimeEvaluated;
    private Boolean selfCheckPassed;
    private String probeContext;
    private String detectedPythonVersion;
    private String detectedFrameworkVersion;
    private String detectedTorchVersion;
    private Boolean cudaAvailable;
    private String gpuName;
    private String reasonCode;
    private String message;
}
