package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.WorkflowModelUploadService;
import com.workflow.support.WorkflowCurrentStepSupport;
import com.workflow.support.WorkflowErrorSummarySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowAcceptAutoManageAsyncService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final WorkflowModelUploadService workflowModelUploadService;
    private final WorkflowAutoManageStatusService workflowAutoManageStatusService;
    private final WorkflowFederatedAggregationService workflowFederatedAggregationService;

    @Async("workflowTaskExecutor")
    public void processAcceptedWorkflowAsync(Long workflowId,
                                             Long serverUserId,
                                             int requiredModelCount,
                                             int actualUploadedCountBeforeAccept) {
        log.info(
                "ACCEPT async auto-manage task started: workflowId={}, requiredModelCount={}, actualUploadedCountBeforeAccept={}, serverUserId={}",
                workflowId,
                requiredModelCount,
                actualUploadedCountBeforeAccept,
                serverUserId
        );

        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || isDeleted(workflow.getIsDeleted())) {
            log.warn("ACCEPT async auto-manage aborted because workflow is missing: workflowId={}", workflowId);
            return;
        }

        List<WorkflowModelUpload> pendingUploads = listPendingAutoManageUploads(workflowId);
        log.info(
                "ACCEPT async auto-manage task loaded candidates: workflowId={}, pendingUploadCount={}",
                workflowId,
                pendingUploads.size()
        );
        if (!pendingUploads.isEmpty()) {
            workflowAutoManageStatusService.updateWorkflowCurrentStep(
                    workflowId,
                    WorkflowCurrentStepSupport.MODEL_DOWNLOADING,
                    "accept-async-download-start",
                    true
            );
        }

        int successCount = 0;
        int failureCount = 0;
        List<String> failureMessages = new ArrayList<>();

        for (WorkflowModelUpload upload : pendingUploads) {
            workflowAutoManageStatusService.updateWorkflowCurrentStep(
                    workflowId,
                    WorkflowCurrentStepSupport.MODEL_DOWNLOADING,
                    "accept-async-per-upload-download",
                    true
            );
            log.info(
                    "ACCEPT async per-upload decrypt start: workflowId={}, uploadId={}, encryptedFilePath={}",
                    workflowId,
                    upload.getId(),
                    upload.getEncryptedFilePath()
            );
            try {
                workflowModelUploadService.decryptModelFile(upload.getId(), serverUserId);
                successCount++;
            } catch (Exception ex) {
                failureCount++;
                failureMessages.add(
                        WorkflowErrorSummarySupport.summarizeUploadFailureForWorkflow(upload.getId(), ex.getMessage())
                );
                log.error(
                        "ACCEPT async per-upload decrypt failed: workflowId={}, uploadId={}, encryptedFilePath={}",
                        workflowId,
                        upload.getId(),
                        upload.getEncryptedFilePath(),
                        ex
                );
            }
        }

        WorkflowAutoManageStatusService.AutoManageStatusSnapshot snapshot =
                workflowAutoManageStatusService.refreshWorkflowAutoManageStatus(workflowId, "accept-async-finalize");
        boolean federatedTriggered = false;
        if (snapshot.getCompletedModelCount() >= snapshot.getRequiredModelCount()) {
            try {
                federatedTriggered = workflowFederatedAggregationService.triggerFederatedAggregationIfReady(
                        workflowId,
                        serverUserId,
                        "accept-async-finalize"
                );
            } catch (Exception ex) {
                log.error(
                        "ACCEPT async federated aggregation failed after decrypt stage: workflowId={}, summary={}",
                        workflowId,
                        WorkflowErrorSummarySupport.summarizeFederatedAggregation(ex),
                        ex
                );
            }
        }

        log.info(
                "ACCEPT async auto-manage task completed: workflowId={}, successCount={}, failureCount={}, requiredModelCount={}, actualManagedModelCountAfterAccept={}, failedUploadCount={}, currentStep={}, federatedTriggered={}, failures={}",
                workflowId,
                successCount,
                failureCount,
                snapshot.getRequiredModelCount(),
                snapshot.getCompletedModelCount(),
                snapshot.getFailedUploadCount(),
                snapshot.getCurrentStep(),
                federatedTriggered,
                String.join(" | ", failureMessages)
        );
    }

    private List<WorkflowModelUpload> listPendingAutoManageUploads(Long workflowId) {
        return workflowModelUploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .eq(WorkflowModelUpload::getUploadStatus, "ENCRYPTED_STORED")
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByAsc(WorkflowModelUpload::getCreatedAt)
                        .orderByAsc(WorkflowModelUpload::getId)
        ).stream()
                .filter(upload -> StringUtils.hasText(upload.getEncryptedFilePath()))
                .toList();
    }

    private boolean isDeleted(Integer value) {
        return value != null && value == 1;
    }
}
