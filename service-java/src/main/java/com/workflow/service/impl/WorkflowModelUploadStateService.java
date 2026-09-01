package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.support.WorkflowErrorSummarySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowModelUploadStateService {

    private static final String UPLOAD_STATUS_DECRYPTING = "DECRYPTING";
    private static final String UPLOAD_STATUS_COMPLETED = "COMPLETED";
    private static final String UPLOAD_STATUS_FAILED = "FAILED";

    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final WorkflowMapper workflowMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDecrypting(Long uploadId) {
        WorkflowModelUpload record = workflowModelUploadMapper.selectTrackedById(uploadId);
        if (record == null || isDeleted(record.getIsDeleted())) {
            log.warn("Skip marking decrypting because upload record is missing: uploadId={}", uploadId);
            return;
        }

        workflowModelUploadMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getId, uploadId)
                        .set(WorkflowModelUpload::getUploadStatus, UPLOAD_STATUS_DECRYPTING)
                        .set(WorkflowModelUpload::getAggregationStatus, "PENDING")
                        .set(WorkflowModelUpload::getErrorMessage, null)
        );

        log.info("Marked workflow model upload as decrypting: uploadId={}", uploadId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDecryptSucceeded(Long workflowId,
                                     Long uploadId,
                                     String decryptedFilePath,
                                     Long serverModelAssetId) {
        WorkflowModelUpload record = workflowModelUploadMapper.selectTrackedById(uploadId);
        if (record == null || isDeleted(record.getIsDeleted())) {
            log.warn("Skip marking decrypt success because upload record is missing: uploadId={}", uploadId);
            return;
        }

        WorkflowModelUpload update = new WorkflowModelUpload();
        update.setId(uploadId);
        update.setDecryptedFilePath(decryptedFilePath);
        update.setServerModelAssetId(serverModelAssetId);
        update.setUploadStatus(UPLOAD_STATUS_COMPLETED);
        update.setAggregationStatus("READY");
        update.setErrorMessage(null);
        workflowModelUploadMapper.updateDecryptResult(update);

        if (!UPLOAD_STATUS_COMPLETED.equals(record.getUploadStatus())) {
            workflowMapper.incrementCollectedModelCount(resolveWorkflowId(workflowId, record));
        }

        log.info(
                "Persisted workflow model upload success state: workflowId={}, uploadId={}, finalDecryptedPath={}, serverModelAssetId={}",
                resolveWorkflowId(workflowId, record),
                uploadId,
                decryptedFilePath,
                serverModelAssetId
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markDecryptFailed(Long uploadId, String decryptedFilePath, String errorMessage) {
        WorkflowModelUpload record = workflowModelUploadMapper.selectTrackedById(uploadId);
        if (record == null || isDeleted(record.getIsDeleted())) {
            log.warn("Skip marking decrypt failure because upload record is missing: uploadId={}", uploadId);
            return;
        }
        String summarizedError = WorkflowErrorSummarySupport.summarizeForUpload(
                errorMessage,
                "模型解密失败，请查看服务端日志"
        );

        if (StringUtils.hasText(decryptedFilePath)) {
            record.setDecryptedFilePath(decryptedFilePath);
        }
        record.setServerModelAssetId(null);
        record.setUploadStatus(UPLOAD_STATUS_FAILED);
        record.setAggregationStatus("FAILED");
        record.setErrorMessage(summarizedError);
        workflowModelUploadMapper.updateDecryptFailure(uploadId, decryptedFilePath, summarizedError);

        log.info(
                "Marked workflow model upload as failed: uploadId={}, decryptedFilePath={}, errorMessage={}",
                uploadId,
                decryptedFilePath,
                summarizedError
        );
    }

    private Long resolveWorkflowId(Long workflowId, WorkflowModelUpload record) {
        if (workflowId != null) {
            return workflowId;
        }
        return record != null ? record.getWorkflowId() : null;
    }

    private boolean isDeleted(Integer value) {
        return value != null && value == 1;
    }
}
