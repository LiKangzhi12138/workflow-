package com.workflow.dto.dashboard;

import lombok.Data;

@Data
public class DashboardOverviewVO {

    private String roleCode;

    private DashboardMetricsVO metrics;

    private DashboardWorkflowCardVO latestWorkflow;

    private DashboardValidationCardVO recentValidation;

    private DashboardModelCardVO modelOverview;
}
