package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FederatedGlobalModelDeletionService {

    private static final Set<String> DELETABLE_WORKFLOW_STATUSES = Set.of("COMPLETED", "FAILED", "WITHDRAWN");
    private static final List<String> ACTIVE_STANDALONE_STATUSES = List.of("INITIATED", "VALIDATING");

    private final WorkflowMapper workflowMapper;
    private final StandaloneValidationMapper standaloneValidationMapper;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final ControlledArtifactDeletionService controlledArtifactDeletionService;

    public DeletionPlan prepareDeletion(ModelAsset asset, SysUser currentUser) {
        if (asset == null || currentUser == null
                || !"SERVER".equalsIgnoreCase(currentUser.getRoleCode())
                || !"SERVER".equalsIgnoreCase(asset.getOwnerRoleCode())
                || !Objects.equals(asset.getOwnerUserId(), currentUser.getId())) {
            throw new BusinessException("FEDERATED_MODEL_DELETE_FORBIDDEN", "只有该联邦全局模型所属的服务端用户可以删除模型。");
        }
        if (!AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT.equalsIgnoreCase(asset.getSourceType())) {
            throw new BusinessException("FEDERATED_MODEL_SOURCE_INVALID", "当前模型不是联邦全局模型，不能使用联邦模型物理删除流程。");
        }

        List<Workflow> references = workflowMapper.selectList(
                new LambdaQueryWrapper<Workflow>()
                        .eq(Workflow::getFederatedModelAssetId, asset.getId())
                        .eq(Workflow::getIsDeleted, 0)
                        .orderByAsc(Workflow::getId)
        );
        if (references.size() > 1) {
            throw new BusinessException(
                    "FEDERATED_MODEL_SHARED_REFERENCE",
                    "该联邦全局模型被多个工作流引用，为避免误删共享文件，暂时不能删除。"
            );
        }
        if (references.size() == 1) {
            validateWorkflowState(references.get(0));
        }

        Long activeStandaloneCount = standaloneValidationMapper.selectCount(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getModelAssetId, asset.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .in(StandaloneValidation::getStatus, ACTIVE_STANDALONE_STATUSES)
        );
        if (activeStandaloneCount != null && activeStandaloneCount > 0) {
            throw new BusinessException(
                    "FEDERATED_MODEL_STANDALONE_VALIDATION_RUNNING",
                    "当前联邦全局模型正在被独立验证任务使用，请等待任务结束后再删除。"
            );
        }

        Path federatedRoot = workflowStorageProperties.federatedModelRootDirPath();
        ControlledArtifactDeletionService.Inspection inspection =
                controlledArtifactDeletionService.inspectReadableRegularFile(asset.getFilePath(), federatedRoot);
        if (inspection.status() == ControlledArtifactDeletionService.InspectionStatus.REJECTED) {
            throw new BusinessException(
                    "FEDERATED_MODEL_PATH_UNSAFE",
                    "联邦全局模型路径未通过受控目录安全校验，已拒绝删除：" + inspection.reason()
            );
        }

        return new DeletionPlan(asset.getId(), asset.getFilePath(), federatedRoot, inspection.status());
    }

    public void schedulePhysicalDeletionAfterCommit(DeletionPlan plan) {
        if (plan == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn(
                    "Federated global model physical deletion was not scheduled because transaction synchronization is inactive: modelAssetId={}, filePath={}",
                    plan.modelAssetId(),
                    plan.filePath()
            );
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletePhysicalFileBestEffort(plan);
            }
        });
    }

    private void validateWorkflowState(Workflow workflow) {
        if ("VALIDATING".equalsIgnoreCase(workflow.getStatus())) {
            throw new BusinessException(
                    "FEDERATED_MODEL_WORKFLOW_VALIDATION_RUNNING",
                    "当前联邦全局模型正在被工作流验证使用，请等待验证完成后再删除。"
            );
        }
        if (!DELETABLE_WORKFLOW_STATUSES.contains(workflow.getStatus())) {
            throw new BusinessException(
                    "FEDERATED_MODEL_WORKFLOW_ACTIVE",
                    "当前工作流尚未结束，暂时不能删除联邦全局模型。请等待工作流完成、失败或撤回后再操作。"
            );
        }
    }

    private void deletePhysicalFileBestEffort(DeletionPlan plan) {
        try {
            ControlledArtifactDeletionService.DeleteResult result = controlledArtifactDeletionService.deleteRegularFile(
                    plan.filePath(),
                    plan.federatedRoot()
            );
            if (result.removedOrMissing()) {
                log.info(
                        "Federated global model physical cleanup completed: modelAssetId={}, filePath={}, status={}",
                        plan.modelAssetId(),
                        plan.filePath(),
                        result.status()
                );
                return;
            }
            log.warn(
                    "Federated global model is soft deleted but physical cleanup failed: modelAssetId={}, filePath={}, status={}, reason={}",
                    plan.modelAssetId(),
                    plan.filePath(),
                    result.status(),
                    result.reason()
            );
        } catch (RuntimeException ex) {
            log.warn(
                    "Federated global model is soft deleted but physical cleanup raised an exception: modelAssetId={}, filePath={}, reason={}",
                    plan.modelAssetId(),
                    plan.filePath(),
                    ex.getMessage()
            );
        }
    }

    public record DeletionPlan(
            Long modelAssetId,
            String filePath,
            Path federatedRoot,
            ControlledArtifactDeletionService.InspectionStatus initialFileStatus
    ) {
    }
}
