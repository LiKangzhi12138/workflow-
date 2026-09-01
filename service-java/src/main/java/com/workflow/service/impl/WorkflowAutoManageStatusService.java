package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.support.WorkflowCurrentStepSupport;
import com.workflow.support.WorkflowErrorSummarySupport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowAutoManageStatusService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final WorkflowModelUploadSummaryService workflowModelUploadSummaryService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AutoManageStatusSnapshot refreshWorkflowAutoManageStatus(Long workflowId, String triggerSource) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || isDeleted(workflow.getIsDeleted())) {
            log.warn(
                    "Skip refreshing workflow auto-manage status because workflow is missing: workflowId={}, triggerSource={}",
                    workflowId,
                    triggerSource
            );
            return new AutoManageStatusSnapshot(0, 0, 0, "WORKFLOW_MISSING", null);
        }

        if (!"ACCEPTED".equals(workflow.getStatus())) {
            log.info(
                    "Skip refreshing workflow auto-manage status because workflow is no longer ACCEPTED: workflowId={}, status={}, triggerSource={}",
                    workflowId,
                    workflow.getStatus(),
                    triggerSource
            );
            return new AutoManageStatusSnapshot(
                    requiredModelCount(workflow),
                    workflowModelUploadSummaryService.summarizeWorkflow(workflowId).getCompletedModelCount(),
                    0,
                    workflow.getCurrentStep(),
                    workflow.getErrorMessage()
            );
        }

        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary =
                workflowModelUploadSummaryService.summarizeWorkflow(workflowId);
        List<WorkflowModelUpload> failedUploads = workflowModelUploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .eq(WorkflowModelUpload::getUploadStatus, "FAILED")
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByDesc(WorkflowModelUpload::getUpdatedAt)
                        .orderByDesc(WorkflowModelUpload::getId)
        );

        int requiredModelCount = requiredModelCount(workflow);
        int completedModelCount = summary.getCompletedModelCount();
        int failedUploadCount = failedUploads.size();

        String currentStep;
        String errorMessage = null;
        if (failedUploadCount > 0 && completedModelCount < requiredModelCount) {
            currentStep = WorkflowCurrentStepSupport.AUTO_DECRYPT_FAILED;
            errorMessage = buildFailureMessage(failedUploads);
        } else if (completedModelCount >= requiredModelCount) {
            if (WorkflowFederatedAggregationService.FEDERATED_STATUS_FAILED.equalsIgnoreCase(workflow.getFederatedStatus())) {
                currentStep = WorkflowCurrentStepSupport.FEDERATED_FAILED;
                errorMessage = WorkflowErrorSummarySupport.summarizeForWorkflow(
                        workflow.getErrorMessage(),
                        "联邦学习聚合失败，请查看服务端日志"
                );
            } else if (WorkflowFederatedAggregationService.FEDERATED_STATUS_COMPLETED.equalsIgnoreCase(workflow.getFederatedStatus())) {
                currentStep = WorkflowCurrentStepSupport.FEDERATED_COMPLETED;
            } else {
                currentStep = WorkflowCurrentStepSupport.FEDERATED_AGGREGATING;
            }
        } else {
            currentStep = WorkflowCurrentStepSupport.AUTO_DECRYPTING;
        }

        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                currentStep,
                "refresh-auto-manage-" + triggerSource
        );
        workflow.setErrorMessage(errorMessage);
        workflowMapper.updateById(workflow);

        log.info(
                "Refreshed workflow auto-manage status: workflowId={}, triggerSource={}, requiredModelCount={}, completedModelCount={}, failedUploadCount={}, currentStep={}",
                workflowId,
                triggerSource,
                requiredModelCount,
                completedModelCount,
                failedUploadCount,
                currentStep
        );

        return new AutoManageStatusSnapshot(
                requiredModelCount,
                completedModelCount,
                failedUploadCount,
                currentStep,
                errorMessage
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateWorkflowCurrentStep(Long workflowId,
                                          String currentStep,
                                          String triggerSource,
                                          boolean clearErrorMessage) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || isDeleted(workflow.getIsDeleted())) {
            log.warn(
                    "Skip updating workflow current step because workflow is missing: workflowId={}, triggerSource={}",
                    workflowId,
                    triggerSource
            );
            return;
        }

        WorkflowCurrentStepSupport.applyCurrentStep(workflow, currentStep, triggerSource);
        if (clearErrorMessage) {
            workflow.setErrorMessage(null);
        }
        workflowMapper.updateById(workflow);

        log.info(
                "Updated workflow current step: workflowId={}, triggerSource={}, currentStep={}, clearErrorMessage={}",
                workflowId,
                triggerSource,
                workflow.getCurrentStep(),
                clearErrorMessage
        );
    }

    private String buildFailureMessage(List<WorkflowModelUpload> failedUploads) {
        if (failedUploads == null || failedUploads.isEmpty()) {
            return null;
        }
        return failedUploads.stream()
                .limit(3)
                .map(upload -> WorkflowErrorSummarySupport.summarizeUploadFailureForWorkflow(
                        upload.getId(),
                        defaultString(upload.getErrorMessage())
                ))
                .collect(Collectors.joining(" | "));
    }

    private String defaultString(String value) {
        return value == null || value.isBlank() ? "未知失败" : value;
    }

    private int requiredModelCount(Workflow workflow) {
        if (workflow == null) {
            return 1;
        }
        Integer expectedModelCount = workflow.getExpectedModelCount();
        if (expectedModelCount != null && expectedModelCount > 0) {
            return expectedModelCount;
        }
        Integer clientModelCount = workflow.getClientModelCount();
        return clientModelCount == null || clientModelCount <= 0 ? 1 : clientModelCount;
    }

    private boolean isDeleted(Integer value) {
        return value != null && value == 1;
    }

    @Getter
    public static class AutoManageStatusSnapshot {
        private final int requiredModelCount;
        private final int completedModelCount;
        private final int failedUploadCount;
        private final String currentStep;
        private final String errorMessage;

        public AutoManageStatusSnapshot(int requiredModelCount,
                                        int completedModelCount,
                                        int failedUploadCount,
                                        String currentStep,
                                        String errorMessage) {
            this.requiredModelCount = requiredModelCount;
            this.completedModelCount = completedModelCount;
            this.failedUploadCount = failedUploadCount;
            this.currentStep = currentStep;
            this.errorMessage = errorMessage;
        }
    }
}
