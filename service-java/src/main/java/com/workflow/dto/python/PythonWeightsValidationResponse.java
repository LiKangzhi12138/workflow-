package com.workflow.dto.python;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class PythonWeightsValidationResponse {

    private Boolean passed;
    private String reasonCode;
    private String message;
    private JsonNode inspection;
    private JsonNode compatibility;
}
