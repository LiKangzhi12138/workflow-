package com.workflow.dto.python;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PythonFederatedAggregateRequest {

    private Long workflowId;

    private String strategy;

    private List<String> modelPaths;

    private String outputModelPath;

    private Boolean dpEnabled;

    private BigDecimal dpEpsilon;

    private BigDecimal dpDelta;

    private BigDecimal dpClipNorm;

    private BigDecimal dpNoiseMultiplier;

    private Boolean shuffleEnabled;

    private String shuffleBatchNo;

    private Boolean secureAggregationEnabled;

    private String secureAggregationMode;
}
