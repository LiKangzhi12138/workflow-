package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonCallbackRequest;
import com.workflow.dto.python.PythonCallbackResultFile;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.dto.python.PythonJobTypes;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.dto.workflow.WorkflowDetailVO;
import com.workflow.dto.workflow.WorkflowListItemVO;
import com.workflow.dto.workflow.WorkflowStepVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.entity.WorkflowStep;
import com.workflow.enums.WorkflowModelUploadStatusEnum;
import com.workflow.enums.WorkflowStatusEnum;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.WorkflowModelUploadService;
import com.workflow.service.WorkflowService;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final SysUserMapper sysUserMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final DatasetAssetMapper datasetAssetMapper;
    private final LoginUserContext loginUserContext;
    private final PythonJobClient pythonJobClient;
    private final PythonIntegrationProperties pythonIntegrationProperties;
    private final ObjectMapper objectMapper;
    private final WorkflowModelUploadMapper workflowModelUploadMapper;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final WorkflowModelUploadSummaryService workflowModelUploadSummaryService;
    private final WorkflowAcceptAutoManageAsyncService workflowAcceptAutoManageAsyncService;
    private final WorkflowFederatedAggregationService workflowFederatedAggregationService;
    private final ValidationImageCacheService validationImageCacheService;
    private final ValidationResultService validationResultService;
    private final ResultArtifactCleanupService resultArtifactCleanupService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWorkflow(CreateWorkflowRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertClient(currentUser);

        SysUser serverUser = sysUserMapper.selectById(request.getServerUserId());
        if (serverUser == null || Objects.equals(serverUser.getIsDeleted(), 1)) {
            throw new RuntimeException("目标服务端用户不存在");
        }
        if (!"SERVER".equalsIgnoreCase(serverUser.getRoleCode())) {
            throw new RuntimeException("serverUserId 对应的用户不是 SERVER 角色");
        }

        ModelAsset clientModel = modelAssetMapper.selectOne(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getId, request.getClientModelAssetId())
                        .eq(ModelAsset::getOwnerUserId, currentUser.getId())
                        .eq(ModelAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (clientModel == null) {
            throw new RuntimeException("所选模型不存在或无权访问");
        }

        Integer clientModelCount = request.getClientModelCount();
        if (clientModelCount == null || clientModelCount < 1) {
            throw new RuntimeException("客户端模型数量必须大于等于 1");
        }

        // 校验 yoloVersion 只能是当前支持的 YOLO 主流程版本。
        List<String> allowedVersions = Arrays.asList("YOLOv8", "YOLOv10", "YOLOv11");
        if (!allowedVersions.contains(request.getYoloVersion())) {
            throw new RuntimeException("不支持的 YOLO 版本，请选择 YOLOv8、YOLOv10 或 YOLOv11");
        }

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode(generateWorkflowCode());
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setInitiatorUserId(currentUser.getId());
        workflow.setServerUserId(serverUser.getId());
        workflow.setClientModelAssetId(clientModel.getId());
        workflow.setServerDatasetAssetId(null);
        workflow.setClientModelCount(clientModelCount);
        workflow.setExpectedModelCount(clientModelCount);
        workflow.setYoloVersion(request.getYoloVersion());
        workflow.setCollectedModelCount(0);
        workflow.setFederatedStatus(WorkflowFederatedAggregationService.FEDERATED_STATUS_PENDING);
        workflow.setFederatedStrategy(WorkflowFederatedAggregationService.FEDERATED_STRATEGY_FEDML);
        workflow.setFederatedModelAssetId(null);
        workflow.setFederatedStartedAt(null);
        workflow.setFederatedFinishedAt(null);
        boolean dpEnabled = Boolean.TRUE.equals(request.getDpEnabled());
        boolean shuffleEnabled = Boolean.TRUE.equals(request.getShuffleEnabled());
        boolean secureAggregationEnabled = Boolean.TRUE.equals(request.getSecureAggregationEnabled());
        workflow.setDpEnabled(dpEnabled ? 1 : 0);
        workflow.setDpEpsilon(resolvePositiveDecimal(request.getDpEpsilon(), new BigDecimal("1.000000")));
        workflow.setDpDelta(resolvePositiveDecimal(request.getDpDelta(), new BigDecimal("0.000001")));
        workflow.setDpClipNorm(resolvePositiveDecimal(request.getDpClipNorm(), new BigDecimal("1.000000")));
        workflow.setDpNoiseMultiplier(resolveNonNegativeDecimal(request.getDpNoiseMultiplier(), new BigDecimal("0.000000")));
        workflow.setDpStatus(dpEnabled ? "ENABLED" : "DISABLED");
        workflow.setDpSummary(dpEnabled ? buildDpSummary(workflow) : "差分隐私未启用");
        workflow.setShuffleEnabled(shuffleEnabled ? 1 : 0);
        workflow.setShuffleBatchNo(null);
        workflow.setShuffleStatus(shuffleEnabled ? "WAITING" : "DISABLED");
        workflow.setShuffleOrderSummary(shuffleEnabled ? "等待模型收齐后生成安全洗牌批次" : "安全洗牌未启用");
        workflow.setShuffleStartedAt(null);
        workflow.setShuffleFinishedAt(null);
        workflow.setSecureAggregationEnabled(secureAggregationEnabled ? 1 : 0);
        workflow.setSecureAggregationMode(secureAggregationEnabled ? "SECURE" : "PLAIN");
        workflow.setSecureAggregationStatus(secureAggregationEnabled ? "PREPARING" : "DISABLED");
        workflow.setSecureAggregationSummary(secureAggregationEnabled ? "安全聚合协议原型链路已启用，等待联邦聚合触发" : "普通聚合模式");
        workflow.setSecureAggregationStartedAt(null);
        workflow.setSecureAggregationFinishedAt(null);
        workflow.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        workflow.setStatus(WorkflowStatusEnum.CREATED.getCode());
        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                WorkflowCurrentStepSupport.statusStepOf(WorkflowStatusEnum.CREATED.getCode()),
                "create-workflow"
        );
        workflow.setProgress(WorkflowStatusEnum.progressOf(WorkflowStatusEnum.CREATED.getCode()));
        workflow.setRemark(request.getRemark());
        workflow.setResultRetentionStatus(ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY);
        workflow.setIsDeleted(0);

        workflowMapper.insert(workflow);

        insertStep(
                workflow.getId(),
                1,
                "INITIATE",
                WorkflowStepTextCatalog.initiateStepName(),
                null,
                WorkflowStatusEnum.CREATED.getCode(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                WorkflowStepTextCatalog.initiateMessage(clientModel.getAssetName())
        );

        return workflow.getId();
    }

    @Override
    public Page<WorkflowListItemVO> pageWorkflows(long pageNum, long pageSize, String status) {
        SysUser currentUser = loginUserContext.getCurrentUser();

        Page<Workflow> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getIsDeleted, 0)
                .orderByDesc(Workflow::getCreatedAt);

        if (StringUtils.hasText(status)) {
            wrapper.eq(Workflow::getStatus, status);
        }

        if ("CLIENT".equalsIgnoreCase(currentUser.getRoleCode())) {
            wrapper.and(item -> item
                    .eq(Workflow::getInitiatorUserId, currentUser.getId())
                    .or()
                    .eq(Workflow::getIsPublic, 1));
        } else if ("SERVER".equalsIgnoreCase(currentUser.getRoleCode())) {
            wrapper.eq(Workflow::getServerUserId, currentUser.getId());
        } else {
            throw new RuntimeException("未知角色，无法查询工作流");
        }

        Page<Workflow> entityPage = workflowMapper.selectPage(page, wrapper);

        Map<Long, String> usernameMap = buildUsernameMap(entityPage.getRecords());
        Map<Long, ModelAsset> modelMap = buildModelMap(entityPage.getRecords());
        Map<Long, DatasetAsset> datasetMap = buildDatasetMap(entityPage.getRecords());
        Map<Long, WorkflowModelUploadSummaryService.WorkflowUploadSummary> uploadSummaryMap =
                buildUploadSummaryMap(entityPage.getRecords());

        List<WorkflowListItemVO> voList = entityPage.getRecords().stream().map(item -> {
            WorkflowListItemVO vo = new WorkflowListItemVO();
            vo.setId(item.getId());
            vo.setWorkflowCode(item.getWorkflowCode());
            vo.setWorkflowName(item.getWorkflowName());
            vo.setInitiatorUserId(item.getInitiatorUserId());
            vo.setInitiatorUsername(usernameMap.get(item.getInitiatorUserId()));
            vo.setServerUserId(item.getServerUserId());
            vo.setServerUsername(usernameMap.get(item.getServerUserId()));

            vo.setClientModelAssetId(item.getClientModelAssetId());
            ModelAsset modelAsset = modelMap.get(item.getClientModelAssetId());
            if (modelAsset != null) {
                vo.setClientModelAssetName(modelAsset.getAssetName());
                vo.setClientModelVersion(modelAsset.getModelVersion());
            }
            vo.setClientModelCount(item.getClientModelCount());
            vo.setExpectedModelCount(item.getExpectedModelCount());
            vo.setYoloVersion(item.getYoloVersion());
            applyUploadSummary(vo, item, uploadSummaryMap.get(item.getId()));
            vo.setFederatedStatus(item.getFederatedStatus());
            vo.setFederatedStrategy(item.getFederatedStrategy());
            vo.setFederatedModelAssetId(item.getFederatedModelAssetId());
            vo.setFederatedStartedAt(item.getFederatedStartedAt());
            vo.setFederatedFinishedAt(item.getFederatedFinishedAt());
            vo.setDpEnabled(isEnabled(item.getDpEnabled()));
            vo.setDpEpsilon(item.getDpEpsilon());
            vo.setDpDelta(item.getDpDelta());
            vo.setDpClipNorm(item.getDpClipNorm());
            vo.setDpNoiseMultiplier(item.getDpNoiseMultiplier());
            vo.setDpStatus(item.getDpStatus());
            vo.setDpSummary(item.getDpSummary());
            vo.setShuffleEnabled(isEnabled(item.getShuffleEnabled()));
            vo.setShuffleBatchNo(item.getShuffleBatchNo());
            vo.setShuffleStatus(item.getShuffleStatus());
            vo.setShuffleOrderSummary(item.getShuffleOrderSummary());
            vo.setShuffleStartedAt(item.getShuffleStartedAt());
            vo.setShuffleFinishedAt(item.getShuffleFinishedAt());
            vo.setSecureAggregationEnabled(isEnabled(item.getSecureAggregationEnabled()));
            vo.setSecureAggregationMode(item.getSecureAggregationMode());
            vo.setSecureAggregationStatus(item.getSecureAggregationStatus());
            vo.setSecureAggregationSummary(item.getSecureAggregationSummary());
            vo.setSecureAggregationStartedAt(item.getSecureAggregationStartedAt());
            vo.setSecureAggregationFinishedAt(item.getSecureAggregationFinishedAt());
            ModelAsset federatedModelAsset = modelMap.get(item.getFederatedModelAssetId());
            if (federatedModelAsset != null) {
                vo.setFederatedModelAssetName(federatedModelAsset.getAssetName());
            }
            WorkflowFederatedAggregationService.FederatedModelAvailability federatedModelAvailability =
                    workflowFederatedAggregationService.resolveFederatedModelAvailability(item);
            vo.setFederatedModelAvailable(federatedModelAvailability.available());
            vo.setFederatedModelUnavailableReason(
                    federatedModelAvailability.available() ? null : federatedModelAvailability.reason()
            );

            vo.setServerDatasetAssetId(item.getServerDatasetAssetId());
            DatasetAsset datasetAsset = datasetMap.get(item.getServerDatasetAssetId());
            if (datasetAsset != null) {
                vo.setServerDatasetAssetName(datasetAsset.getAssetName());
                vo.setServerDatasetDataFormat(datasetAsset.getDataFormat());
                vo.setServerDatasetTaskType(datasetAsset.getTaskType());
            }

            vo.setIsPublic(item.getIsPublic());
            vo.setStatus(item.getStatus());
            vo.setCurrentStep(item.getCurrentStep());
            vo.setProgress(item.getProgress());
            vo.setPythonJobId(item.getPythonJobId());
            vo.setRemark(item.getRemark());
            vo.setResultRetentionStatus(resolveWorkflowResultRetentionStatus(item));
            vo.setResultSavedAt(item.getResultSavedAt());
            vo.setResultDeletedAt(item.getResultDeletedAt());
            vo.setCreatedAt(item.getCreatedAt());
            vo.setUpdatedAt(item.getUpdatedAt());
            return vo;
        }).collect(Collectors.toList());

        Page<WorkflowListItemVO> result = new Page<>(pageNum, pageSize);
        result.setTotal(entityPage.getTotal());
        result.setRecords(voList);
        log.info(
                "Workflow page result prepared: userId={}, roleCode={}, pageNum={}, pageSize={}, statusFilter={}, returnedWorkflowIds={}, total={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                pageNum,
                pageSize,
                status,
                voList.stream().map(WorkflowListItemVO::getId).collect(Collectors.toList()),
                entityPage.getTotal()
        );
        return result;
    }

    @Override
    public WorkflowDetailVO getWorkflowDetail(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();

        Workflow workflow = getWorkflowById(id);
        boolean sharedClientReadable = "CLIENT".equalsIgnoreCase(currentUser.getRoleCode())
                && Objects.equals(workflow.getIsPublic(), 1);
        if (!sharedClientReadable) {
            assertReadable(workflow, currentUser);
        }

        Map<Long, String> usernameMap = buildUsernameMap(Collections.singletonList(workflow));
        Map<Long, ModelAsset> modelMap = buildModelMap(Collections.singletonList(workflow));
        Map<Long, DatasetAsset> datasetMap = buildDatasetMap(Collections.singletonList(workflow));

        List<WorkflowStep> stepList = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getWorkflowId, id)
                        .orderByAsc(WorkflowStep::getStepNo)
                        .orderByAsc(WorkflowStep::getCreatedAt)
        );

        List<WorkflowStepVO> stepVOList = stepList.stream().map(step -> {
            WorkflowStepVO vo = new WorkflowStepVO();
            vo.setStepNo(step.getStepNo());
            vo.setStepCode(step.getStepCode());
            vo.setStepName(step.getStepName());
            vo.setFromStatus(step.getFromStatus());
            vo.setToStatus(step.getToStatus());
            vo.setOperatorUserId(step.getOperatorUserId());
            vo.setOperatorRole(step.getOperatorRole());
            vo.setMessage(step.getMessage());
            vo.setCreatedAt(step.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());

        WorkflowModelUploadSummaryService.WorkflowUploadSummary uploadSummary =
                workflowModelUploadSummaryService.summarizeWorkflow(workflow.getId());
        WorkflowDetailVO detailVO = new WorkflowDetailVO();
        detailVO.setId(workflow.getId());
        detailVO.setWorkflowCode(workflow.getWorkflowCode());
        detailVO.setWorkflowName(workflow.getWorkflowName());
        detailVO.setInitiatorUserId(workflow.getInitiatorUserId());
        detailVO.setInitiatorUsername(usernameMap.get(workflow.getInitiatorUserId()));
        detailVO.setServerUserId(workflow.getServerUserId());
        detailVO.setServerUsername(usernameMap.get(workflow.getServerUserId()));

        detailVO.setClientModelAssetId(workflow.getClientModelAssetId());
        ModelAsset modelAsset = modelMap.get(workflow.getClientModelAssetId());
        if (modelAsset != null) {
            detailVO.setClientModelAssetName(modelAsset.getAssetName());
            detailVO.setClientModelVersion(modelAsset.getModelVersion());
        }
        detailVO.setClientModelCount(workflow.getClientModelCount());
        detailVO.setExpectedModelCount(workflow.getExpectedModelCount());
        detailVO.setYoloVersion(workflow.getYoloVersion());
        applyUploadSummary(detailVO, workflow, uploadSummary);
        detailVO.setFederatedStatus(workflow.getFederatedStatus());
        detailVO.setFederatedStrategy(workflow.getFederatedStrategy());
        detailVO.setFederatedModelAssetId(workflow.getFederatedModelAssetId());
        detailVO.setFederatedStartedAt(workflow.getFederatedStartedAt());
        detailVO.setFederatedFinishedAt(workflow.getFederatedFinishedAt());
        detailVO.setDpEnabled(isEnabled(workflow.getDpEnabled()));
        detailVO.setDpEpsilon(workflow.getDpEpsilon());
        detailVO.setDpDelta(workflow.getDpDelta());
        detailVO.setDpClipNorm(workflow.getDpClipNorm());
        detailVO.setDpNoiseMultiplier(workflow.getDpNoiseMultiplier());
        detailVO.setDpStatus(workflow.getDpStatus());
        detailVO.setDpSummary(workflow.getDpSummary());
        detailVO.setShuffleEnabled(isEnabled(workflow.getShuffleEnabled()));
        detailVO.setShuffleBatchNo(workflow.getShuffleBatchNo());
        detailVO.setShuffleStatus(workflow.getShuffleStatus());
        detailVO.setShuffleOrderSummary(workflow.getShuffleOrderSummary());
        detailVO.setShuffleStartedAt(workflow.getShuffleStartedAt());
        detailVO.setShuffleFinishedAt(workflow.getShuffleFinishedAt());
        detailVO.setSecureAggregationEnabled(isEnabled(workflow.getSecureAggregationEnabled()));
        detailVO.setSecureAggregationMode(workflow.getSecureAggregationMode());
        detailVO.setSecureAggregationStatus(workflow.getSecureAggregationStatus());
        detailVO.setSecureAggregationSummary(workflow.getSecureAggregationSummary());
        detailVO.setSecureAggregationStartedAt(workflow.getSecureAggregationStartedAt());
        detailVO.setSecureAggregationFinishedAt(workflow.getSecureAggregationFinishedAt());
        ModelAsset federatedModelAsset = modelMap.get(workflow.getFederatedModelAssetId());
        if (federatedModelAsset != null) {
            detailVO.setFederatedModelAssetName(federatedModelAsset.getAssetName());
        }
        WorkflowFederatedAggregationService.FederatedModelAvailability federatedModelAvailability =
                workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow);
        detailVO.setFederatedModelAvailable(federatedModelAvailability.available());
        detailVO.setFederatedModelUnavailableReason(
                federatedModelAvailability.available() ? null : federatedModelAvailability.reason()
        );

        detailVO.setServerDatasetAssetId(workflow.getServerDatasetAssetId());
        DatasetAsset datasetAsset = datasetMap.get(workflow.getServerDatasetAssetId());
        if (datasetAsset != null) {
            detailVO.setServerDatasetAssetName(datasetAsset.getAssetName());
            detailVO.setServerDatasetDataFormat(datasetAsset.getDataFormat());
            detailVO.setServerDatasetTaskType(datasetAsset.getTaskType());
        }

        detailVO.setIsPublic(workflow.getIsPublic());
        detailVO.setStatus(workflow.getStatus());
        detailVO.setCurrentStep(workflow.getCurrentStep());
        detailVO.setProgress(workflow.getProgress());
        detailVO.setPythonJobId(workflow.getPythonJobId());
        detailVO.setRemark(workflow.getRemark());
        detailVO.setMetricsJson(workflow.getMetricsJson());
        detailVO.setResultFilePath(workflow.getResultFilePath());
        detailVO.setResultRetentionStatus(resolveWorkflowResultRetentionStatus(workflow));
        detailVO.setResultSavedAt(workflow.getResultSavedAt());
        detailVO.setResultDeletedAt(workflow.getResultDeletedAt());
        detailVO.setErrorMessage(workflow.getErrorMessage());
        detailVO.setCreatedAt(workflow.getCreatedAt());
        detailVO.setUpdatedAt(workflow.getUpdatedAt());
        detailVO.setSteps(stepVOList);
        return detailVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdrawWorkflow(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertClient(currentUser);

        Workflow workflow = getWorkflowById(id);

        if (!Objects.equals(workflow.getInitiatorUserId(), currentUser.getId())) {
            throw new RuntimeException("只能撤回自己发起的工作流");
        }

        if (!Arrays.asList("CREATED", "ACCEPTED").contains(workflow.getStatus())) {
            throw new RuntimeException("当前状态不允许撤回，只允许 CREATED / ACCEPTED 撤回");
        }

        String oldStatus = workflow.getStatus();
        workflow.setStatus(WorkflowStatusEnum.WITHDRAWN.getCode());
        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                WorkflowCurrentStepSupport.statusStepOf(WorkflowStatusEnum.WITHDRAWN.getCode()),
                "withdraw-workflow"
        );
        workflow.setProgress(WorkflowStatusEnum.progressOf(WorkflowStatusEnum.WITHDRAWN.getCode()));
        workflowMapper.updateById(workflow);

        insertStep(
                workflow.getId(),
                nextStepNo(workflow.getId()),
                "WITHDRAW",
                WorkflowStepTextCatalog.withdrawStepName(),
                oldStatus,
                WorkflowStatusEnum.WITHDRAWN.getCode(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                WorkflowStepTextCatalog.withdrawMessage()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void advanceWorkflow(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertServer(currentUser);
        Workflow workflow = getWorkflowById(id);
        log.info(
                "advanceWorkflow ACCEPT entered: workflowId={}, serverUserId={}, currentStatus={}",
                workflow.getId(),
                currentUser.getId(),
                workflow.getStatus()
        );
        if (!Objects.equals(workflow.getServerUserId(), currentUser.getId())) {
            throw new RuntimeException("Only the assigned server can accept this workflow");
        }
        if (!"CREATED".equals(workflow.getStatus())) {
            throw new RuntimeException("Only CREATED workflows can be accepted. Bind dataset and start the Python job afterwards.");
        }
        int requiredCount = requiredModelCount(workflow);
        int actualUploadedCount = countReceivedUploadsForAcceptance(workflow);
        String countRule = "upload_status in [ENCRYPTED_STORED, DECRYPTING, COMPLETED] and encrypted_file_path exists on server";
        log.info(
                "advanceWorkflow upload count check before accept: workflowId={}, requiredModelCount={}, actualUploadedCountBeforeAccept={}, countRule={}",
                workflow.getId(),
                requiredCount,
                actualUploadedCount,
                countRule
        );
        if (actualUploadedCount < requiredCount) {
            throw new BusinessException(
                    "WORKFLOW_ACCEPT_UPLOAD_NOT_READY",
                    String.format(
                            "Workflow does not have enough uploaded model files on the server yet: current=%d, required=%d. Wait for the real encrypted upload to reach the server before accepting.",
                            actualUploadedCount,
                            requiredCount
                    )
            );
        }
        List<WorkflowModelUpload> uploadsAwaitingServerManagement = listUploadsAwaitingServerManagement(workflow);
        log.info(
                "advanceWorkflow ACCEPT quick validation completed: workflowId={}, requiredModelCount={}, actualUploadedCountBeforeAccept={}, pendingAutoManageCount={}",
                workflow.getId(),
                requiredCount,
                actualUploadedCount,
                uploadsAwaitingServerManagement.size()
        );
        String oldStatus = workflow.getStatus();
        String nextStatus = "ACCEPTED";
        workflow.setStatus(nextStatus);
        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                WorkflowCurrentStepSupport.SERVER_ACCEPTED,
                "accept-workflow"
        );
        workflow.setErrorMessage(null);
        workflow.setProgress(WorkflowStatusEnum.progressOf(nextStatus));
        workflowMapper.updateById(workflow);
        insertStep(
                workflow.getId(),
                nextStepNo(workflow.getId()),
                buildStepCode(nextStatus),
                buildStepName(nextStatus),
                oldStatus,
                nextStatus,
                currentUser.getId(),
                currentUser.getRoleCode(),
                WorkflowStepTextCatalog.acceptMessage(
                        requiredCount,
                        actualUploadedCount,
                        uploadsAwaitingServerManagement.size()
                )
        );
        triggerAcceptAutoManageAfterCommit(
                workflow.getId(),
                currentUser.getId(),
                requiredCount,
                actualUploadedCount
        );
        log.info(
                "advanceWorkflow triggered background auto-manage task: workflowId={}, pendingAutoManageCount={}",
                workflow.getId(),
                uploadsAwaitingServerManagement.size()
        );
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startPythonJob(Long workflowId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertServer(currentUser);

        Workflow workflow = getWorkflowById(workflowId);

        if (!Objects.equals(workflow.getServerUserId(), currentUser.getId())) {
            throw new RuntimeException("只能为分配给自己的工作流启动 Python 任务");
        }

        // 新链路应从 ACCEPTED 启动；保留 PREPARING 仅用于兼容旧数据。
        if (!Arrays.asList("ACCEPTED", "PREPARING").contains(workflow.getStatus())) {
            throw new RuntimeException("当前工作流状态为 " + workflow.getStatus()
                    + "，不允许启动 Python 任务；仅 ACCEPTED 可启动，PREPARING 仅兼容旧数据");
        }

        if (workflow.getPythonJobId() != null && !workflow.getPythonJobId().isBlank()) {
            throw new RuntimeException("该工作流已经启动过 Python 任务");
        }

        if (false && workflow.getClientModelAssetId() == null) {
            throw new RuntimeException("工作流缺少客户端模型");
        }

        if (workflow.getServerDatasetAssetId() == null) {
            throw new RuntimeException("请先绑定服务端数据集，再启动验证任务。");
        }

        String modelFilePath = resolveActualModelPathUsedByValidation(workflow);
        log.info(
                "startPythonJob resolved validation model path: workflowId={}, actualModelPathUsedByValidation={}",
                workflow.getId(),
                modelFilePath
        );

        // 同样校验数据集目录。
        DatasetAsset datasetAsset = datasetAssetMapper.selectOne(
                new LambdaQueryWrapper<DatasetAsset>()
                        .eq(DatasetAsset::getId, workflow.getServerDatasetAssetId())
                        .eq(DatasetAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (datasetAsset == null) {
            throw new RuntimeException("未绑定服务端数据集，请先绑定后再启动验证任务。");
        }
        java.io.File datasetDir = new java.io.File(datasetAsset.getFilePath());
        if (!datasetDir.exists() || !datasetDir.isDirectory()) {
            throw new RuntimeException("数据集目录在服务器上不存在：" + datasetAsset.getFilePath());
        }

        // 仅在全部启动条件确认通过后替换临时历史结果，避免被拒绝的请求误清理结果。
        resultArtifactCleanupService.cleanupReplaceableWorkflowResultsBeforeStart(
                workflow,
                currentUser,
                "new-workflow-validation-replaces-temporary-results"
        );

        String jobId = UUID.randomUUID().toString().replace("-", "");
        int deletedCacheCount = validationImageCacheService.clearValidationImageCache(
                currentUser.getRoleCode(),
                currentUser.getId()
        );
        log.info(
                "Starting workflow validation with FEDERATED_OUTPUT model: workflowId={}, actualModelPathUsedByValidation={}, datasetPath={}, roleCode={}, userId={}, cacheDir={}, deletedImageCount={}",
                workflow.getId(),
                modelFilePath,
                datasetAsset.getFilePath(),
                currentUser.getRoleCode(),
                currentUser.getId(),
                validationImageCacheService.resolveValidationImageCacheDir(currentUser.getRoleCode(), currentUser.getId()),
                deletedCacheCount
        );

        PythonCreateJobRequest request = new PythonCreateJobRequest();
        request.setJobId(jobId);
        request.setJobType(PythonJobTypes.WORKFLOW_VALIDATION);
        request.setWorkflowId(workflow.getId());
        request.setStandaloneValidationId(null);
        request.setModelPath(modelFilePath);
        request.setDatasetPath(datasetAsset.getFilePath());
        request.setAlgorithmType(
            workflow.getYoloVersion() != null ? workflow.getYoloVersion() : "YOLOv10"
        );
        request.setCallbackUrl(pythonIntegrationProperties.getJavaBaseUrl() + "/api/internal/python/jobs/callback");
        request.setCallbackSecret(pythonIntegrationProperties.getCallbackSecret());

        PythonCreateJobResponse response = pythonJobClient.createJob(request);

        workflow.setPythonJobId(response.getData().getJobId());
        workflowMapper.updateById(workflow);

        insertStep(
                workflow.getId(),
                nextStepNo(workflow.getId()),
                "START_PYTHON_JOB",
                WorkflowStepTextCatalog.startPythonJobStepName(),
                workflow.getStatus(),
                workflow.getStatus(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                WorkflowStepTextCatalog.startPythonJobMessage(response.getData().getJobId())
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePythonCallback(PythonCallbackRequest request) {
        log.info("Received Python callback, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, callbackStatus={}, callbackProgress={}, hasMetrics={}, hasResultFile={}, hasErrorMessage={}",
                request.getJobType(),
                request.getWorkflowId(),
                request.getStandaloneValidationId(),
                request.getJobId(),
                request.getStatus(),
                request.getProgress(),
                request.getMetrics() != null,
                request.getResultFile() != null,
                StringUtils.hasText(request.getErrorMessage()));

        if (StringUtils.hasText(request.getJobType()) && !PythonJobTypes.isWorkflowValidation(request.getJobType())) {
            throw new RuntimeException("Python callback jobType mismatch for workflow callback: " + request.getJobType());
        }
        verifyPythonCallbackSign(request);

        Workflow workflow = getWorkflowById(request.getWorkflowId());
        String oldStatus = workflow.getStatus();
        Integer oldProgress = workflow.getProgress();

        if (!Objects.equals(workflow.getId(), request.getWorkflowId())) {
            logCallbackOutcome("Python callback rejected", true, workflow, request, true, false,
                    oldStatus, oldProgress, "workflow_id_mismatch");
            throw new RuntimeException("workflowId mismatch.");
        }

        if (workflow.getPythonJobId() == null || !workflow.getPythonJobId().equals(request.getJobId())) {
            logCallbackOutcome("Python callback rejected", true, workflow, request, true, false,
                    oldStatus, oldProgress, "job_id_mismatch");
            throw new RuntimeException("jobId mismatch.");
        }

        String newStatus = request.getStatus();

        if (isTerminalStatus(oldStatus) && Objects.equals(oldStatus, newStatus)) {
            applyPythonPayload(workflow, request, false);
            workflowMapper.updateById(workflow);
            logCallbackOutcome("Python callback terminal refresh", false, workflow, request, true, true,
                    oldStatus, oldProgress, "terminal_duplicate");
            return;
        }

        if ("WITHDRAWN".equals(oldStatus) || isTerminalStatus(oldStatus)) {
            logCallbackOutcome("Python callback ignored", true, workflow, request, true, true,
                    oldStatus, oldProgress, "terminal_frozen");
            return;
        }

        int oldRank = statusRank(oldStatus);
        int newRank = statusRank(newStatus);

        if (newRank < 0) {
            throw new RuntimeException("不支持的 Python 回调状态：" + newStatus);
        }

        // 状态倒退：直接忽略，避免 Python 重试打满。
        if (newRank < oldRank) {
            logCallbackOutcome("Python callback ignored", true, workflow, request, true, true,
                    oldStatus, oldProgress, "outdated_callback");
            return;
        }

        // 同状态重复回调：更新补充信息，但不重复插入 step。
        if (newRank == oldRank) {
            if (!Objects.equals(oldStatus, newStatus)) {
                logCallbackOutcome("Python callback ignored", true, workflow, request, true, true,
                        oldStatus, oldProgress, "same_rank_conflict");
                return;
            }
            applyPythonPayload(workflow, request, false);
            workflowMapper.updateById(workflow);
            logCallbackOutcome("Python callback idempotent refresh", false, workflow, request, true, true,
                    oldStatus, oldProgress, "same_status_refresh");
            return;
        }

        workflow.setStatus(newStatus);
        WorkflowCurrentStepSupport.applyCurrentStep(
                workflow,
                WorkflowCurrentStepSupport.statusStepOf(newStatus),
                "python-callback-" + newStatus
        );
        applyPythonPayload(workflow, request, true);
        workflowMapper.updateById(workflow);

        insertStep(
                workflow.getId(),
                nextStepNo(workflow.getId()),
                "PY_CALLBACK_" + newStatus,
                WorkflowStepTextCatalog.pythonCallbackStepName(newStatus),
                oldStatus,
                newStatus,
                workflow.getServerUserId(),
                "SERVER",
                request.getMessage()
        );

        logCallbackOutcome("Python callback applied", false, workflow, request, true, false,
                oldStatus, oldProgress, "advanced");

        if ("COMPLETED".equals(workflow.getStatus())) {
            validationResultService.warmWorkflowResultCache(workflow.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWorkflowResult(Long workflowId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        Workflow workflow = getWorkflowById(workflowId);
        assertReadable(workflow, currentUser);
        if (!StringUtils.hasText(workflow.getResultFilePath())) {
            throw new RuntimeException("当前工作流还没有可保存的结果。");
        }
        if (ResultArtifactCleanupService.WORKFLOW_RESULT_DELETED.equalsIgnoreCase(defaultString(workflow.getResultRetentionStatus()))) {
            throw new RuntimeException("当前工作流结果已删除，不能保存。");
        }
        workflow.setResultRetentionStatus(ResultArtifactCleanupService.WORKFLOW_RESULT_SAVED);
        workflow.setResultSavedAt(LocalDateTime.now());
        workflow.setResultDeletedAt(null);
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(workflow);
        log.info(
                "Workflow result saved manually: actorUserId={}, actorRole={}, workflowId={}, workflowCode={}, resultFilePath={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                workflow.getId(),
                workflow.getWorkflowCode(),
                workflow.getResultFilePath()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSavedWorkflowResult(Long workflowId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        Workflow workflow = getWorkflowById(workflowId);
        assertReadable(workflow, currentUser);
        if (!ResultArtifactCleanupService.WORKFLOW_RESULT_SAVED.equalsIgnoreCase(defaultString(workflow.getResultRetentionStatus()))) {
            throw new RuntimeException("只有已保存的工作流结果可以手动删除。");
        }
        resultArtifactCleanupService.cleanupWorkflowArtifacts(
                workflow,
                currentUser,
                "manual-delete-saved-workflow-result"
        );
        workflow.setResultFilePath(null);
        workflow.setMetricsJson(null);
        workflow.setResultRetentionStatus(ResultArtifactCleanupService.WORKFLOW_RESULT_DELETED);
        workflow.setResultDeletedAt(LocalDateTime.now());
        workflow.setUpdatedAt(LocalDateTime.now());
        workflowMapper.updateById(workflow);
        log.info(
                "Workflow saved result deleted manually: actorUserId={}, actorRole={}, workflowId={}, workflowCode={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                workflow.getId(),
                workflow.getWorkflowCode()
        );
    }

    private void applyPythonPayload(Workflow workflow, PythonCallbackRequest request, boolean preferEnumProgress) {
        if (request.getProgress() != null) {
            workflow.setProgress(request.getProgress());
        } else if (preferEnumProgress) {
            workflow.setProgress(WorkflowStatusEnum.progressOf(request.getStatus()));
        }

        if (request.getMetrics() != null) {
            workflow.setMetricsJson(writeJson(request.getMetrics()));
        }

        if (request.getResultFile() != null) {
            PythonCallbackResultFile resultFile = request.getResultFile();
            if (StringUtils.hasText(resultFile.getFilePath())) {
                workflow.setResultFilePath(resultFile.getFilePath());
            } else {
                workflow.setResultFilePath(writeJson(buildResultFileMap(resultFile)));
            }
            if (!ResultArtifactCleanupService.WORKFLOW_RESULT_SAVED.equalsIgnoreCase(defaultString(workflow.getResultRetentionStatus()))) {
                workflow.setResultRetentionStatus(ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY);
                workflow.setResultDeletedAt(null);
            }
        }

        if (StringUtils.hasText(request.getErrorMessage())) {
            String rawErrorMessage = request.getErrorMessage();
            String summarizedErrorMessage = WorkflowErrorSummarySupport.summarizeForWorkflow(
                    rawErrorMessage,
                    "验证任务失败，请查看服务端日志"
            );
            if (!Objects.equals(rawErrorMessage, summarizedErrorMessage)) {
                log.warn(
                        "Workflow callback error message summarized before persistence: workflowId={}, jobId={}, rawErrorMessage={}, summarizedErrorMessage={}",
                        workflow.getId(),
                        request.getJobId(),
                        rawErrorMessage,
                        summarizedErrorMessage
                );
            }
            workflow.setErrorMessage(summarizedErrorMessage);
        }
    }

    private void logCallbackOutcome(String action,
                                    boolean warn,
                                    Workflow workflow,
                                    PythonCallbackRequest request,
                                    boolean signVerified,
                                    boolean idempotentIgnore,
                                    String oldStatus,
                                    Integer oldProgress,
                                    String reason) {
        String pattern = "{}, workflowId={}, jobId={}, callbackStatus={}, signVerified={}, idempotentIgnore={}, oldStatus={}, oldProgress={}, newStatus={}, newProgress={}, resultFilePathPersisted={}, errorMessagePersisted={}, metricsPersisted={}, reason={}";
        Object[] args = new Object[]{
                action,
                workflow.getId(),
                request.getJobId(),
                request.getStatus(),
                signVerified,
                idempotentIgnore,
                oldStatus,
                oldProgress,
                workflow.getStatus(),
                workflow.getProgress(),
                StringUtils.hasText(workflow.getResultFilePath()),
                StringUtils.hasText(workflow.getErrorMessage()),
                StringUtils.hasText(workflow.getMetricsJson()),
                reason
        };

        if (warn) {
            log.warn(pattern, args);
            return;
        }
        log.info(pattern, args);
    }

    private int statusRank(String status) {
        if ("ACCEPTED".equals(status)) {
            return 1;
        }
        if ("PREPARING".equals(status)) {
            return 2;
        }
        if ("TRAINING_RUNNING".equals(status)) {
            return 3;
        }
        if ("VALIDATING".equals(status)) {
            return 4;
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            return 5;
        }
        if ("CREATED".equals(status)) {
            return 0;
        }
        return -1;
    }

    private String resolveWorkflowResultRetentionStatus(Workflow workflow) {
        if (workflow == null) {
            return ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY;
        }
        if (StringUtils.hasText(workflow.getResultRetentionStatus())) {
            return workflow.getResultRetentionStatus();
        }
        if (!StringUtils.hasText(workflow.getResultFilePath())) {
            return ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY;
        }
        return ResultArtifactCleanupService.WORKFLOW_RESULT_TEMPORARY;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private void verifyPythonCallbackSign(PythonCallbackRequest request) {
        try {
            if (!StringUtils.hasText(request.getSign())) {
                log.warn("Python callback sign verify result, workflowId={}, jobId={}, callbackStatus={}, signVerified=false, reason=missing_sign",
                        request.getWorkflowId(), request.getJobId(), request.getStatus());
                throw new RuntimeException("Python 回调缺少签名");
            }
            if (!StringUtils.hasText(pythonIntegrationProperties.getCallbackSecret())) {
                log.error("Python callback sign verify result, workflowId={}, jobId={}, callbackStatus={}, signVerified=false, reason=missing_server_secret",
                        request.getWorkflowId(), request.getJobId(), request.getStatus());
                throw new RuntimeException("Java callbackSecret 未配置。");
            }

            String json = writeCanonicalJson(buildCallbackPayloadForSign(request));
            String expectedSign = sha256Hex(json + pythonIntegrationProperties.getCallbackSecret());

            if (!expectedSign.equalsIgnoreCase(request.getSign())) {
                log.warn("Python callback sign verify result, workflowId={}, jobId={}, callbackStatus={}, signVerified=false",
                        request.getWorkflowId(), request.getJobId(), request.getStatus());
                throw new RuntimeException("Python 回调签名校验失败");
            }
            log.info("Python callback sign verify result, workflowId={}, jobId={}, callbackStatus={}, signVerified=true",
                    request.getWorkflowId(), request.getJobId(), request.getStatus());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Python callback sign verify result, workflowId={}, jobId={}, callbackStatus={}, signVerified=false, reason=exception",
                    request.getWorkflowId(), request.getJobId(), request.getStatus(), ex);
            throw new RuntimeException("Python 回调签名校验异常: " + ex.getMessage(), ex);
        }
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    private Map<String, Object> buildCallbackPayloadForSign(PythonCallbackRequest request) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("errorMessage", request.getErrorMessage());
        payload.put("jobId", request.getJobId());
        payload.put("jobType", request.getJobType());
        payload.put("message", request.getMessage());
        payload.put("metrics", request.getMetrics());
        payload.put("progress", request.getProgress());
        payload.put("resultFile", buildResultFileMap(request.getResultFile()));
        payload.put("standaloneValidationId", request.getStandaloneValidationId());
        payload.put("status", request.getStatus());
        payload.put("workflowId", request.getWorkflowId());
        return payload;
    }

    private Map<String, Object> buildResultFileMap(PythonCallbackResultFile resultFile) {
        if (resultFile == null) {
            return null;
        }

        Map<String, Object> payload = new TreeMap<>();
        payload.put("fileName", resultFile.getFileName());
        payload.put("filePath", resultFile.getFilePath());
        return payload;
    }

    private String writeCanonicalJson(Object value) {
        try {
            ObjectMapper mapper = objectMapper.copy();
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException("鍥炶皟绛惧悕 JSON 序列化失败: " + ex.getMessage(), ex);
        }
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Python 回调签名计算异常: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException("JSON 序列化失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindServerDataset(Long workflowId, Long serverDatasetAssetId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertServer(currentUser);

        Workflow workflow = getWorkflowById(workflowId);

        if (!Objects.equals(workflow.getServerUserId(), currentUser.getId())) {
            throw new RuntimeException("Only the assigned server can bind the validation dataset.");
        }

        if (workflow.getPythonJobId() != null && !workflow.getPythonJobId().isBlank()) {
            throw new RuntimeException("Python 任务已启动，不能再修改验证数据集");
        }

        if (Arrays.asList("VALIDATING", "COMPLETED", "FAILED", "WITHDRAWN").contains(workflow.getStatus())) {
            throw new RuntimeException("当前状态不允许再绑定服务端数据集");
        }

        DatasetAsset datasetAsset = datasetAssetMapper.selectOne(
                new LambdaQueryWrapper<DatasetAsset>()
                        .eq(DatasetAsset::getId, serverDatasetAssetId)
                        .eq(DatasetAsset::getOwnerUserId, currentUser.getId())
                        .eq(DatasetAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (datasetAsset == null) {
            throw new RuntimeException("所选数据集不存在或无权访问");
        }

        // 校验数据集有实际图片内容。
        if (datasetAsset.getFilePathValidated() == null || datasetAsset.getFilePathValidated() == 0) {
            throw new RuntimeException(
                    "所选数据集路径尚未通过校验，请重新校验或选择其他数据集。"
            );
        }
        if (datasetAsset.getImageCount() == null || datasetAsset.getImageCount() == 0) {
            throw new RuntimeException(
                    "所选数据集没有可用图片，请确认目录中包含 .jpg、.jpeg 或 .png 文件。"
            );
        }
        log.info("bindServerDataset 数据集校验通过：datasetId={}，图片数={}，类型={}",
                datasetAsset.getId(), datasetAsset.getImageCount(), datasetAsset.getDatasetType());

        workflow.setServerDatasetAssetId(datasetAsset.getId());
        workflowMapper.updateById(workflow);

        insertStep(
                workflow.getId(),
                nextStepNo(workflow.getId()),
                "BIND_DATASET",
                WorkflowStepTextCatalog.bindDatasetStepName(),
                workflow.getStatus(),
                workflow.getStatus(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                WorkflowStepTextCatalog.bindDatasetMessage(datasetAsset.getAssetName())
        );
    }

    private Workflow getWorkflowById(Long id) {
        Workflow workflow = workflowMapper.selectOne(
                new LambdaQueryWrapper<Workflow>()
                        .eq(Workflow::getId, id)
                        .eq(Workflow::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (workflow == null) {
            throw new RuntimeException("工作流不存在");
        }
        return workflow;
    }

    private void assertReadable(Workflow workflow, SysUser currentUser) {
        if ("CLIENT".equalsIgnoreCase(currentUser.getRoleCode())) {
            if (!Objects.equals(workflow.getInitiatorUserId(), currentUser.getId())) {
                throw new RuntimeException("无权查看该工作流");
            }
            return;
        }

        if ("SERVER".equalsIgnoreCase(currentUser.getRoleCode())) {
            if (!Objects.equals(workflow.getServerUserId(), currentUser.getId())) {
                throw new RuntimeException("无权查看该工作流");
            }
            return;
        }

        throw new RuntimeException("未知角色，无权访问。");
    }

    private void assertClient(SysUser user) {
        if (!"CLIENT".equalsIgnoreCase(user.getRoleCode())) {
            throw new RuntimeException("只有 CLIENT 角色可以执行该操作。");
        }
    }

    private void assertServer(SysUser user) {
        if (!"SERVER".equalsIgnoreCase(user.getRoleCode())) {
            throw new RuntimeException("只有 SERVER 角色可以执行该操作。");
        }
    }

    private Map<Long, String> buildUsernameMap(List<Workflow> workflowList) {
        Set<Long> userIds = new HashSet<>();
        for (Workflow workflow : workflowList) {
            if (workflow.getInitiatorUserId() != null) {
                userIds.add(workflow.getInitiatorUserId());
            }
            if (workflow.getServerUserId() != null) {
                userIds.add(workflow.getServerUserId());
            }
        }

        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<SysUser> userList = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, userIds)
                        .eq(SysUser::getIsDeleted, 0)
        );

        return userList.stream().collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));
    }

    private Map<Long, ModelAsset> buildModelMap(List<Workflow> workflowList) {
        Set<Long> modelIds = new HashSet<>();
        for (Workflow workflow : workflowList) {
            if (workflow.getClientModelAssetId() != null) {
                modelIds.add(workflow.getClientModelAssetId());
            }
            if (workflow.getFederatedModelAssetId() != null) {
                modelIds.add(workflow.getFederatedModelAssetId());
            }
        }

        if (modelIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ModelAsset> modelAssets = modelAssetMapper.selectList(
                new LambdaQueryWrapper<ModelAsset>()
                        .in(ModelAsset::getId, modelIds)
                        .eq(ModelAsset::getIsDeleted, 0)
        );

        return modelAssets.stream().collect(Collectors.toMap(ModelAsset::getId, item -> item));
    }

    private Map<Long, DatasetAsset> buildDatasetMap(List<Workflow> workflowList) {
        Set<Long> datasetIds = new HashSet<>();
        for (Workflow workflow : workflowList) {
            if (workflow.getServerDatasetAssetId() != null) {
                datasetIds.add(workflow.getServerDatasetAssetId());
            }
        }

        if (datasetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<DatasetAsset> datasetAssets = datasetAssetMapper.selectList(
                new LambdaQueryWrapper<DatasetAsset>()
                        .in(DatasetAsset::getId, datasetIds)
                        .eq(DatasetAsset::getIsDeleted, 0)
        );

        return datasetAssets.stream().collect(Collectors.toMap(DatasetAsset::getId, item -> item));
    }

    private Map<Long, WorkflowModelUploadSummaryService.WorkflowUploadSummary> buildUploadSummaryMap(List<Workflow> workflowList) {
        Set<Long> workflowIds = workflowList.stream()
                .map(Workflow::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (workflowIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return workflowModelUploadSummaryService.summarizeWorkflows(workflowIds);
    }

    private void applyUploadSummary(WorkflowListItemVO vo,
                                    Workflow workflow,
                                    WorkflowModelUploadSummaryService.WorkflowUploadSummary summary) {
        WorkflowModelUploadSummaryService.WorkflowUploadSummary safeSummary =
                summary == null ? new WorkflowModelUploadSummaryService.WorkflowUploadSummary() : summary;
        int requiredCount = requiredModelCount(workflow);
        vo.setActiveUploadCount(safeSummary.getActiveUploadCount());
        vo.setReceivedModelCount(safeSummary.getReceivedModelCount());
        vo.setCollectedModelCount(safeSummary.getCompletedModelCount());
        vo.setUploadLimitReached(safeSummary.getActiveUploadCount() >= requiredCount);
        vo.setLatestUploadStatus(safeSummary.getLatestUploadStatus());
    }

    private void applyUploadSummary(WorkflowDetailVO vo,
                                    Workflow workflow,
                                    WorkflowModelUploadSummaryService.WorkflowUploadSummary summary) {
        WorkflowModelUploadSummaryService.WorkflowUploadSummary safeSummary =
                summary == null ? new WorkflowModelUploadSummaryService.WorkflowUploadSummary() : summary;
        int requiredCount = requiredModelCount(workflow);
        vo.setActiveUploadCount(safeSummary.getActiveUploadCount());
        vo.setReceivedModelCount(safeSummary.getReceivedModelCount());
        vo.setCollectedModelCount(safeSummary.getCompletedModelCount());
        vo.setUploadLimitReached(safeSummary.getActiveUploadCount() >= requiredCount);
        vo.setLatestUploadStatus(safeSummary.getLatestUploadStatus());
    }

    private int countReceivedUploadsForAcceptance(Workflow workflow) {
        List<WorkflowModelUpload> receivedUploads = listWorkflowUploadsByStatuses(
                workflow,
                WorkflowModelUploadStatusEnum.receivedFileCodes()
        );

        int actualUploadedCount = 0;
        for (WorkflowModelUpload uploadRecord : receivedUploads) {
            if (findReceivedEncryptedFilePath(workflow, uploadRecord, false) != null) {
                actualUploadedCount++;
            }
        }
        return actualUploadedCount;
    }

    private List<WorkflowModelUpload> listUploadsAwaitingServerManagement(Workflow workflow) {
        List<WorkflowModelUpload> receivedUploads = listWorkflowUploadsByStatuses(
                workflow,
                WorkflowModelUploadStatusEnum.receivedFileCodes()
        );

        List<WorkflowModelUpload> pendingUploads = new ArrayList<>();
        for (WorkflowModelUpload uploadRecord : receivedUploads) {
            Path encryptedFilePath = findReceivedEncryptedFilePath(workflow, uploadRecord, true);
            if (encryptedFilePath == null) {
                continue;
            }
            if (findUsableServerManagedModelPath(workflow, uploadRecord, false) != null) {
                continue;
            }
            if (!"ENCRYPTED_STORED".equals(uploadRecord.getUploadStatus())) {
                log.info(
                        "Skipping upload from auto decrypt candidate list because status is not retryable for auto-manage: workflowId={}, uploadId={}, uploadStatus={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getUploadStatus()
                );
                continue;
            }
            pendingUploads.add(uploadRecord);
        }
        return pendingUploads;
    }
    private String resolveActualModelPathUsedByValidation(Workflow workflow) {
        WorkflowFederatedAggregationService.FederatedModelAvailability availability =
                workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow);
        if (!availability.available()) {
            throw new BusinessException(availability.code(), availability.reason());
        }
        log.info(
                "Resolved FEDERATED_OUTPUT model path for workflow validation: workflowId={}, federatedModelAssetId={}, actualModelPathUsedByValidation={}",
                workflow.getId(),
                workflow.getFederatedModelAssetId(),
                availability.modelPath()
        );
        return availability.modelPath().toString();
    }

    private List<WorkflowModelUpload> listWorkflowUploadsByStatuses(Workflow workflow, Set<String> statuses) {
        if (workflow == null || workflow.getId() == null || statuses == null || statuses.isEmpty()) {
            return Collections.emptyList();
        }
        return workflowModelUploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflow.getId())
                        .in(WorkflowModelUpload::getUploadStatus, statuses)
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByDesc(WorkflowModelUpload::getCreatedAt)
        );
    }

    private Path findReceivedEncryptedFilePath(Workflow workflow, WorkflowModelUpload uploadRecord, boolean logRejected) {
        if (uploadRecord == null) {
            return null;
        }
        if (!StringUtils.hasText(uploadRecord.getEncryptedFilePath())) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because encrypted file path is blank: workflowId={}, uploadId={}, uploadStatus={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getUploadStatus()
                );
            }
            return null;
        }

        Path encryptedFilePath = Paths.get(uploadRecord.getEncryptedFilePath()).toAbsolutePath().normalize();
        if (!Files.exists(encryptedFilePath) || !Files.isRegularFile(encryptedFilePath)) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because encrypted file does not exist on server: workflowId={}, uploadId={}, encryptedFilePath={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        encryptedFilePath
                );
            }
            return null;
        }
        return encryptedFilePath;
    }

    private Path findUsableServerManagedModelPath(Workflow workflow, WorkflowModelUpload uploadRecord, boolean logRejected) {
        if (uploadRecord == null || uploadRecord.getServerModelAssetId() == null) {
            if (logRejected && uploadRecord != null) {
                log.warn(
                        "Skipping workflow upload without server model asset: workflowId={}, uploadId={}",
                        workflow.getId(),
                        uploadRecord.getId()
                );
            }
            return null;
        }

        ModelAsset serverModelAsset = modelAssetMapper.selectOne(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getId, uploadRecord.getServerModelAssetId())
                        .eq(ModelAsset::getOwnerUserId, workflow.getServerUserId())
                        .eq(ModelAsset::getOwnerRoleCode, "SERVER")
                        .eq(ModelAsset::getStatus, "READY")
                        .eq(ModelAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (serverModelAsset == null) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because server model asset is missing: workflowId={}, uploadId={}, serverModelAssetId={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getServerModelAssetId()
                );
            }
            return null;
        }

        if (!StringUtils.hasText(serverModelAsset.getFilePath())) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because server model asset file path is blank: workflowId={}, uploadId={}, serverModelAssetId={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getServerModelAssetId()
                );
            }
            return null;
        }

        if (serverModelAsset.getFilePathValidated() == null || serverModelAsset.getFilePathValidated() != 1) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because server model asset path is not validated: workflowId={}, uploadId={}, serverModelAssetId={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getServerModelAssetId()
                );
            }
            return null;
        }

        Path actualModelPath = Paths.get(serverModelAsset.getFilePath()).toAbsolutePath().normalize();
        Path modelRootPath = workflowStorageProperties.modelRootDirPath();
        if (!actualModelPath.startsWith(modelRootPath)) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because model path is outside configured server model root: workflowId={}, uploadId={}, serverModelAssetId={}, modelRootPath={}, candidatePath={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getServerModelAssetId(),
                        modelRootPath,
                        actualModelPath
                );
            }
            return null;
        }

        if (!Files.exists(actualModelPath) || !Files.isRegularFile(actualModelPath)) {
            if (logRejected) {
                log.warn(
                        "Skipping workflow upload because model file does not exist on server: workflowId={}, uploadId={}, serverModelAssetId={}, candidatePath={}",
                        workflow.getId(),
                        uploadRecord.getId(),
                        uploadRecord.getServerModelAssetId(),
                        actualModelPath
                );
            }
            return null;
        }

        log.info(
                "Resolved server managed model path for workflow validation: workflowId={}, uploadId={}, serverModelAssetId={}, actualModelPathUsedByValidation={}",
                workflow.getId(),
                uploadRecord.getId(),
                uploadRecord.getServerModelAssetId(),
                actualModelPath
        );
        return actualModelPath;
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

    private BigDecimal resolvePositiveDecimal(BigDecimal value, BigDecimal fallback) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return fallback;
        }
        return value;
    }

    private BigDecimal resolveNonNegativeDecimal(BigDecimal value, BigDecimal fallback) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return fallback;
        }
        return value;
    }

    private String buildDpSummary(Workflow workflow) {
        return "差分隐私已启用，epsilon=" + workflow.getDpEpsilon()
                + "，delta=" + workflow.getDpDelta()
                + "，clipNorm=" + workflow.getDpClipNorm()
                + "，noiseMultiplier=" + workflow.getDpNoiseMultiplier();
    }

    private boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    private void triggerAcceptAutoManageAfterCommit(Long workflowId,
                                                    Long serverUserId,
                                                    int requiredModelCount,
                                                    int actualUploadedCountBeforeAccept) {
        Runnable trigger = () -> workflowAcceptAutoManageAsyncService.processAcceptedWorkflowAsync(
                workflowId,
                serverUserId,
                requiredModelCount,
                actualUploadedCountBeforeAccept
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
            log.info(
                    "Registered ACCEPT background auto-manage trigger after transaction commit: workflowId={}, requiredModelCount={}, actualUploadedCountBeforeAccept={}",
                    workflowId,
                    requiredModelCount,
                    actualUploadedCountBeforeAccept
            );
            return;
        }

        log.warn(
                "Transaction synchronization is not active during ACCEPT, running background auto-manage trigger immediately: workflowId={}",
                workflowId
        );
        trigger.run();
    }

    private void insertStep(Long workflowId,
                            Integer stepNo,
                            String stepCode,
                            String stepName,
                            String fromStatus,
                            String toStatus,
                            Long operatorUserId,
                            String operatorRole,
                            String message) {
        WorkflowStep step = new WorkflowStep();
        step.setWorkflowId(workflowId);
        step.setStepNo(stepNo);
        step.setStepCode(stepCode);
        step.setStepName(stepName);
        step.setFromStatus(fromStatus);
        step.setToStatus(toStatus);
        step.setOperatorUserId(operatorUserId);
        step.setOperatorRole(operatorRole);
        step.setMessage(message);
        workflowStepMapper.insert(step);
    }

    private Integer nextStepNo(Long workflowId) {
        Long count = workflowStepMapper.selectCount(
                new LambdaQueryWrapper<WorkflowStep>().eq(WorkflowStep::getWorkflowId, workflowId)
        );
        return count.intValue() + 1;
    }

    private String generateWorkflowCode() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = new Random().nextInt(9000) + 1000;
        return "WF" + time + random;
    }

    private String buildStepCode(String status) {
        switch (status) {
            case "ACCEPTED":
                return "ACCEPT";
            case "PREPARING":
                return "PREPARE";
            case "TRAINING_RUNNING":
                return "TRAIN";
            case "VALIDATING":
                return "VALIDATE";
            case "COMPLETED":
                return "COMPLETE";
            case "FAILED":
                return "FAIL";
            default:
                return status;
        }
    }

    private String buildStepName(String status) {
        return WorkflowStepTextCatalog.manualStatusStepName(status);
    }
}



