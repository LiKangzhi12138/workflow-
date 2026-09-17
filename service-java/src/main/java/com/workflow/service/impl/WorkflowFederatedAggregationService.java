package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonFederatedAggregateRequest;
import com.workflow.dto.python.PythonFederatedAggregateResponse;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.WorkflowStepService;
import com.workflow.service.python.PythonFederatedClient;
import com.workflow.support.AssetSourceCatalog;
import com.workflow.support.WorkflowCurrentStepSupport;
import com.workflow.support.WorkflowErrorSummarySupport;
import com.workflow.support.WorkflowStepTextCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowFederatedAggregationService {

    public static final String FEDERATED_STATUS_PENDING = "PENDING";
    public static final String FEDERATED_STATUS_RUNNING = "RUNNING";
    public static final String FEDERATED_STATUS_COMPLETED = "COMPLETED";
    public static final String FEDERATED_STATUS_FAILED = "FAILED";
    public static final String FEDERATED_STRATEGY_FEDML = "FEDML";
    public static final String FEDERATED_NOT_COMPLETED = "FEDERATED_NOT_COMPLETED";
    public static final String FEDERATED_MODEL_NOT_GENERATED = "FEDERATED_MODEL_NOT_GENERATED";
    public static final String FEDERATED_MODEL_DELETED = "FEDERATED_MODEL_DELETED";
    public static final String FEDERATED_MODEL_SOURCE_INVALID = "FEDERATED_MODEL_SOURCE_INVALID";
    public static final String FEDERATED_MODEL_PATH_INVALID = "FEDERATED_MODEL_PATH_INVALID";
    public static final String FEDERATED_MODEL_FILE_MISSING = "FEDERATED_MODEL_FILE_MISSING";
    public static final String FEDERATED_MODEL_FILE_UNREADABLE = "FEDERATED_MODEL_FILE_UNREADABLE";
    private static final int FEDERATED_RUNNING_PROGRESS = 55;
    private static final int FEDERATED_READY_PROGRESS = 60;

    private final WorkflowMapper workflowMapper;
    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final ModelAssetService modelAssetService;
    private final WorkflowStepService workflowStepService;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final PythonFederatedClient pythonFederatedClient;
    private final FederatedArtifactCleanupService federatedArtifactCleanupService;

    @Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
    public boolean triggerFederatedAggregationIfReady(Long workflowId, Long currentServerUserId, String triggerSource) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || isDeleted(workflow.getIsDeleted())) {
            log.warn("Skip federated aggregation because workflow is missing: workflowId={}, triggerSource={}", workflowId, triggerSource);
            return false;
        }
        if (currentServerUserId != null && !currentServerUserId.equals(workflow.getServerUserId())) {
            throw new BusinessException("FORBIDDEN", "Only the assigned server can aggregate workflow models.");
        }
        if (!"ACCEPTED".equals(workflow.getStatus())) {
            log.info(
                    "Skip federated aggregation because workflow status is not ACCEPTED: workflowId={}, status={}, triggerSource={}",
                    workflowId,
                    workflow.getStatus(),
                    triggerSource
            );
            return false;
        }
        if (FEDERATED_STATUS_RUNNING.equalsIgnoreCase(workflow.getFederatedStatus())
                || FEDERATED_STATUS_COMPLETED.equalsIgnoreCase(workflow.getFederatedStatus())
                || FEDERATED_STATUS_FAILED.equalsIgnoreCase(workflow.getFederatedStatus())) {
            log.info(
                    "Skip federated aggregation because workflow already has terminal/running federated status: workflowId={}, federatedStatus={}, triggerSource={}",
                    workflowId,
                    workflow.getFederatedStatus(),
                    triggerSource
            );
            return false;
        }

        List<WorkflowModelUpload> readyUploads = listReadyUploads(workflow.getId());
        int requiredModelCount = requiredModelCount(workflow);
        if (readyUploads.size() < requiredModelCount) {
            log.info(
                    "Skip federated aggregation because ready upload count is insufficient: workflowId={}, readyUploadCount={}, requiredModelCount={}, triggerSource={}",
                    workflowId,
                    readyUploads.size(),
                    requiredModelCount,
                    triggerSource
            );
            return false;
        }

        List<String> modelPaths = new ArrayList<>();
        List<Long> readyUploadIds = new ArrayList<>();
        for (WorkflowModelUpload upload : readyUploads) {
            Path modelPath = resolveServerManagedModelPath(workflow, upload);
            if (modelPath == null) {
                continue;
            }
            modelPaths.add(modelPath.toString());
            readyUploadIds.add(upload.getId());
            if (modelPaths.size() >= requiredModelCount) {
                break;
            }
        }

        if (modelPaths.size() < requiredModelCount) {
            log.warn(
                    "Skip federated aggregation because usable server model path count is insufficient: workflowId={}, usableModelCount={}, requiredModelCount={}, triggerSource={}",
                    workflowId,
                    modelPaths.size(),
                    requiredModelCount,
                    triggerSource
            );
            return false;
        }

        workflow.setFederatedStatus(FEDERATED_STATUS_RUNNING);
        workflow.setFederatedStrategy(FEDERATED_STRATEGY_FEDML);
        workflow.setFederatedStartedAt(LocalDateTime.now());
        workflow.setFederatedFinishedAt(null);
        workflow.setErrorMessage(null);
        workflow.setProgress(Math.max(defaultProgress(workflow.getProgress()), FEDERATED_RUNNING_PROGRESS));
        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                WorkflowCurrentStepSupport.FEDERATED_AGGREGATING,
                "federated-start-" + triggerSource
        );
        workflowMapper.updateById(workflow);
        markAggregationStatus(readyUploadIds, FEDERATED_STATUS_RUNNING, null);

        workflowStepService.appendWorkflowStep(
                workflow.getId(),
                "FEDERATED_AGGREGATE_START",
                WorkflowStepTextCatalog.federatedAggregateStepName(),
                workflow.getStatus(),
                workflow.getStatus(),
                workflow.getServerUserId(),
                "SERVER",
                WorkflowStepTextCatalog.federatedAggregateStartMessage(modelPaths.size(), FEDERATED_STRATEGY_FEDML)
        );

        if (isEnabled(workflow.getShuffleEnabled())) {
            String batchNo = "SHF-" + workflow.getWorkflowCode() + "-" + System.currentTimeMillis();
            List<Long> originalUploadIds = new ArrayList<>(readyUploadIds);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < modelPaths.size(); i++) {
                order.add(i);
            }
            Collections.shuffle(order, new SecureRandom());
            List<String> shuffledModelPaths = new ArrayList<>();
            List<Long> shuffledUploadIds = new ArrayList<>();
            for (Integer index : order) {
                shuffledModelPaths.add(modelPaths.get(index));
                shuffledUploadIds.add(readyUploadIds.get(index));
            }
            modelPaths = shuffledModelPaths;
            readyUploadIds = shuffledUploadIds;
            String orderSummary = "originalUploadIds=" + originalUploadIds + ", shuffledUploadIds=" + shuffledUploadIds;
            workflow.setShuffleBatchNo(batchNo);
            workflow.setShuffleStatus("COMPLETED");
            workflow.setShuffleStartedAt(LocalDateTime.now());
            workflow.setShuffleFinishedAt(LocalDateTime.now());
            workflow.setShuffleOrderSummary(orderSummary);
            WorkflowCurrentStepSupport.applyCurrentStep(
                    workflow,
                    WorkflowCurrentStepSupport.SECURE_SHUFFLE_PROCESSING,
                    "secure-shuffle-" + triggerSource
            );
            workflowMapper.updateById(workflow);
            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "SECURE_SHUFFLE_PROCESS",
                    WorkflowStepTextCatalog.secureShuffleStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    workflow.getServerUserId(),
                    "SERVER",
                    WorkflowStepTextCatalog.secureShuffleMessage(batchNo, orderSummary)
            );
            log.info(
                    "已进入安全洗牌处理阶段: workflowId={}, batchNo={}, originalUploadIds={}, shuffledUploadIds={}",
                    workflow.getId(),
                    batchNo,
                    originalUploadIds,
                    shuffledUploadIds
            );
        }

        if (isEnabled(workflow.getDpEnabled())) {
            workflow.setDpStatus("RUNNING");
            workflow.setDpSummary(buildDpSummary(workflow, "差分隐私处理中"));
            WorkflowCurrentStepSupport.applyCurrentStep(
                    workflow,
                    WorkflowCurrentStepSupport.DP_PROCESSING,
                    "dp-processing-" + triggerSource
            );
            workflowMapper.updateById(workflow);
            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "DIFFERENTIAL_PRIVACY_PROCESS",
                    WorkflowStepTextCatalog.differentialPrivacyStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    workflow.getServerUserId(),
                    "SERVER",
                    WorkflowStepTextCatalog.differentialPrivacyMessage(
                            workflow.getDpEpsilon(),
                            workflow.getDpDelta(),
                            workflow.getDpClipNorm(),
                            workflow.getDpNoiseMultiplier()
                    )
            );
            log.info(
                    "差分隐私处理阶段已启用: workflowId={}, epsilon={}, delta={}, clipNorm={}, noiseMultiplier={}",
                    workflow.getId(),
                    workflow.getDpEpsilon(),
                    workflow.getDpDelta(),
                    workflow.getDpClipNorm(),
                    workflow.getDpNoiseMultiplier()
            );
        }

        if (isEnabled(workflow.getSecureAggregationEnabled())) {
            workflow.setSecureAggregationMode("SECURE");
            workflow.setSecureAggregationStatus("RUNNING");
            workflow.setSecureAggregationStartedAt(LocalDateTime.now());
            workflow.setSecureAggregationSummary("secure_aggregation_prepare -> secure_aggregation_execute，participantCount=" + modelPaths.size());
            WorkflowCurrentStepSupport.applyCurrentStep(
                    workflow,
                    WorkflowCurrentStepSupport.SECURE_AGGREGATION_PROCESSING,
                    "secure-aggregation-" + triggerSource
            );
            workflowMapper.updateById(workflow);
            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "SECURE_AGGREGATION_PROCESS",
                    WorkflowStepTextCatalog.secureAggregationStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    workflow.getServerUserId(),
                    "SERVER",
                    WorkflowStepTextCatalog.secureAggregationMessage(workflow.getSecureAggregationMode(), modelPaths.size())
            );
            log.info(
                    "安全聚合协议处理阶段已启用: workflowId={}, mode={}, participantCount={}",
                    workflow.getId(),
                    workflow.getSecureAggregationMode(),
                    modelPaths.size()
            );
        }

        Path outputModelPath = buildOutputModelPath(workflow);
        try {
            Files.createDirectories(outputModelPath.getParent());
            PythonFederatedAggregateRequest request = new PythonFederatedAggregateRequest();
            request.setWorkflowId(workflow.getId());
            request.setStrategy(FEDERATED_STRATEGY_FEDML);
            request.setModelPaths(modelPaths);
            request.setOutputModelPath(outputModelPath.toString());
            request.setDpEnabled(isEnabled(workflow.getDpEnabled()));
            request.setDpEpsilon(workflow.getDpEpsilon());
            request.setDpDelta(workflow.getDpDelta());
            request.setDpClipNorm(workflow.getDpClipNorm());
            request.setDpNoiseMultiplier(workflow.getDpNoiseMultiplier());
            request.setShuffleEnabled(isEnabled(workflow.getShuffleEnabled()));
            request.setShuffleBatchNo(workflow.getShuffleBatchNo());
            request.setSecureAggregationEnabled(isEnabled(workflow.getSecureAggregationEnabled()));
            request.setSecureAggregationMode(workflow.getSecureAggregationMode());

            log.info(
                    "Triggering Python federated aggregate: workflowId={}, strategy={}, modelPaths={}, outputModelPath={}, triggerSource={}",
                    workflow.getId(),
                    request.getStrategy(),
                    request.getModelPaths(),
                    request.getOutputModelPath(),
                    triggerSource
            );

            PythonFederatedAggregateResponse response = pythonFederatedClient.aggregate(request);
            String globalModelPath = response.getData().getGlobalModelPath();
            Path actualOutputModelPath = Path.of(globalModelPath).toAbsolutePath().normalize();
            if (!Files.exists(actualOutputModelPath) || !Files.isRegularFile(actualOutputModelPath)) {
                throw new BusinessException("FEDERATED_OUTPUT_MISSING", "联邦学习聚合失败：全局模型文件不存在");
            }

            Long federatedModelAssetId = modelAssetService.registerFederatedServerModel(
                    workflow,
                    actualOutputModelPath,
                    modelPaths.size()
            );

            workflow.setFederatedStatus(FEDERATED_STATUS_COMPLETED);
            workflow.setFederatedModelAssetId(federatedModelAssetId);
            workflow.setFederatedFinishedAt(LocalDateTime.now());
            workflow.setErrorMessage(null);
            workflow.setProgress(Math.max(defaultProgress(workflow.getProgress()), FEDERATED_READY_PROGRESS));
            if (isEnabled(workflow.getDpEnabled())) {
                String dpSummary = StringUtils.hasText(response.getData().getDpSummary())
                        ? response.getData().getDpSummary()
                        : buildDpSummary(workflow, "差分隐私处理完成");
                workflow.setDpStatus("COMPLETED");
                workflow.setDpSummary(dpSummary);
            }
            if (isEnabled(workflow.getSecureAggregationEnabled())) {
                String secureSummary = StringUtils.hasText(response.getData().getSecureAggregationSummary())
                        ? response.getData().getSecureAggregationSummary()
                        : "secure_aggregation_finalize，participantCount=" + modelPaths.size();
                workflow.setSecureAggregationStatus("COMPLETED");
                workflow.setSecureAggregationFinishedAt(LocalDateTime.now());
                workflow.setSecureAggregationSummary(secureSummary);
            }
            if (isEnabled(workflow.getShuffleEnabled()) && StringUtils.hasText(response.getData().getShuffleSummary())) {
                workflow.setShuffleOrderSummary(response.getData().getShuffleSummary());
            }
            WorkflowCurrentStepSupport.applyCurrentStep(
                    workflow,
                    WorkflowCurrentStepSupport.FEDERATED_COMPLETED,
                    "federated-complete-" + triggerSource
            );
            workflowMapper.updateById(workflow);
            markAggregationStatus(readyUploadIds, FEDERATED_STATUS_COMPLETED, null);

            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "FEDERATED_AGGREGATE_COMPLETE",
                    WorkflowStepTextCatalog.federatedAggregateStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    workflow.getServerUserId(),
                    "SERVER",
                    WorkflowStepTextCatalog.federatedAggregateCompleteMessage(federatedModelAssetId, actualOutputModelPath)
            );

            log.info(
                    "Federated aggregation completed: workflowId={}, sourceModelCount={}, federatedModelAssetId={}, globalModelPath={}, triggerSource={}, dpStatus={}, shuffleStatus={}, secureAggregationStatus={}",
                    workflow.getId(),
                    modelPaths.size(),
                    federatedModelAssetId,
                    actualOutputModelPath,
                    triggerSource,
                    workflow.getDpStatus(),
                    workflow.getShuffleStatus(),
                    workflow.getSecureAggregationStatus()
            );
            scheduleIntermediateCleanupAfterCommit(workflow.getId());
            return true;
        } catch (Exception ex) {
            String summary = WorkflowErrorSummarySupport.summarizeFederatedAggregation(ex);
            workflow.setFederatedStatus(FEDERATED_STATUS_FAILED);
            workflow.setFederatedFinishedAt(LocalDateTime.now());
            workflow.setErrorMessage(summary);
            workflow.setProgress(Math.max(defaultProgress(workflow.getProgress()), FEDERATED_RUNNING_PROGRESS));
            if (isEnabled(workflow.getDpEnabled())) {
                workflow.setDpStatus("FAILED");
                workflow.setDpSummary("差分隐私处理链路随联邦聚合失败终止：" + summary);
            }
            if (isEnabled(workflow.getShuffleEnabled()) && !"COMPLETED".equalsIgnoreCase(workflow.getShuffleStatus())) {
                workflow.setShuffleStatus("FAILED");
                workflow.setShuffleFinishedAt(LocalDateTime.now());
                workflow.setShuffleOrderSummary("安全洗牌处理链路随联邦聚合失败终止：" + summary);
            }
            if (isEnabled(workflow.getSecureAggregationEnabled())) {
                workflow.setSecureAggregationStatus("FAILED");
                workflow.setSecureAggregationFinishedAt(LocalDateTime.now());
                workflow.setSecureAggregationSummary("安全聚合协议处理链路失败：" + summary);
            }
            WorkflowCurrentStepSupport.applyCurrentStep(
                    workflow,
                    WorkflowCurrentStepSupport.FEDERATED_FAILED,
                    "federated-failed-" + triggerSource
            );
            workflowMapper.updateById(workflow);
            markAggregationStatus(readyUploadIds, FEDERATED_STATUS_FAILED, summary);

            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "FEDERATED_AGGREGATE_FAILED",
                    WorkflowStepTextCatalog.federatedAggregateStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    workflow.getServerUserId(),
                    "SERVER",
                    WorkflowStepTextCatalog.federatedAggregateFailedMessage(summary)
            );

            log.error(
                    "Federated aggregation failed: workflowId={}, triggerSource={}, outputModelPath={}, summary={}",
                    workflow.getId(),
                    triggerSource,
                    outputModelPath,
                    summary,
                    ex
            );
            throw new BusinessException("FEDERATED_AGGREGATE_FAILED", summary, ex);
        }
    }

    public boolean isFederatedModelReady(Workflow workflow) {
        return resolveFederatedModelAvailability(workflow).available();
    }

    void scheduleIntermediateCleanupAfterCommit(Long workflowId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn(
                    "Federated intermediate cleanup was not scheduled because no active transaction synchronization exists: workflowId={}",
                    workflowId
            );
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    federatedArtifactCleanupService.cleanupCompletedWorkflowArtifacts(workflowId);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Federated aggregation is completed but intermediate cleanup failed; startup compensation will retry: workflowId={}, reason={}",
                            workflowId,
                            ex.getMessage()
                    );
                }
            }
        });
    }

    public Path resolveFederatedModelPath(Workflow workflow) {
        FederatedModelAvailability availability = resolveFederatedModelAvailability(workflow);
        return availability.available() ? availability.modelPath() : null;
    }

    public FederatedModelAvailability resolveFederatedModelAvailability(Workflow workflow) {
        if (workflow == null
                || !FEDERATED_STATUS_COMPLETED.equalsIgnoreCase(workflow.getFederatedStatus())) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_NOT_COMPLETED,
                    "联邦聚合尚未完成，暂时无法启动工作流验证。"
            );
        }
        if (workflow.getFederatedModelAssetId() == null) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_NOT_GENERATED,
                    "尚未生成联邦全局模型，无法启动工作流验证。"
            );
        }

        ModelAsset asset = modelAssetMapper.selectById(workflow.getFederatedModelAssetId());
        if (asset == null) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_NOT_GENERATED,
                    "尚未生成联邦全局模型，无法启动工作流验证。"
            );
        }
        if (isDeleted(asset.getIsDeleted())) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_DELETED,
                    "联邦全局模型已删除，无法再次启动工作流验证。"
            );
        }
        boolean legacyFederatedOutput = AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT.equalsIgnoreCase(asset.getSourceType());
        boolean weightsFederatedOutput = workflow.getModelDefinitionId() != null
                && WorkflowWeightsFederatedAggregationService.STRATEGY.equals(workflow.getFederatedStrategy())
                && AssetSourceCatalog.SOURCE_FEDERATED_WEIGHTS_V1.equalsIgnoreCase(asset.getSourceType())
                && AssetSourceCatalog.IMPORT_MODE_WEIGHTS_PROTOCOL_V1.equalsIgnoreCase(asset.getImportMode())
                && AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(asset.getRecordMode())
                && "READY".equalsIgnoreCase(asset.getStatus())
                && "OK".equalsIgnoreCase(asset.getLastCheckStatus());
        if (!Objects.equals(asset.getIsDeleted(), 0)
                || (!legacyFederatedOutput && !weightsFederatedOutput)) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_SOURCE_INVALID,
                    "工作流关联的模型不是联邦聚合全局模型，无法启动工作流验证。"
            );
        }
        if (!StringUtils.hasText(asset.getFilePath())) {
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_PATH_INVALID,
                    "联邦全局模型路径无效，请检查模型资产状态。"
            );
        }

        try {
            Path federatedRoot = workflowStorageProperties.federatedModelRootDirPath();
            Path modelPath = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (modelPath.equals(federatedRoot) || !modelPath.startsWith(federatedRoot)) {
                return FederatedModelAvailability.unavailable(
                        FEDERATED_MODEL_PATH_INVALID,
                        "联邦全局模型路径不在系统受控目录内，请检查模型资产状态。"
                );
            }
            if (!Files.exists(modelPath, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(modelPath, LinkOption.NOFOLLOW_LINKS)) {
                return FederatedModelAvailability.unavailable(
                        FEDERATED_MODEL_FILE_MISSING,
                        "联邦全局模型文件不存在，请检查模型资产状态。"
                );
            }
            if (!Files.isReadable(modelPath)) {
                return FederatedModelAvailability.unavailable(
                        FEDERATED_MODEL_FILE_UNREADABLE,
                        "联邦全局模型文件不可读取，请检查文件权限。"
                );
            }
            return FederatedModelAvailability.available(modelPath);
        } catch (InvalidPathException ex) {
            log.warn(
                    "Federated model path is invalid: workflowId={}, federatedModelAssetId={}, filePath={}",
                    workflow.getId(),
                    workflow.getFederatedModelAssetId(),
                    asset.getFilePath()
            );
            return FederatedModelAvailability.unavailable(
                    FEDERATED_MODEL_PATH_INVALID,
                    "联邦全局模型路径无效，请检查模型资产状态。"
            );
        }
    }

    public record FederatedModelAvailability(boolean available, String code, String reason, Path modelPath) {

        public static FederatedModelAvailability available(Path modelPath) {
            return new FederatedModelAvailability(true, null, null, modelPath);
        }

        public static FederatedModelAvailability unavailable(String code, String reason) {
            return new FederatedModelAvailability(false, code, reason, null);
        }
    }

    private void markAggregationStatus(List<Long> uploadIds, String aggregationStatus, String errorSummary) {
        if (uploadIds == null || uploadIds.isEmpty()) {
            return;
        }
        String uploadError = WorkflowErrorSummarySupport.summarizeForUpload(errorSummary, "联邦学习聚合失败，请查看服务端日志");
        workflowModelUploadMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowModelUpload>()
                        .in(WorkflowModelUpload::getId, uploadIds)
                        .set(WorkflowModelUpload::getAggregationStatus, aggregationStatus)
                        .set(WorkflowModelUpload::getErrorMessage,
                                FEDERATED_STATUS_FAILED.equalsIgnoreCase(aggregationStatus) ? uploadError : null)
        );
    }

    private List<WorkflowModelUpload> listReadyUploads(Long workflowId) {
        return workflowModelUploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .eq(WorkflowModelUpload::getUploadStatus, "COMPLETED")
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByAsc(WorkflowModelUpload::getCreatedAt)
                        .orderByAsc(WorkflowModelUpload::getId)
        );
    }

    private Path resolveServerManagedModelPath(Workflow workflow, WorkflowModelUpload upload) {
        if (upload.getServerModelAssetId() == null) {
            return null;
        }
        ModelAsset asset = modelAssetMapper.selectOne(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getId, upload.getServerModelAssetId())
                        .eq(ModelAsset::getOwnerUserId, workflow.getServerUserId())
                        .eq(ModelAsset::getOwnerRoleCode, "SERVER")
                        .eq(ModelAsset::getStatus, "READY")
                        .eq(ModelAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (asset == null || !StringUtils.hasText(asset.getFilePath()) || asset.getFilePathValidated() == null || asset.getFilePathValidated() != 1) {
            return null;
        }
        Path modelPath = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
        if (!modelPath.startsWith(workflowStorageProperties.modelRootDirPath())) {
            return null;
        }
        return Files.exists(modelPath) && Files.isRegularFile(modelPath) ? modelPath : null;
    }

    private Path buildOutputModelPath(Workflow workflow) {
        return workflowStorageProperties.federatedModelRootDirPath()
                .resolve(resolveWorkflowCode(workflow))
                .resolve("global_model.pt");
    }

    private String resolveWorkflowCode(Workflow workflow) {
        if (workflow == null || !StringUtils.hasText(workflow.getWorkflowCode())) {
            return workflow != null && workflow.getId() != null ? "WF" + workflow.getId() : "workflow";
        }
        return workflow.getWorkflowCode().replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private int requiredModelCount(Workflow workflow) {
        if (workflow == null) {
            return 1;
        }
        Integer expected = workflow.getExpectedModelCount();
        if (expected != null && expected > 0) {
            return expected;
        }
        Integer legacy = workflow.getClientModelCount();
        return legacy == null || legacy <= 0 ? 1 : legacy;
    }

    private boolean isDeleted(Integer value) {
        return value != null && value == 1;
    }

    private boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    private String buildDpSummary(Workflow workflow, String stage) {
        return stage + "，epsilon=" + workflow.getDpEpsilon()
                + "，delta=" + workflow.getDpDelta()
                + "，clipNorm=" + workflow.getDpClipNorm()
                + "，noiseMultiplier=" + workflow.getDpNoiseMultiplier();
    }

    private int defaultProgress(Integer value) {
        return value == null ? 0 : value;
    }
}
