package com.workflow.dto.python;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class PythonWeightsFedAvgResponse {

    private Boolean passed;
    private String reasonCode;
    private String message;
    private Long workflowId;
    private Object modelDefinitionId;
    private String modelDefinitionCode;
    private Integer inputCount;
    private List<Long> inputAssetIds;
    private String aggregationMethod;
    private List<Double> aggregationWeights;
    private String outputWeightsPath;
    private String outputManifestPath;
    private String outputDescriptorPath;
    private String outputInspectionPath;
    private String outputReportPath;
    private String outputSha256;
    private JsonNode inspection;
}
