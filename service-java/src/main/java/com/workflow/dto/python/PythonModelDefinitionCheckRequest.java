package com.workflow.dto.python;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PythonModelDefinitionCheckRequest {

    private JsonNode modelDefinition;
    private String runtimeProfileId;
}
