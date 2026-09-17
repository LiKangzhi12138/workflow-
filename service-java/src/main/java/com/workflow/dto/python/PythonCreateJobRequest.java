package com.workflow.dto.python;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class PythonCreateJobRequest {

    private String jobId;
    private String jobType;
    private Long workflowId;
    private Long standaloneValidationId;
    private String modelPath;
    private String datasetPath;
    private String algorithmType;
    private String callbackUrl;
    private String callbackSecret;
    private String validationMode;
    private String runtimeProfileId;
    private JsonNode trustedModelDefinition;
    private PythonWeightsValidationJobInput globalWeights;
}
