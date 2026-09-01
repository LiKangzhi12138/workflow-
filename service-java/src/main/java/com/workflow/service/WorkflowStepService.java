package com.workflow.service;

import com.workflow.entity.WorkflowStep;

public interface WorkflowStepService {

    WorkflowStep appendWorkflowStep(Long workflowId,
                                    String stepCode,
                                    String stepName,
                                    String fromStatus,
                                    String toStatus,
                                    Long operatorUserId,
                                    String operatorRole,
                                    String message);
}
