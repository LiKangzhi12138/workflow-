package com.workflow.dto.workflow;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowStepVO {

    private Integer stepNo;
    private String stepCode;
    private String stepName;
    private String fromStatus;
    private String toStatus;
    private Long operatorUserId;
    private String operatorRole;
    private String message;
    private LocalDateTime createdAt;
}