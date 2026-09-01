package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.dto.dashboard.DashboardMetricsVO;
import com.workflow.dto.dashboard.DashboardModelCardVO;
import com.workflow.dto.dashboard.DashboardOverviewVO;
import com.workflow.dto.dashboard.DashboardValidationCardVO;
import com.workflow.dto.dashboard.DashboardWorkflowCardVO;
import com.workflow.dto.validation.ValidationResultViewVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowStep;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.DashboardService;
import com.workflow.service.ValidationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardServiceImpl implements DashboardService {

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String SOURCE_TYPE_WORKFLOW = "WORKFLOW";
    private static final String SOURCE_TYPE_STANDALONE = "STANDALONE";
    private static final String SOURCE_LABEL_WORKFLOW = "\u5DE5\u4F5C\u6D41\u9A8C\u8BC1\u7ED3\u679C";
    private static final String SOURCE_LABEL_STANDALONE = "\u72EC\u7ACB\u9A8C\u8BC1\u7ED3\u679C";
    private static final String WORKFLOW_RESULT_DELETED = "DELETED";

    private static final Set<String> SERVER_MANAGE_PENDING_UPLOAD_STATUSES = Set.of(
            "ENCRYPTED_STORED",
            "DECRYPTING"
    );

    private final WorkflowMapper workflowMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final StandaloneValidationMapper standaloneValidationMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final DatasetAssetMapper datasetAssetMapper;
    private final WorkflowModelUploadSummaryService workflowModelUploadSummaryService;
    private final WorkflowFederatedAggregationService workflowFederatedAggregationService;
    private final ValidationResultService validationResultService;
    private final LoginUserContext loginUserContext;

    @Override
    public DashboardOverviewVO getOverview() {
        SysUser currentUser = loginUserContext.getCurrentUser();
        log.info(
                "Aggregating dashboard overview: userId={}, roleCode={}",
                currentUser.getId(),
                currentUser.getRoleCode()
        );

        List<Workflow> workflows = loadOwnedWorkflows(currentUser);
        Map<Long, WorkflowModelUploadSummaryService.WorkflowUploadSummary> uploadSummaryMap =
                workflowModelUploadSummaryService.summarizeWorkflows(
                        workflows.stream().map(Workflow::getId).collect(Collectors.toList())
                );
        List<StandaloneValidation> validations = loadOwnedStandaloneValidations(currentUser);
        List<ModelAsset> models = loadOwnedModels(currentUser);

        DashboardWorkflowCardVO latestWorkflow = buildLatestWorkflow(workflows, uploadSummaryMap);
        DashboardValidationCardVO recentValidation = buildRecentValidation(currentUser, workflows, validations);
        DashboardModelCardVO modelOverview = buildModelOverview(models);
        DashboardMetricsVO metrics = buildMetrics(
                currentUser.getRoleCode(),
                workflows,
                uploadSummaryMap,
                validations,
                recentValidation
        );

        DashboardOverviewVO overview = new DashboardOverviewVO();
        overview.setRoleCode(currentUser.getRoleCode());
        overview.setMetrics(metrics);
        overview.setLatestWorkflow(latestWorkflow);
        overview.setRecentValidation(recentValidation);
        overview.setModelOverview(modelOverview);

        log.info(
                "Dashboard overview aggregated: userId={}, roleCode={}, workflowCount={}, standaloneValidationCount={}, modelCount={}, latestWorkflowId={}, recentValidationType={}, recentValidationWorkflowId={}, recentValidationId={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                workflows.size(),
                validations.size(),
                models.size(),
                latestWorkflow == null ? null : latestWorkflow.getId(),
                recentValidation == null ? null : recentValidation.getSourceType(),
                recentValidation == null ? null : recentValidation.getWorkflowId(),
                recentValidation == null ? null : recentValidation.getValidationId()
        );
        return overview;
    }

    private List<Workflow> loadOwnedWorkflows(SysUser currentUser) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getIsDeleted, 0)
                .orderByDesc(Workflow::getUpdatedAt)
                .orderByDesc(Workflow::getCreatedAt)
                .orderByDesc(Workflow::getId);

        if ("CLIENT".equalsIgnoreCase(currentUser.getRoleCode())) {
            wrapper.eq(Workflow::getInitiatorUserId, currentUser.getId());
        } else if ("SERVER".equalsIgnoreCase(currentUser.getRoleCode())) {
            wrapper.eq(Workflow::getServerUserId, currentUser.getId());
        } else {
            return Collections.emptyList();
        }

        List<Workflow> workflows = workflowMapper.selectList(wrapper);
        log.info(
                "Dashboard workflow block prepared: source=workflow table, userId={}, roleCode={}, count={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                workflows.size()
        );
        return workflows;
    }

    private List<StandaloneValidation> loadOwnedStandaloneValidations(SysUser currentUser) {
        List<StandaloneValidation> validations = standaloneValidationMapper.selectList(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getUserId, currentUser.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .orderByDesc(StandaloneValidation::getCreatedAt)
                        .orderByDesc(StandaloneValidation::getId)
        );
        log.info(
                "Dashboard standalone validation block prepared: source=standalone_validation table, userId={}, roleCode={}, count={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                validations.size()
        );
        return validations;
    }

    private List<ModelAsset> loadOwnedModels(SysUser currentUser) {
        List<ModelAsset> models = modelAssetMapper.selectList(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getOwnerUserId, currentUser.getId())
                        .eq(ModelAsset::getOwnerRoleCode, currentUser.getRoleCode())
                        .eq(ModelAsset::getIsDeleted, 0)
                        .orderByDesc(ModelAsset::getCreatedAt)
                        .orderByDesc(ModelAsset::getId)
        );
        log.info(
                "Dashboard model block prepared: source=model_asset table, userId={}, roleCode={}, count={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                models.size()
        );
        return models;
    }

    private DashboardWorkflowCardVO buildLatestWorkflow(
            List<Workflow> workflows,
            Map<Long, WorkflowModelUploadSummaryService.WorkflowUploadSummary> uploadSummaryMap
    ) {
        if (workflows.isEmpty()) {
            log.info("Dashboard latest workflow block is empty: no workflow records");
            return null;
        }

        Workflow workflow = workflows.get(0);
        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary = uploadSummaryMap.get(workflow.getId());
        int requiredModelCount = requiredModelCount(workflow);
        int receivedModelCount = summary == null ? 0 : summary.getReceivedModelCount();
        int completedModelCount = summary == null ? 0 : summary.getCompletedModelCount();
        ValidationReadiness validationReadiness = resolveValidationReadiness(workflow, summary);
        WorkflowFederatedAggregationService.FederatedModelAvailability federatedModelAvailability =
                workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow);

        DashboardWorkflowCardVO card = new DashboardWorkflowCardVO();
        card.setId(workflow.getId());
        card.setWorkflowCode(workflow.getWorkflowCode());
        card.setWorkflowName(workflow.getWorkflowName());
        card.setStatus(workflow.getStatus());
        card.setCurrentStep(workflow.getCurrentStep());
        card.setUpdatedAt(firstNonNull(workflow.getUpdatedAt(), workflow.getCreatedAt()));
        card.setReceivedByServer(!"CREATED".equals(workflow.getStatus()));
        card.setDecryptCompleted(completedModelCount >= requiredModelCount);
        card.setValidationReady(validationReadiness.ready());
        card.setValidationReadyReason(validationReadiness.reason());
        card.setFederatedModelAvailable(federatedModelAvailability.available());
        card.setFederatedModelUnavailableReason(
                federatedModelAvailability.available() ? null : federatedModelAvailability.reason()
        );
        card.setRequiredModelCount(requiredModelCount);
        card.setReceivedModelCount(receivedModelCount);
        card.setCollectedModelCount(completedModelCount);
        card.setLatestUploadStatus(summary == null ? null : summary.getLatestUploadStatus());

        if (workflow.getClientModelAssetId() != null) {
            ModelAsset modelAsset = modelAssetMapper.selectById(workflow.getClientModelAssetId());
            if (modelAsset != null && Objects.equals(modelAsset.getIsDeleted(), 0)) {
                card.setClientModelAssetName(modelAsset.getAssetName());
            }
        }

        if (workflow.getServerDatasetAssetId() != null) {
            DatasetAsset datasetAsset = datasetAssetMapper.selectById(workflow.getServerDatasetAssetId());
            if (datasetAsset != null && Objects.equals(datasetAsset.getIsDeleted(), 0)) {
                card.setServerDatasetAssetName(datasetAsset.getAssetName());
            }
        }

        log.info(
                "Dashboard latest workflow selected: workflowId={}, workflowCode={}, status={}, currentStep={}, receivedModelCount={}, completedModelCount={}, latestUploadStatus={}",
                workflow.getId(),
                workflow.getWorkflowCode(),
                workflow.getStatus(),
                workflow.getCurrentStep(),
                receivedModelCount,
                completedModelCount,
                summary == null ? null : summary.getLatestUploadStatus()
        );
        return card;
    }

    private DashboardValidationCardVO buildRecentValidation(
            SysUser currentUser,
            List<Workflow> workflows,
            List<StandaloneValidation> validations
    ) {
        Optional<ResolvedValidationCandidate> candidate = selectLatestEffectiveValidation(currentUser, workflows, validations);
        if (candidate.isEmpty()) {
            log.info(
                    "Dashboard recent validation block is empty: no effective completed validation with accuracy, userId={}, roleCode={}",
                    currentUser.getId(),
                    currentUser.getRoleCode()
            );
            return null;
        }

        ResolvedValidationCandidate resolved = candidate.get();
        ValidationCandidate selected = resolved.candidate;
        ValidationResultViewVO resultView = resolved.resultView;

        DashboardValidationCardVO card = new DashboardValidationCardVO();
        card.setSourceType(selected.type);
        card.setWorkflowId(selected.workflowId);
        card.setValidationId(selected.validationId);
        card.setTaskName(selected.taskName);
        card.setModelName(resultView.getModelName());
        card.setDatasetName(resultView.getDatasetName());
        card.setStatus(resultView.getStatus());
        card.setAccuracy(resultView.getAccuracy());
        card.setPrecision(resultView.getPrecision());
        card.setRecall(resultView.getRecall());
        card.setUpdatedAt(selected.sortTime);
        card.setSourceLabel(selected.sourceLabel);

        log.info(
                "Dashboard recent effective validation selected: userId={}, roleCode={}, sourceType={}, workflowId={}, validationId={}, taskName={}, status={}, accuracy={}, sortTime={}, sourceLabel={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                selected.type,
                selected.workflowId,
                selected.validationId,
                selected.taskName,
                resultView.getStatus(),
                resultView.getAccuracy(),
                selected.sortTime,
                selected.sourceLabel
        );
        return card;
    }

    private DashboardModelCardVO buildModelOverview(List<ModelAsset> models) {
        DashboardModelCardVO card = new DashboardModelCardVO();
        card.setTotalModelCount(models.size());

        if (models.isEmpty()) {
            log.info("Dashboard model overview block is empty: no local models");
            return card;
        }

        ModelAsset latestModel = models.get(0);
        card.setLatestModelId(latestModel.getId());
        card.setLatestModelName(latestModel.getAssetName());
        card.setLatestModelVersion(
                StringUtils.hasText(latestModel.getYoloVersion())
                        ? latestModel.getYoloVersion()
                        : latestModel.getModelVersion()
        );
        card.setLatestModelStatus(latestModel.getStatus());
        card.setLatestModelCreatedAt(latestModel.getCreatedAt());

        log.info(
                "Dashboard model overview selected latest model: modelId={}, modelName={}, createdAt={}, totalModelCount={}",
                latestModel.getId(),
                latestModel.getAssetName(),
                latestModel.getCreatedAt(),
                models.size()
        );
        return card;
    }

    private DashboardMetricsVO buildMetrics(
            String roleCode,
            List<Workflow> workflows,
            Map<Long, WorkflowModelUploadSummaryService.WorkflowUploadSummary> uploadSummaryMap,
            List<StandaloneValidation> validations,
            DashboardValidationCardVO recentValidation
    ) {
        DashboardMetricsVO metrics = new DashboardMetricsVO();
        metrics.setTotalWorkflows(workflows.size());
        metrics.setPendingServerReceiveCount(
                workflows.stream().filter(item -> "CREATED".equals(item.getStatus())).count()
        );
        metrics.setPendingReceiveWorkflowCount(
                workflows.stream().filter(item -> "CREATED".equals(item.getStatus())).count()
        );
        metrics.setPendingDecryptCount(
                workflows.stream().filter(item -> isPendingDecryptOrManage(item, uploadSummaryMap.get(item.getId()))).count()
        );
        metrics.setPendingValidationCount(
                workflows.stream().filter(item -> resolveValidationReadiness(item, uploadSummaryMap.get(item.getId())).ready()).count()
        );
        metrics.setCompletedValidationCount(
                workflows.stream().filter(item -> STATUS_COMPLETED.equals(item.getStatus())).count()
                        + validations.stream().filter(item -> STATUS_COMPLETED.equals(item.getStatus())).count()
        );

        if (recentValidation != null) {
            metrics.setLatestAccuracy(recentValidation.getAccuracy());
            metrics.setLatestAccuracySourceType(recentValidation.getSourceType());
            metrics.setLatestAccuracySourceLabel(recentValidation.getSourceLabel());
        }

        if ("CLIENT".equalsIgnoreCase(roleCode)) {
            log.info(
                    "Dashboard client metrics computed: totalWorkflows={}, pendingServerReceiveCount={}, completedValidationCount={}, latestAccuracy={}, accuracySource={}",
                    metrics.getTotalWorkflows(),
                    metrics.getPendingServerReceiveCount(),
                    metrics.getCompletedValidationCount(),
                    metrics.getLatestAccuracy(),
                    metrics.getLatestAccuracySourceLabel()
            );
        } else {
            log.info(
                    "Dashboard server metrics computed: pendingReceiveWorkflowCount={}, pendingDecryptCount={}, pendingValidationCount={}, completedValidationCount={}, latestAccuracy={}, accuracySource={}",
                    metrics.getPendingReceiveWorkflowCount(),
                    metrics.getPendingDecryptCount(),
                    metrics.getPendingValidationCount(),
                    metrics.getCompletedValidationCount(),
                    metrics.getLatestAccuracy(),
                    metrics.getLatestAccuracySourceLabel()
            );
        }
        return metrics;
    }

    private Optional<ResolvedValidationCandidate> selectLatestEffectiveValidation(
            SysUser currentUser,
            List<Workflow> workflows,
            List<StandaloneValidation> validations
    ) {
        List<ValidationCandidate> workflowCandidates = workflows.stream()
                .filter(this::isEffectiveWorkflowValidationCandidate)
                .map(this::toWorkflowValidationCandidate)
                .collect(Collectors.toList());
        List<ValidationCandidate> standaloneCandidates = validations.stream()
                .filter(this::isEffectiveStandaloneValidationCandidate)
                .map(this::toStandaloneValidationCandidate)
                .collect(Collectors.toList());

        List<ValidationCandidate> allCandidates = Arrays.asList(workflowCandidates, standaloneCandidates).stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        Comparator<ValidationCandidate> comparator = Comparator
                .comparing((ValidationCandidate item) -> item.sortTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.taskName == null ? "" : item.taskName);

        List<ValidationCandidate> orderedCandidates = allCandidates.stream()
                .sorted(comparator)
                .collect(Collectors.toList());

        for (ValidationCandidate candidate : orderedCandidates) {
            try {
                ValidationResultViewVO resultView = SOURCE_TYPE_WORKFLOW.equals(candidate.type)
                        ? validationResultService.getWorkflowResultView(candidate.workflowId)
                        : validationResultService.getStandaloneResultView(candidate.validationId);
                if (isEffectiveResultView(resultView)) {
                    log.info(
                            "Dashboard effective validation candidate accepted: userId={}, roleCode={}, sourceType={}, workflowId={}, validationId={}, accuracy={}, sortTime={}",
                            currentUser.getId(),
                            currentUser.getRoleCode(),
                            candidate.type,
                            candidate.workflowId,
                            candidate.validationId,
                            resultView.getAccuracy(),
                            candidate.sortTime
                    );
                    return Optional.of(new ResolvedValidationCandidate(candidate, resultView));
                }
                log.info(
                        "Dashboard validation candidate skipped: userId={}, roleCode={}, sourceType={}, workflowId={}, validationId={}, status={}, accuracy={}, reason=not_effective",
                        currentUser.getId(),
                        currentUser.getRoleCode(),
                        candidate.type,
                        candidate.workflowId,
                        candidate.validationId,
                        resultView == null ? null : resultView.getStatus(),
                        resultView == null ? null : resultView.getAccuracy()
                );
            } catch (RuntimeException ex) {
                log.warn(
                        "Dashboard validation candidate skipped: userId={}, roleCode={}, sourceType={}, workflowId={}, validationId={}, reason=result_view_load_failed, message={}",
                        currentUser.getId(),
                        currentUser.getRoleCode(),
                        candidate.type,
                        candidate.workflowId,
                        candidate.validationId,
                        ex.getMessage()
                );
            }
        }
        return Optional.empty();
    }

    private boolean isEffectiveWorkflowValidationCandidate(Workflow workflow) {
        if (workflow == null) {
            return false;
        }
        return STATUS_COMPLETED.equals(workflow.getStatus())
                && StringUtils.hasText(workflow.getPythonJobId())
                && !WORKFLOW_RESULT_DELETED.equalsIgnoreCase(defaultString(workflow.getResultRetentionStatus()));
    }

    private boolean isEffectiveStandaloneValidationCandidate(StandaloneValidation validation) {
        if (validation == null) {
            return false;
        }
        return STATUS_COMPLETED.equals(validation.getStatus());
    }

    private boolean isEffectiveResultView(ValidationResultViewVO resultView) {
        return resultView != null
                && STATUS_COMPLETED.equals(resultView.getStatus())
                && resultView.getAccuracy() != null;
    }

    private ValidationCandidate toWorkflowValidationCandidate(Workflow workflow) {
        return ValidationCandidate.builder()
                .type(SOURCE_TYPE_WORKFLOW)
                .workflowId(workflow.getId())
                .validationId(null)
                .taskName(workflow.getWorkflowName())
                .status(workflow.getStatus())
                .sourceLabel(SOURCE_LABEL_WORKFLOW)
                .sortTime(resolveWorkflowCompletedAt(workflow))
                .build();
    }

    private ValidationCandidate toStandaloneValidationCandidate(StandaloneValidation validation) {
        return ValidationCandidate.builder()
                .type(SOURCE_TYPE_STANDALONE)
                .workflowId(null)
                .validationId(validation.getId())
                .taskName(validation.getValidationCode())
                .status(validation.getStatus())
                .sourceLabel(SOURCE_LABEL_STANDALONE)
                .sortTime(firstNonNull(validation.getFinishedAt(), validation.getUpdatedAt(), validation.getCreatedAt()))
                .build();
    }

    private LocalDateTime resolveWorkflowCompletedAt(Workflow workflow) {
        WorkflowStep completedStep = workflowStepMapper.selectOne(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getWorkflowId, workflow.getId())
                        .eq(WorkflowStep::getStepCode, "PY_CALLBACK_COMPLETED")
                        .orderByDesc(WorkflowStep::getCreatedAt)
                        .last("limit 1")
        );
        if (completedStep != null && completedStep.getCreatedAt() != null) {
            return completedStep.getCreatedAt();
        }
        return firstNonNull(workflow.getUpdatedAt(), workflow.getCreatedAt());
    }

    private boolean isPendingDecryptOrManage(
            Workflow workflow,
            WorkflowModelUploadSummaryService.WorkflowUploadSummary summary
    ) {
        if (workflow == null) {
            return false;
        }
        if (!Arrays.asList("ACCEPTED", "PREPARING").contains(workflow.getStatus())) {
            return false;
        }
        if (summary == null) {
            return false;
        }
        int requiredModelCount = requiredModelCount(workflow);
        if (summary.getCompletedModelCount() >= requiredModelCount) {
            return false;
        }
        return summary.getReceivedModelCount() > summary.getCompletedModelCount()
                || SERVER_MANAGE_PENDING_UPLOAD_STATUSES.contains(summary.getLatestUploadStatus());
    }

    private ValidationReadiness resolveValidationReadiness(
            Workflow workflow,
            WorkflowModelUploadSummaryService.WorkflowUploadSummary summary
    ) {
        if (workflow == null) {
            return ValidationReadiness.notReady("暂无工作流信息");
        }
        if (!Arrays.asList("ACCEPTED", "PREPARING").contains(workflow.getStatus())) {
            return ValidationReadiness.notReady("仅已接收且未进入验证的工作流可以启动验证");
        }
        if (StringUtils.hasText(workflow.getPythonJobId())) {
            return ValidationReadiness.notReady("Python 验证任务已启动");
        }
        if (workflow.getServerDatasetAssetId() == null) {
            return ValidationReadiness.notReady("请先绑定服务端数据集");
        }
        if (!isValidServerDataset(workflow.getServerDatasetAssetId())) {
            return ValidationReadiness.notReady("服务端数据集未通过校验或没有图片");
        }
        WorkflowFederatedAggregationService.FederatedModelAvailability federatedModelAvailability =
                workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow);
        if (!federatedModelAvailability.available()) {
            return ValidationReadiness.notReady(federatedModelAvailability.reason());
        }
        return ValidationReadiness.readyState();
    }

    private boolean isValidServerDataset(Long datasetId) {
        if (datasetId == null) {
            return false;
        }
        DatasetAsset datasetAsset = datasetAssetMapper.selectById(datasetId);
        return datasetAsset != null
                && Objects.equals(datasetAsset.getIsDeleted(), 0)
                && Objects.equals(datasetAsset.getFilePathValidated(), 1)
                && datasetAsset.getImageCount() != null
                && datasetAsset.getImageCount() > 0
                && StringUtils.hasText(datasetAsset.getFilePath());
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

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @lombok.Builder
    private static class ValidationCandidate {
        private String type;
        private Long workflowId;
        private Long validationId;
        private String taskName;
        private String status;
        private String sourceLabel;
        private LocalDateTime sortTime;
    }

    private static class ResolvedValidationCandidate {
        private final ValidationCandidate candidate;
        private final ValidationResultViewVO resultView;

        private ResolvedValidationCandidate(ValidationCandidate candidate, ValidationResultViewVO resultView) {
            this.candidate = candidate;
            this.resultView = resultView;
        }
    }

    private record ValidationReadiness(boolean ready, String reason) {
        private static ValidationReadiness readyState() {
            return new ValidationReadiness(true, "已满足启动验证条件");
        }

        private static ValidationReadiness notReady(String reason) {
            return new ValidationReadiness(false, reason);
        }
    }
}
