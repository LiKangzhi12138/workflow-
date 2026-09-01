package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class FederatedArtifactCleanupService {

    private static final String COMPLETED = "COMPLETED";
    private static final List<String> ACTIVE_VALIDATION_STATUSES = List.of("INITIATED", "VALIDATING");

    private final WorkflowMapper workflowMapper;
    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final StandaloneValidationMapper standaloneValidationMapper;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final ControlledArtifactDeletionService controlledArtifactDeletionService;
    private final PlatformTransactionManager transactionManager;

    public CleanupSummary cleanupCompletedWorkflowArtifacts(Long workflowId) {
        if (!isCleanupEligible(workflowId)) {
            return CleanupSummary.skipped();
        }

        List<Long> uploadIds = workflowModelUploadMapper.selectList(
                        new LambdaQueryWrapper<WorkflowModelUpload>()
                                .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                                .eq(WorkflowModelUpload::getAggregationStatus, COMPLETED)
                                .eq(WorkflowModelUpload::getIsDeleted, 0)
                                .orderByAsc(WorkflowModelUpload::getId)
                ).stream()
                .map(WorkflowModelUpload::getId)
                .filter(Objects::nonNull)
                .toList();

        CleanupSummary summary = new CleanupSummary(false, 0, 0, 0);
        for (Long uploadId : uploadIds) {
            summary = summary.add(runInNewTransaction(() -> cleanupEncryptedFile(workflowId, uploadId)));
            summary = summary.add(runInNewTransaction(() -> cleanupDecryptedModel(workflowId, uploadId)));
        }

        log.info(
                "Federated intermediate cleanup completed: workflowId={}, encrypted={}, decrypted={}, skippedOrFailed={}",
                workflowId,
                summary.encryptedCleaned(),
                summary.decryptedCleaned(),
                summary.skippedOrFailed()
        );
        return summary;
    }

    public void cleanupEligibleCompletedWorkflowsOnStartup() {
        List<Long> workflowIds = workflowMapper.selectList(
                        new LambdaQueryWrapper<Workflow>()
                                .eq(Workflow::getFederatedStatus, COMPLETED)
                                .isNotNull(Workflow::getFederatedModelAssetId)
                                .eq(Workflow::getIsDeleted, 0)
                                .orderByAsc(Workflow::getId)
                ).stream()
                .map(Workflow::getId)
                .filter(Objects::nonNull)
                .toList();

        for (Long workflowId : workflowIds) {
            try {
                cleanupCompletedWorkflowArtifacts(workflowId);
            } catch (RuntimeException ex) {
                log.warn("Startup federated artifact cleanup failed: workflowId={}, reason={}", workflowId, ex.getMessage());
            }
        }
    }

    private CleanupSummary cleanupEncryptedFile(Long workflowId, Long uploadId) {
        WorkflowModelUpload upload = loadEligibleUpload(workflowId, uploadId);
        if (upload == null || !StringUtils.hasText(upload.getEncryptedFilePath())) {
            return CleanupSummary.skippedArtifact();
        }

        Path encryptedRoot = workflowStorageProperties.modelUploadDirPath()
                .resolve(String.valueOf(workflowId))
                .resolve("encrypted");
        ControlledArtifactDeletionService.DeleteResult result = controlledArtifactDeletionService.deleteRegularFile(
                upload.getEncryptedFilePath(),
                encryptedRoot
        );
        if (!result.removedOrMissing()) {
            log.warn(
                    "Encrypted model cleanup skipped: workflowId={}, uploadId={}, path={}, status={}, reason={}",
                    workflowId,
                    uploadId,
                    upload.getEncryptedFilePath(),
                    result.status(),
                    result.reason()
            );
            return CleanupSummary.skippedArtifact();
        }

        int updated = workflowModelUploadMapper.clearEncryptedFilePath(uploadId, workflowId);
        if (updated != 1) {
            throw new IllegalStateException("清空 AES 密文路径失败，后续启动补偿将重新处理");
        }
        return CleanupSummary.oneEncryptedCleaned();
    }

    private CleanupSummary cleanupDecryptedModel(Long workflowId, Long uploadId) {
        Workflow workflow = loadCleanupEligibleWorkflow(workflowId);
        WorkflowModelUpload upload = loadEligibleUpload(workflowId, uploadId);
        if (workflow == null || upload == null || !StringUtils.hasText(upload.getDecryptedFilePath())) {
            return CleanupSummary.skippedArtifact();
        }
        if (upload.getServerModelAssetId() == null) {
            log.warn("Decrypted model cleanup skipped because asset id is missing: workflowId={}, uploadId={}", workflowId, uploadId);
            return CleanupSummary.skippedArtifact();
        }

        ModelAsset asset = modelAssetMapper.selectById(upload.getServerModelAssetId());
        if (!isOwnedWorkflowDecryptAsset(workflow, upload, asset)) {
            log.warn(
                    "Decrypted model cleanup rejected by asset boundary: workflowId={}, uploadId={}, modelAssetId={}, sourceType={}",
                    workflowId,
                    uploadId,
                    upload.getServerModelAssetId(),
                    asset == null ? null : asset.getSourceType()
            );
            return CleanupSummary.skippedArtifact();
        }
        if (hasActiveStandaloneValidation(asset.getId())) {
            log.warn(
                    "Decrypted model cleanup postponed because standalone validation is active: workflowId={}, uploadId={}, modelAssetId={}",
                    workflowId,
                    uploadId,
                    asset.getId()
            );
            return CleanupSummary.skippedArtifact();
        }

        ControlledArtifactDeletionService.DeleteResult result = controlledArtifactDeletionService.deleteRegularFile(
                asset.getFilePath(),
                workflowStorageProperties.modelRootDirPath()
        );
        if (!result.removedOrMissing()) {
            log.warn(
                    "Decrypted model cleanup skipped: workflowId={}, uploadId={}, modelAssetId={}, path={}, status={}, reason={}",
                    workflowId,
                    uploadId,
                    asset.getId(),
                    asset.getFilePath(),
                    result.status(),
                    result.reason()
            );
            return CleanupSummary.skippedArtifact();
        }

        int assetUpdated = modelAssetMapper.softDeleteWorkflowDecryptAsset(asset.getId(), workflow.getServerUserId());
        if (assetUpdated != 1) {
            throw new IllegalStateException("软删除 WORKFLOW_DECRYPT 模型资产失败，后续启动补偿将重新处理");
        }
        int uploadUpdated = workflowModelUploadMapper.clearDecryptedFilePath(uploadId, workflowId);
        if (uploadUpdated != 1) {
            throw new IllegalStateException("清空解密模型路径失败，后续启动补偿将重新处理");
        }
        return CleanupSummary.oneDecryptedCleaned();
    }

    private WorkflowModelUpload loadEligibleUpload(Long workflowId, Long uploadId) {
        if (!isCleanupEligible(workflowId)) {
            return null;
        }
        WorkflowModelUpload upload = workflowModelUploadMapper.selectById(uploadId);
        if (upload == null
                || !Objects.equals(workflowId, upload.getWorkflowId())
                || !Objects.equals(upload.getIsDeleted(), 0)
                || !COMPLETED.equalsIgnoreCase(upload.getAggregationStatus())) {
            return null;
        }
        return upload;
    }

    private boolean isCleanupEligible(Long workflowId) {
        return loadCleanupEligibleWorkflow(workflowId) != null;
    }

    private Workflow loadCleanupEligibleWorkflow(Long workflowId) {
        if (workflowId == null) {
            return null;
        }
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null
                || !Objects.equals(workflow.getIsDeleted(), 0)
                || !COMPLETED.equalsIgnoreCase(workflow.getFederatedStatus())
                || workflow.getFederatedModelAssetId() == null) {
            return null;
        }

        ModelAsset globalAsset = modelAssetMapper.selectById(workflow.getFederatedModelAssetId());
        if (globalAsset == null
                || !Objects.equals(globalAsset.getIsDeleted(), 0)
                || !Objects.equals(globalAsset.getOwnerUserId(), workflow.getServerUserId())
                || !"SERVER".equalsIgnoreCase(globalAsset.getOwnerRoleCode())
                || !AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT.equalsIgnoreCase(globalAsset.getSourceType())
                || !AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(globalAsset.getRecordMode())) {
            return null;
        }

        ControlledArtifactDeletionService.Inspection inspection = controlledArtifactDeletionService.inspectReadableRegularFile(
                globalAsset.getFilePath(),
                workflowStorageProperties.federatedModelRootDirPath()
        );
        return inspection.valid() ? workflow : null;
    }

    private boolean isOwnedWorkflowDecryptAsset(Workflow workflow, WorkflowModelUpload upload, ModelAsset asset) {
        if (asset == null
                || !Objects.equals(asset.getId(), upload.getServerModelAssetId())
                || !Objects.equals(asset.getIsDeleted(), 0)
                || !Objects.equals(asset.getOwnerUserId(), workflow.getServerUserId())
                || !"SERVER".equalsIgnoreCase(asset.getOwnerRoleCode())
                || !AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT.equalsIgnoreCase(asset.getSourceType())
                || !AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(asset.getRecordMode())
                || !StringUtils.hasText(asset.getFilePath())) {
            return false;
        }
        try {
            Path uploadPath = Path.of(upload.getDecryptedFilePath()).toAbsolutePath().normalize();
            Path assetPath = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            return uploadPath.equals(assetPath);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean hasActiveStandaloneValidation(Long modelAssetId) {
        Long activeCount = standaloneValidationMapper.selectCount(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getModelAssetId, modelAssetId)
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .in(StandaloneValidation::getStatus, ACTIVE_VALIDATION_STATUSES)
        );
        return activeCount != null && activeCount > 0;
    }

    private CleanupSummary runInNewTransaction(CleanupAction action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            CleanupSummary result = template.execute(status -> action.run());
            return result == null ? CleanupSummary.skippedArtifact() : result;
        } catch (RuntimeException ex) {
            log.warn("Federated intermediate artifact cleanup failed and will be retried later: reason={}", ex.getMessage());
            return CleanupSummary.skippedArtifact();
        }
    }

    @FunctionalInterface
    private interface CleanupAction {
        CleanupSummary run();
    }

    public record CleanupSummary(boolean workflowSkipped, int encryptedCleaned, int decryptedCleaned, int skippedOrFailed) {

        public static CleanupSummary skipped() {
            return new CleanupSummary(true, 0, 0, 0);
        }

        public static CleanupSummary skippedArtifact() {
            return new CleanupSummary(false, 0, 0, 1);
        }

        public static CleanupSummary oneEncryptedCleaned() {
            return new CleanupSummary(false, 1, 0, 0);
        }

        public static CleanupSummary oneDecryptedCleaned() {
            return new CleanupSummary(false, 0, 1, 0);
        }

        public CleanupSummary add(CleanupSummary other) {
            return new CleanupSummary(
                    workflowSkipped && other.workflowSkipped,
                    encryptedCleaned + other.encryptedCleaned,
                    decryptedCleaned + other.decryptedCleaned,
                    skippedOrFailed + other.skippedOrFailed
            );
        }
    }
}
