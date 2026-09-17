package com.workflow.dto.python;

import lombok.Data;

@Data
public class PythonWeightsValidationJobInput {

    private Long assetId;
    private String weightsPath;
    private String expectedSha256;
    private String manifestPath;
    private String descriptorPath;
    private String inspectionReportPath;
    private String aggregationReportPath;
    private Integer expectedTensorCount;
    private Long expectedElementCount;
    private String expectedStateDictKeyHash;
    private String expectedShapeSignature;
    private String expectedDtypeSignature;
}
