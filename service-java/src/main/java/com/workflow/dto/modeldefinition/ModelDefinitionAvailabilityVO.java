package com.workflow.dto.modeldefinition;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ModelDefinitionAvailabilityVO {

    private Boolean available;
    private String state;
    private String reasonCode;
    private String message;
    private Boolean definitionValid;
    private Boolean adapterAvailable;
    private Boolean runtimeEvaluated;
    private Boolean runtimeAvailable;
    private Boolean selfCheckPassed;
    private String probeContext;
}
