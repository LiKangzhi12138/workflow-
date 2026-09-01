package com.workflow.dto.dashboard;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DashboardValidationCardVO {

    private String sourceType;

    private Long workflowId;

    private Long validationId;

    private String taskName;

    private String modelName;

    private String datasetName;

    private String status;

    private Double accuracy;

    private Double precision;

    private Double recall;

    private LocalDateTime updatedAt;

    private String sourceLabel;
}
