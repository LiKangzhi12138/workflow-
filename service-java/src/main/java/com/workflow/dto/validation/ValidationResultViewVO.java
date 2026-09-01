package com.workflow.dto.validation;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ValidationResultViewVO {

    private String validationMode;

    private Long validationId;

    private Long workflowId;

    private String validationCode;

    private String modelName;

    private String datasetName;

    private String algorithmType;

    private String status;

    private Integer progress;

    private String pythonJobId;

    private Integer datasetSampleCount;

    private Integer datasetImageCount;

    private Double accuracy;

    private Double precision;

    private Double recall;

    private Double map50;

    private Double map50_95;

    private Boolean fallback;

    private String fallbackReason;

    private String qualityLabel;

    private String errorMessage;

    private Boolean resultFileAvailable;

    private String visualizationMode;

    private String resultRetentionStatus;

    private String emptyVisualizationReason;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private List<ValidationResultClassVO> classResults;

    private List<ValidationResultSampleVO> sampleResults;
}
