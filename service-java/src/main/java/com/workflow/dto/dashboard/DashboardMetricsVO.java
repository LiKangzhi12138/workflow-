package com.workflow.dto.dashboard;

import lombok.Data;

@Data
public class DashboardMetricsVO {

    private long totalWorkflows;

    private long pendingServerReceiveCount;

    private long pendingReceiveWorkflowCount;

    private long pendingDecryptCount;

    private long pendingValidationCount;

    private long completedValidationCount;

    private Double latestAccuracy;

    private String latestAccuracySourceType;

    private String latestAccuracySourceLabel;
}
