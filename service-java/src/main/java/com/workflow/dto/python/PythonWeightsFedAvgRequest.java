package com.workflow.dto.python;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class PythonWeightsFedAvgRequest {

    private Long workflowId;
    private JsonNode trustedModelDefinition;
    private List<Input> inputs;
    private String outputDirectory;
    private String aggregationMethod = "EQUAL_FEDAVG";

    @Data
    public static class Input {
        private Long assetId;
        private Long uploadId;
        private String weightsPath;
        private String expectedSha256;
        private String manifestPath;
        private String descriptorPath;
        private String inspectionReportPath;
    }
}
