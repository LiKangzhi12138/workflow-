package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.workflow.ModelUploadInitRequest;
import com.workflow.dto.workflow.ModelUploadInitResponse;
import com.workflow.dto.workflow.WorkflowUploadProgressVO;
import com.workflow.dto.workflow.WorkflowUploadContractVO;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.enums.WorkflowModelUploadStatusEnum;
import com.workflow.enums.WorkflowStatusEnum;
import com.workflow.exception.BusinessException;
import com.workflow.exception.DatabaseSchemaMismatchException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.WorkflowModelUploadService;
import com.workflow.service.WorkflowStepService;
import com.workflow.support.AssetSourceCatalog;
import com.workflow.support.WorkflowCurrentStepSupport;
import com.workflow.support.WorkflowErrorSummarySupport;
import com.workflow.support.WorkflowStepTextCatalog;
import com.workflow.utils.AesEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowModelUploadServiceImpl implements WorkflowModelUploadService {

    private static final String CLIENT_ROLE = "CLIENT";
    private static final String SERVER_ROLE = "SERVER";
    private static final String UPLOAD_STATUS_PENDING = WorkflowModelUploadStatusEnum.PENDING.getCode();
    private static final String UPLOAD_STATUS_UPLOADING = WorkflowModelUploadStatusEnum.UPLOADING.getCode();
    private static final String UPLOAD_STATUS_ENCRYPTED_STORED = WorkflowModelUploadStatusEnum.ENCRYPTED_STORED.getCode();
    private static final String UPLOAD_STATUS_DECRYPTING = WorkflowModelUploadStatusEnum.DECRYPTING.getCode();
    private static final String UPLOAD_STATUS_COMPLETED = WorkflowModelUploadStatusEnum.COMPLETED.getCode();
    private static final String UPLOAD_STATUS_FAILED = WorkflowModelUploadStatusEnum.FAILED.getCode();
    private static final String MODEL_ASSET_READY_STATUS = "READY";
    private static final String CLIENT_CRYPTO_MODE_WEB_CRYPTO = "WEB_CRYPTO";
    private static final String CLIENT_CRYPTO_MODE_SERVER_COMPAT = "SERVER_COMPAT";
    private static final long MAX_UPLOAD_FILE_SIZE = 500L * 1024 * 1024;
    private static final int TOKEN_EXPIRE_HOURS = 24;
    private static final Set<String> WORKFLOW_UPLOAD_ALLOWED_STATUSES = Set.of(
            WorkflowStatusEnum.CREATED.getCode(),
            WorkflowStatusEnum.ACCEPTED.getCode()
    );

    private final WorkflowModelUploadMapper uploadMapper;
    private final WorkflowMapper workflowMapper;
    private final SysUserMapper sysUserMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final WorkflowStepService workflowStepService;
    private final ModelAssetService modelAssetService;
    private final WorkflowStorageProperties workflowStorageProperties;
    private final WorkflowModelUploadStateService workflowModelUploadStateService;
    private final WorkflowModelUploadSummaryService workflowModelUploadSummaryService;
    private final WorkflowAutoManageStatusService workflowAutoManageStatusService;
    private final WorkflowFederatedAggregationService workflowFederatedAggregationService;

    @Autowired(required = false)
    private WeightsProtocolUploadService weightsProtocolUploadService;

    @Autowired(required = false)
    private WorkflowWeightsFederatedAggregationService weightsFederatedAggregationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelUploadInitResponse initUpload(ModelUploadInitRequest request, Long currentUserId) {
        if (request.getModelAssetId() == null) {
            throw new BusinessException("PARAM_ERROR", "modelAssetId 不能为空");
        }

        Workflow workflow = requireWorkflowForClientUpload(request.getWorkflowId(), currentUserId);
        ModelAsset modelAsset = requireUploadableModelAssetForWorkflow(request.getModelAssetId(), currentUserId, workflow);

        boolean replaceExisting = Boolean.TRUE.equals(request.getReplaceExisting());
        log.info(
                "initUpload entered: workflowId={}, workflowCode={}, uploaderId={}, modelAssetId={}, originalFilename={}, fileSize={}, replaceExisting={}, fileSha256Provided={}",
                workflow.getId(),
                resolveWorkflowCode(workflow),
                currentUserId,
                modelAsset.getId(),
                request.getOriginalFilename(),
                request.getFileSize(),
                replaceExisting,
                StringUtils.hasText(request.getFileSha256())
        );
        prepareUploadSlot(workflow, currentUserId, replaceExisting);
        syncWorkflowModelAsset(workflow, modelAsset.getId());

        String keyBase64 = AesEncryptionUtil.generateKeyBase64();
        String ivBase64 = AesEncryptionUtil.generateIvBase64();
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        if (weightsProtocolUploadService != null) {
            uploadToken = weightsProtocolUploadService.decorateToken(uploadToken, workflow);
        }
        LocalDateTime tokenExpireAt = LocalDateTime.now().plusHours(TOKEN_EXPIRE_HOURS);

        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setWorkflowId(workflow.getId());
        upload.setUploaderId(currentUserId);
        upload.setModelAssetId(modelAsset.getId());
        upload.setClientDisplayName(resolveClientDisplayName(requireUser(currentUserId)));
        upload.setOriginalFilename(request.getOriginalFilename());
        upload.setFileSize(request.getFileSize());
        upload.setFileSha256(StringUtils.hasText(request.getFileSha256()) ? request.getFileSha256().trim() : null);
        upload.setAesKeyBase64(keyBase64);
        upload.setAesIvBase64(ivBase64);
        upload.setUploadToken(uploadToken);
        upload.setTokenExpireAt(tokenExpireAt);
        upload.setUploadStatus(UPLOAD_STATUS_PENDING);
        upload.setFederatedRound(1);
        upload.setFederatedWeight(1);
        upload.setAggregationStatus("PENDING");
        upload.setIsDeleted(0);

        try {
            uploadMapper.insert(upload);
        } catch (DataAccessException e) {
            throw translateDataAccessException(
                    e,
                    "Failed to initialize model upload. Please verify the workflow and model state."
            );
        }

        workflowStepService.appendWorkflowStep(
                workflow.getId(),
                "MODEL_UPLOAD_INIT",
                WorkflowStepTextCatalog.modelUploadInitStepName(),
                workflow.getStatus(),
                workflow.getStatus(),
                currentUserId,
                CLIENT_ROLE,
                WorkflowStepTextCatalog.modelUploadInitMessage(
                        upload.getId(),
                        modelAsset.getId(),
                        request.getOriginalFilename()
                )
        );

        log.info(
                "Initialized model upload: workflowId={}, uploadId={}, uploaderId={}, modelAssetId={}, replaceExisting={}",
                workflow.getId(),
                upload.getId(),
                currentUserId,
                modelAsset.getId(),
                replaceExisting
        );

        return ModelUploadInitResponse.builder()
                .uploadId(upload.getId())
                .workflowId(workflow.getId())
                .modelAssetId(modelAsset.getId())
                .aesKeyBase64(keyBase64)
                .aesIvBase64(ivBase64)
                .uploadToken(uploadToken)
                .tokenExpireAt(tokenExpireAt)
                .uploadProtocol(resolveUploadProtocol(workflow))
                .build();
    }

    @Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
    private void legacyReceiveEncryptedFile(Long uploadId, String uploadToken, MultipartFile file, Long currentUserId) {
        WorkflowModelUpload record = requireUploadRecord(uploadId);
        Workflow workflow = requireWorkflow(record.getWorkflowId());
        String workflowCode = resolveWorkflowCode(workflow);
        String originalFilename = resolveServerModelFilename(record);
        log.info(
                "receiveEncryptedFile entered: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, multipartFilename={}, bytes={}",
                workflow.getId(),
                uploadId,
                workflowCode,
                originalFilename,
                file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : null
        );

        if (!currentUserId.equals(record.getUploaderId())) {
            throw new BusinessException("FORBIDDEN", "当前上传记录不属于你");
        }
        if (record.getModelAssetId() == null) {
            throw new BusinessException(
                    "UPLOAD_RECORD_INVALID",
                    "上传记录缺少模型资产，请重新初始化上传。"
            );
        }
        if (!StringUtils.hasText(uploadToken) || !uploadToken.equals(record.getUploadToken())) {
            throw new BusinessException("UPLOAD_TOKEN_INVALID", "上传令牌无效，请重新发起上传");
        }
        if (record.getTokenExpireAt() != null && LocalDateTime.now().isAfter(record.getTokenExpireAt())) {
            throw new BusinessException(
                    "UPLOAD_TOKEN_EXPIRED",
                    "上传令牌已过期，请重新开始上传。"
            );
        }
        if (!UPLOAD_STATUS_PENDING.equals(record.getUploadStatus())) {
            if (UPLOAD_STATUS_COMPLETED.equals(record.getUploadStatus())) {
                throw new BusinessException("UPLOAD_ALREADY_COMPLETED", "你已经上传过该工作流模型");
            }
            throw new BusinessException("UPLOAD_STATUS_INVALID", "当前上传记录状态不允许继续上传");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("PARAM_ERROR", "上传文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_FILE_SIZE) {
            throw new BusinessException("PARAM_ERROR", "上传文件不能超过 500MB");
        }

        validateReceiveQuota(workflow, uploadId);

        log.info(
                "Receiving encrypted model file content: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, multipartFilename={}, bytes={}",
                workflow.getId(),
                uploadId,
                workflowCode,
                originalFilename,
                file.getOriginalFilename(),
                file.getSize()
        );

        uploadMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getId, uploadId)
                        .set(WorkflowModelUpload::getUploadStatus, UPLOAD_STATUS_UPLOADING)
        );
        record.setUploadStatus(UPLOAD_STATUS_UPLOADING);

        try {
            Path encryptedDir = workflowStorageProperties.modelUploadDirPath()
                    .resolve(String.valueOf(record.getWorkflowId()))
                    .resolve("encrypted");
            Files.createDirectories(encryptedDir);

            String storedFilename = uploadId + "_" + sanitizeFilename(record.getOriginalFilename()) + ".enc";
            Path encryptedFilePath = encryptedDir.resolve(storedFilename);

            file.transferTo(encryptedFilePath);

            record.setStoredFilename(storedFilename);
            record.setEncryptedFilePath(encryptedFilePath.toString());
            record.setFileSize(file.getSize());
            record.setUploadStatus(UPLOAD_STATUS_ENCRYPTED_STORED);
            record.setErrorMessage(null);
            record.setUploadToken(null);
            record.setTokenExpireAt(null);
            record.setUploadedAt(LocalDateTime.now());
            uploadMapper.updateById(record);
            log.info(
                    "workflow_model_upload encrypted_file_path persisted: workflowId={}, uploadId={}, encryptedFilePath={}, uploadStatus={}",
                    record.getWorkflowId(),
                    uploadId,
                    record.getEncryptedFilePath(),
                    record.getUploadStatus()
            );

            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "MODEL_UPLOAD_COMPLETE",
                    WorkflowStepTextCatalog.modelUploadCompleteStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    currentUserId,
                    CLIENT_ROLE,
                    WorkflowStepTextCatalog.modelUploadCompleteMessage(
                            uploadId,
                            record.getModelAssetId(),
                            encryptedFilePath
                    )
            );

            log.info(
                    "Encrypted model file stored: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, modelAssetId={}, encryptedFilePath={}, bytes={}",
                    record.getWorkflowId(),
                    uploadId,
                    workflowCode,
                    originalFilename,
                    record.getModelAssetId(),
                    encryptedFilePath,
                    file.getSize()
            );
        } catch (IOException e) {
            updateRecordAsFailed(record, WorkflowErrorSummarySupport.summarizeForUpload(
                    e,
                    "模型上传文件保存失败，请稍后重试"
            ));
            log.error(
                    "Failed to store encrypted model file: workflowId={}, uploadId={}, targetDir={}",
                    record.getWorkflowId(),
                    uploadId,
                    workflowStorageProperties.modelUploadDirPath(),
                    e
            );
            throw new BusinessException("UPLOAD_RECEIVE_FAILED", "上传文件保存失败，请稍后重试", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = BusinessException.class)
    public void receiveEncryptedFile(Long uploadId,
                                     String uploadToken,
                                     MultipartFile file,
                                     String manifestJson,
                                     String descriptorJson,
                                     String clientCryptoMode,
                                     Long currentUserId) {
        WorkflowModelUpload record = requireUploadRecord(uploadId);
        Workflow workflow = requireWorkflow(record.getWorkflowId());
        String workflowCode = resolveWorkflowCode(workflow);
        String originalFilename = resolveServerModelFilename(record);
        String effectiveClientCryptoMode = resolveClientCryptoMode(clientCryptoMode);
        boolean weightsProtocolV1 = weightsProtocolUploadService != null
                && weightsProtocolUploadService.isV1Token(record.getUploadToken());
        if (weightsProtocolV1 != (weightsProtocolUploadService != null
                && weightsProtocolUploadService.appliesTo(workflow))) {
            throw new BusinessException(
                    "WEIGHTS_PROTOCOL_DISABLED",
                    "权重协议状态已变化，请重新初始化上传。"
            );
        }

        log.info(
                "receiveEncryptedFile entered: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, multipartFilename={}, bytes={}, clientCryptoMode={}",
                workflow.getId(),
                uploadId,
                workflowCode,
                originalFilename,
                file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : null,
                effectiveClientCryptoMode
        );

        if (!currentUserId.equals(record.getUploaderId())) {
            throw new BusinessException("FORBIDDEN", "You are not allowed to upload this workflow model.");
        }
        if (record.getModelAssetId() == null) {
            throw new BusinessException(
                    "UPLOAD_RECORD_INVALID",
                    "Upload record is missing modelAssetId. Please re-initialize the upload."
            );
        }
        if (!StringUtils.hasText(uploadToken) || !uploadToken.equals(record.getUploadToken())) {
            throw new BusinessException("UPLOAD_TOKEN_INVALID", "Upload token is invalid.");
        }
        if (record.getTokenExpireAt() != null && LocalDateTime.now().isAfter(record.getTokenExpireAt())) {
            throw new BusinessException(
                    "UPLOAD_TOKEN_EXPIRED",
                    "Upload token expired. Please start the upload again."
            );
        }
        if (!UPLOAD_STATUS_PENDING.equals(record.getUploadStatus())) {
            if (UPLOAD_STATUS_COMPLETED.equals(record.getUploadStatus())) {
                throw new BusinessException("UPLOAD_ALREADY_COMPLETED", "The workflow model upload has already completed.");
            }
            throw new BusinessException("UPLOAD_STATUS_INVALID", "The workflow model upload is not in a receivable state.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("PARAM_ERROR", "Uploaded model file is empty.");
        }
        if (file.getSize() > MAX_UPLOAD_FILE_SIZE) {
            throw new BusinessException("PARAM_ERROR", "Uploaded model file exceeds the 500MB limit.");
        }

        validateReceiveQuota(workflow, uploadId);

        log.info(
                "Receiving model file content: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, multipartFilename={}, bytes={}, clientCryptoMode={}",
                workflow.getId(),
                uploadId,
                workflowCode,
                originalFilename,
                file.getOriginalFilename(),
                file.getSize(),
                effectiveClientCryptoMode
        );

        uploadMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getId, uploadId)
                        .set(WorkflowModelUpload::getUploadStatus, UPLOAD_STATUS_UPLOADING)
        );
        record.setUploadStatus(UPLOAD_STATUS_UPLOADING);

        try {
            Path encryptedDir = workflowStorageProperties.modelUploadDirPath()
                    .resolve(String.valueOf(record.getWorkflowId()))
                    .resolve("encrypted");
            Files.createDirectories(encryptedDir);

            String storedFilename = weightsProtocolV1
                    ? weightsProtocolUploadService.storedFilename(uploadId, sanitizeFilename(record.getOriginalFilename()))
                    : uploadId + "_" + sanitizeFilename(record.getOriginalFilename()) + ".enc";
            Path encryptedFilePath = encryptedDir.resolve(storedFilename);
            byte[] storedBytes;

            if (CLIENT_CRYPTO_MODE_SERVER_COMPAT.equals(effectiveClientCryptoMode)) {
                if (!StringUtils.hasText(record.getAesKeyBase64()) || !StringUtils.hasText(record.getAesIvBase64())) {
                    throw new BusinessException(
                            "UPLOAD_CRYPTO_CONTEXT_MISSING",
                            "Upload encryption context is missing. Please restart the upload."
                    );
                }

                byte[] plainBytes = file.getBytes();
                String computedSha256 = AesEncryptionUtil.sha256Hex(plainBytes);
                if (StringUtils.hasText(record.getFileSha256())
                        && !computedSha256.equalsIgnoreCase(record.getFileSha256())) {
                    throw new BusinessException(
                            "FILE_INTEGRITY_FAILED",
                            "Model digest mismatch detected before compatibility upload encryption."
                    );
                }

                storedBytes = AesEncryptionUtil.encrypt(plainBytes, record.getAesKeyBase64(), record.getAesIvBase64());
                record.setFileSha256(computedSha256);
                log.info(
                        "Compatibility upload mode encrypted plaintext model on behalf of browser: workflowId={}, uploadId={}, workflowCode={}, multipartFilename={}, plainBytes={}, encryptedBytes={}, computedSha256={}",
                        workflow.getId(),
                        uploadId,
                        workflowCode,
                        file.getOriginalFilename(),
                        plainBytes.length,
                        storedBytes.length,
                        computedSha256
                );
            } else {
                storedBytes = file.getBytes();
            }

            Files.write(encryptedFilePath, storedBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (weightsProtocolV1) {
                weightsProtocolUploadService.persistMetadata(
                        workflow.getId(), uploadId, manifestJson, descriptorJson);
            }

            record.setStoredFilename(storedFilename);
            record.setEncryptedFilePath(encryptedFilePath.toString());
            record.setFileSize(file.getSize());
            record.setUploadStatus(UPLOAD_STATUS_ENCRYPTED_STORED);
            record.setErrorMessage(null);
            record.setUploadToken(null);
            record.setTokenExpireAt(null);
            record.setUploadedAt(LocalDateTime.now());
            uploadMapper.updateById(record);
            log.info(
                    "workflow_model_upload encrypted_file_path persisted: workflowId={}, uploadId={}, encryptedFilePath={}, uploadStatus={}, clientCryptoMode={}, storedBytes={}, fileSha256={}",
                    record.getWorkflowId(),
                    uploadId,
                    record.getEncryptedFilePath(),
                    record.getUploadStatus(),
                    effectiveClientCryptoMode,
                    storedBytes.length,
                    record.getFileSha256()
            );

            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "MODEL_UPLOAD_COMPLETE",
                    WorkflowStepTextCatalog.modelUploadCompleteStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    currentUserId,
                    CLIENT_ROLE,
                    WorkflowStepTextCatalog.modelUploadCompleteMessage(
                            uploadId,
                            record.getModelAssetId(),
                            encryptedFilePath
                    )
            );

            log.info(
                    "Model file stored for workflow upload: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, modelAssetId={}, encryptedFilePath={}, sourceBytes={}, storedBytes={}, clientCryptoMode={}",
                    record.getWorkflowId(),
                    uploadId,
                    workflowCode,
                    originalFilename,
                    record.getModelAssetId(),
                    encryptedFilePath,
                    file.getSize(),
                    storedBytes.length,
                    effectiveClientCryptoMode
            );
        } catch (BusinessException e) {
            updateRecordAsFailed(record, e.getMessage());
            throw e;
        } catch (IOException e) {
            updateRecordAsFailed(record, WorkflowErrorSummarySupport.summarizeForUpload(
                    e,
                    "模型上传文件保存失败，请稍后重试"
            ));
            log.error(
                    "Failed to store workflow model file: workflowId={}, uploadId={}, targetDir={}, clientCryptoMode={}",
                    record.getWorkflowId(),
                    uploadId,
                    workflowStorageProperties.modelUploadDirPath(),
                    effectiveClientCryptoMode,
                    e
            );
            throw new BusinessException("UPLOAD_RECEIVE_FAILED", "上传文件保存失败，请稍后重试", e);
        }
    }

    @Override
    public WorkflowUploadContractVO getUploadContract(Long workflowId, Long currentUserId) {
        Workflow workflow = requireWorkflowForClientUpload(workflowId, currentUserId);
        boolean v1 = weightsProtocolUploadService != null && weightsProtocolUploadService.appliesTo(workflow);
        return WorkflowUploadContractVO.builder()
                .workflowId(workflow.getId())
                .uploadProtocol(v1 ? WeightsProtocolUploadService.PROTOCOL_V1 : WeightsProtocolUploadService.LEGACY_PROTOCOL)
                .manifestRequired(v1)
                .descriptorRequired(v1)
                .acceptedArtifactType(v1 ? "CLIENT_WEIGHTS" : "FULL_CHECKPOINT")
                .build();
    }

    @Override
    public WorkflowUploadProgressVO getUploadProgress(Long workflowId, Long currentUserId) {
        Workflow workflow = requireWorkflow(workflowId);

        SysUser currentUser = sysUserMapper.selectById(currentUserId);
        if (currentUser == null) {
            throw new BusinessException("USER_NOT_FOUND", "Current user not found.");
        }

        boolean canView = currentUserId.equals(workflow.getInitiatorUserId())
                || currentUserId.equals(workflow.getServerUserId())
                || (CLIENT_ROLE.equalsIgnoreCase(currentUser.getRoleCode()) && Objects.equals(workflow.getIsPublic(), 1));
        if (!canView) {
            throw new BusinessException("FORBIDDEN", "无权查看当前工作流的上传进度");
        }

        List<WorkflowModelUpload> uploads = uploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByDesc(WorkflowModelUpload::getCreatedAt)
        );

        WorkflowUploadProgressVO vo = new WorkflowUploadProgressVO();
        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary =
                workflowModelUploadSummaryService.summarizeWorkflow(workflowId);
        int requiredModelCount = requiredModelCount(workflow);
        vo.setWorkflowId(workflowId);
        vo.setWorkflowName(workflow.getWorkflowName());
        vo.setYoloVersion(workflow.getYoloVersion());
        vo.setRequiredModelCount(requiredModelCount);
        vo.setActiveUploadCount(summary.getActiveUploadCount());
        vo.setReceivedModelCount(summary.getReceivedModelCount());
        vo.setCollectedModelCount(summary.getCompletedModelCount());
        vo.setUploadLimitReached(summary.getActiveUploadCount() >= requiredModelCount);
        vo.setReplaceAllowed(requiredModelCount == 1 && summary.getActiveUploadCount() > 0);
        vo.setLatestUploadStatus(summary.getLatestUploadStatus());
        vo.setStatus(workflow.getStatus());
        vo.setDpEnabled(isEnabled(workflow.getDpEnabled()));
        vo.setDpEpsilon(workflow.getDpEpsilon());
        vo.setDpDelta(workflow.getDpDelta());
        vo.setDpClipNorm(workflow.getDpClipNorm());
        vo.setDpNoiseMultiplier(workflow.getDpNoiseMultiplier());
        vo.setDpStatus(workflow.getDpStatus());
        vo.setDpSummary(workflow.getDpSummary());
        vo.setShuffleEnabled(isEnabled(workflow.getShuffleEnabled()));
        vo.setShuffleBatchNo(workflow.getShuffleBatchNo());
        vo.setShuffleStatus(workflow.getShuffleStatus());
        vo.setShuffleOrderSummary(workflow.getShuffleOrderSummary());
        vo.setSecureAggregationEnabled(isEnabled(workflow.getSecureAggregationEnabled()));
        vo.setSecureAggregationMode(workflow.getSecureAggregationMode());
        vo.setSecureAggregationStatus(workflow.getSecureAggregationStatus());
        vo.setSecureAggregationSummary(workflow.getSecureAggregationSummary());

        List<WorkflowUploadProgressVO.UploadRecordVO> recordVOList = new ArrayList<>();
        for (WorkflowModelUpload upload : uploads) {
            SysUser uploader = sysUserMapper.selectById(upload.getUploaderId());

            WorkflowUploadProgressVO.UploadRecordVO recordVO = new WorkflowUploadProgressVO.UploadRecordVO();
            recordVO.setUploadId(upload.getId());
            recordVO.setUploaderName(uploader != null ? uploader.getUsername() : "未知用户");
            recordVO.setUploadStatus(upload.getUploadStatus());
            recordVO.setUploadedAt(upload.getUploadedAt());
            recordVO.setOriginalFilename(upload.getOriginalFilename());
            recordVO.setClientDisplayName(upload.getClientDisplayName());
            recordVO.setModelAssetId(upload.getModelAssetId());
            recordVO.setServerModelAssetId(upload.getServerModelAssetId());
            recordVO.setFederatedRound(upload.getFederatedRound());
            recordVO.setFederatedWeight(upload.getFederatedWeight());
            recordVO.setAggregationStatus(upload.getAggregationStatus());
            recordVO.setErrorMessage(upload.getErrorMessage());
            recordVOList.add(recordVO);
        }
        vo.setUploadRecords(recordVOList);

        return vo;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void decryptModelFile(Long uploadId, Long currentServerUserId) {
        WorkflowModelUpload record = requireUploadRecord(uploadId);

        boolean retryFromFailed = UPLOAD_STATUS_FAILED.equals(record.getUploadStatus());
        if (!UPLOAD_STATUS_ENCRYPTED_STORED.equals(record.getUploadStatus()) && !retryFromFailed) {
            if (UPLOAD_STATUS_COMPLETED.equals(record.getUploadStatus())) {
                throw new BusinessException("UPLOAD_ALREADY_COMPLETED", "当前模型文件已经解密完成");
            }
            throw new BusinessException("UPLOAD_STATUS_INVALID", "当前上传记录状态不允许解密");
        }

        Workflow workflow = requireWorkflow(record.getWorkflowId());
        if (!currentServerUserId.equals(workflow.getServerUserId())) {
            throw new BusinessException("FORBIDDEN", "你无权解密该工作流模型。");
        }
        if (!StringUtils.hasText(record.getEncryptedFilePath())) {
            throw new BusinessException("UPLOAD_FILE_MISSING", "加密模型文件不存在，请客户端重新上传");
        }

        Path encryptedFilePath = Paths.get(record.getEncryptedFilePath());
        if (!Files.exists(encryptedFilePath) || !Files.isRegularFile(encryptedFilePath)) {
            throw new BusinessException("UPLOAD_FILE_MISSING", "加密模型文件不存在，请客户端重新上传");
        }

        Path targetDir = null;
        Path targetFile = null;
        String workflowCode = resolveWorkflowCode(workflow);
        String originalFilename = resolveServerModelFilename(record);
        try {
            workflowModelUploadStateService.markDecrypting(uploadId);
            workflowAutoManageStatusService.updateWorkflowCurrentStep(
                    workflow.getId(),
                    WorkflowCurrentStepSupport.MODEL_DOWNLOADING,
                    "decrypt-download",
                    true
            );
            log.info(
                    "Starting server model download: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, encryptedFilePath={}",
                    workflow.getId(),
                    uploadId,
                    workflowCode,
                    originalFilename,
                    encryptedFilePath
            );

            byte[] encryptedBytes = Files.readAllBytes(encryptedFilePath);
            log.info(
                    "Server model download finished: workflowId={}, uploadId={}, encryptedBytes={}",
                    workflow.getId(),
                    uploadId,
                    encryptedBytes.length
            );
            workflowAutoManageStatusService.updateWorkflowCurrentStep(
                    workflow.getId(),
                    WorkflowCurrentStepSupport.AUTO_DECRYPTING,
                    "decrypt-start",
                    true
            );
            log.info(
                    "Starting server model decrypt: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, encryptedFilePath={}",
                    workflow.getId(),
                    uploadId,
                    workflowCode,
                    originalFilename,
                    encryptedFilePath
            );
            byte[] decryptedBytes = AesEncryptionUtil.decrypt(
                    encryptedBytes,
                    record.getAesKeyBase64(),
                    record.getAesIvBase64()
            );

            if (StringUtils.hasText(record.getFileSha256())) {
                String actualSha256 = AesEncryptionUtil.sha256Hex(decryptedBytes);
                if (!actualSha256.equalsIgnoreCase(record.getFileSha256())) {
                    throw new BusinessException(
                            "FILE_INTEGRITY_FAILED",
                            "SHA256 verification failed. Please upload the model again."
                    );
                }
            }

            if (weightsProtocolUploadService != null && weightsProtocolUploadService.isV1Record(record)) {
                weightsProtocolUploadService.processDecryptedUpload(workflow, record, decryptedBytes);
                workflowAutoManageStatusService.refreshWorkflowAutoManageStatus(workflow.getId(), "weights-v1-success");
                workflowStepService.appendWorkflowStep(
                        workflow.getId(),
                        "WEIGHTS_PROTOCOL_V1_ACCEPTED",
                        "权重包安全检查",
                        workflow.getStatus(),
                        workflow.getStatus(),
                        currentServerUserId,
                        SERVER_ROLE,
                        "weights-only 文件已通过安全检查和 ModelDefinition 兼容性检查。"
                );
                if (weightsFederatedAggregationService != null) {
                    weightsFederatedAggregationService.triggerIfReady(
                            workflow.getId(), currentServerUserId, "weights-v1-upload-success");
                }
                return;
            }

            log.info(
                    "Loaded workflow storage config for decrypt: workflowId={}, uploadId={}, workflowCode={}, modelRootPath={}",
                    workflow.getId(),
                    uploadId,
                    workflowCode,
                    workflowStorageProperties.modelRootDirPath()
            );

            targetFile = buildServerModelFinalPath(workflow, record);
            targetDir = targetFile.getParent();
            log.info(
                    "Resolved server model final path: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, targetDir={}, finalModelPath={}",
                    workflow.getId(),
                    uploadId,
                    workflowCode,
                    originalFilename,
                    targetDir,
                    targetFile
            );
            try {
                Files.createDirectories(targetDir);
                log.info(
                        "Created server model directory: workflowId={}, uploadId={}, workflowCode={}, targetDir={}",
                        workflow.getId(),
                        uploadId,
                        workflowCode,
                        targetDir
                );
            } catch (IOException e) {
                log.error(
                        "Failed to create server model directory: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, targetDir={}",
                        workflow.getId(),
                        uploadId,
                        workflowCode,
                        originalFilename,
                        targetDir,
                        e
                );
                throw new BusinessException(
                        "MODEL_DIRECTORY_CREATE_FAILED",
                        "Failed to create the server model directory.",
                        e
                );
            }

            try {
                Files.write(targetFile, decryptedBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info(
                        "Persisted decrypted model file: workflowId={}, uploadId={}, workflowCode={}, originalFilename={}, finalModelPath={}, fileSize={}",
                        workflow.getId(),
                        uploadId,
                        workflowCode,
                        originalFilename,
                        targetFile,
                        Files.size(targetFile)
                );
                log.info(
                        "Automatic decrypt persisted final server model file: workflowId={}, uploadId={}, finalDecryptedPath={}",
                        workflow.getId(),
                        uploadId,
                        targetFile
                );
            } catch (IOException e) {
                log.error(
                        "Failed to write decrypted model file: workflowId={}, uploadId={}, targetDir={}, targetFile={}",
                        workflow.getId(),
                        uploadId,
                        targetDir,
                        targetFile,
                        e
                );
                throw new BusinessException(
                        "MODEL_WRITE_FAILED",
                        "Failed to write the decrypted server model file.",
                        e
                );
            }

            log.info(
                    "Registering decrypted model into server model assets: workflowId={}, uploadId={}, targetFile={}",
                    workflow.getId(),
                    uploadId,
                    targetFile
            );
            workflowAutoManageStatusService.updateWorkflowCurrentStep(
                    workflow.getId(),
                    WorkflowCurrentStepSupport.MODEL_REGISTERING,
                    "register-server-model",
                    true
            );
            Long serverModelAssetId = registerDecryptedServerModel(workflow, record, targetFile);
            log.info(
                    "Registered server model asset after decrypt: workflowId={}, uploadId={}, serverModelAssetId={}, finalDecryptedPath={}",
                    workflow.getId(),
                    uploadId,
                    serverModelAssetId,
                    targetFile
            );

            record.setDecryptedFilePath(targetFile.toString());
            record.setServerModelAssetId(serverModelAssetId);
            record.setUploadStatus(UPLOAD_STATUS_COMPLETED);
            record.setErrorMessage(null);
            workflowModelUploadStateService.markDecryptSucceeded(
                    workflow.getId(),
                    uploadId,
                    targetFile.toString(),
                    serverModelAssetId
            );
            log.info(
                    "Server model asset id persisted after decrypt: workflowId={}, uploadId={}, decryptedFilePath={}, serverModelAssetId={}, uploadStatus={}",
                    workflow.getId(),
                    uploadId,
                    record.getDecryptedFilePath(),
                    serverModelAssetId,
                    record.getUploadStatus()
            );
            workflowAutoManageStatusService.refreshWorkflowAutoManageStatus(workflow.getId(), "decrypt-success");

            workflowStepService.appendWorkflowStep(
                    workflow.getId(),
                    "MODEL_DECRYPT",
                    WorkflowStepTextCatalog.modelDecryptStepName(),
                    workflow.getStatus(),
                    workflow.getStatus(),
                    currentServerUserId,
                    SERVER_ROLE,
                    WorkflowStepTextCatalog.modelDecryptMessage(
                            uploadId,
                            serverModelAssetId,
                            targetFile
                    )
            );

            log.info(
                    "Model file decrypted and registered: workflowId={}, uploadId={}, targetFile={}, serverModelAssetId={}",
                    workflow.getId(),
                    uploadId,
                    targetFile,
                    serverModelAssetId
            );
        } catch (BusinessException e) {
            String uploadSummary = WorkflowErrorSummarySupport.summarizeForUpload(
                    e,
                    "模型解密或纳管失败，请查看服务端日志"
            );
            log.error(
                    "Business failure during model decrypt/register: workflowId={}, uploadId={}, targetDir={}, targetFile={}",
                    workflow.getId(),
                    uploadId,
                    targetDir,
                    targetFile,
                    e
            );
            workflowModelUploadStateService.markDecryptFailed(
                    record.getId(),
                    resolvePersistedDecryptedFilePath(targetFile),
                    uploadSummary
            );
            workflowAutoManageStatusService.refreshWorkflowAutoManageStatus(workflow.getId(), "decrypt-failed");
            throw new BusinessException(e.getCode(), uploadSummary, e);
        } catch (Exception e) {
            String uploadSummary = WorkflowErrorSummarySupport.summarizeForUpload(
                    e,
                    "模型解密失败，请稍后重试"
            );
            workflowModelUploadStateService.markDecryptFailed(
                    record.getId(),
                    resolvePersistedDecryptedFilePath(targetFile),
                    uploadSummary
            );
            workflowAutoManageStatusService.refreshWorkflowAutoManageStatus(workflow.getId(), "decrypt-exception");
            log.error("Failed to decrypt model file: workflowId={}, uploadId={}", workflow.getId(), uploadId, e);
            throw new BusinessException("DECRYPT_FAILED", "模型解密失败，请稍后重试", e);
        }

        try {
            workflowFederatedAggregationService.triggerFederatedAggregationIfReady(
                    workflow.getId(),
                    currentServerUserId,
                    "decrypt-success"
            );
        } catch (Exception ex) {
            log.error(
                    "Federated aggregation failed after decrypt success, workflowId={}, uploadId={}, summary={}",
                    workflow.getId(),
                    uploadId,
                    WorkflowErrorSummarySupport.summarizeFederatedAggregation(ex),
                    ex
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelUploadInitResponse bindLocalModelAsset(Long workflowId, Long modelAssetId, Long currentUserId) {
        if (modelAssetId == null) {
            throw new BusinessException("PARAM_ERROR", "modelAssetId 不能为空");
        }

        Workflow workflow = requireWorkflowForClientUpload(workflowId, currentUserId);
        ModelAsset modelAsset = requireUploadableModelAsset(modelAssetId, currentUserId);

        log.warn(
                "Rejected bind-local workflow upload because real file transfer is required: workflowId={}, workflowCode={}, uploaderId={}, modelAssetId={}, modelAssetPath={}",
                workflow.getId(),
                resolveWorkflowCode(workflow),
                currentUserId,
                modelAssetId,
                modelAsset.getFilePath()
        );

        throw new BusinessException(
                "REAL_FILE_UPLOAD_REQUIRED",
                "该工作流需要重新选择真实模型文件并上传，不能直接读取客户端登记路径。"
        );
    }

    private Workflow requireWorkflowForClientUpload(Long workflowId, Long currentUserId) {
        Workflow workflow = requireWorkflow(workflowId);
        boolean canUpload = currentUserId.equals(workflow.getInitiatorUserId()) || Objects.equals(workflow.getIsPublic(), 1);
        if (!canUpload) {
            throw new BusinessException("FORBIDDEN", "当前工作流不属于你。");
        }
        if (!WORKFLOW_UPLOAD_ALLOWED_STATUSES.contains(workflow.getStatus())) {
            throw new BusinessException("WORKFLOW_STATUS_INVALID", "当前工作流不允许上传模型");
        }
        return workflow;
    }

    private SysUser requireUser(Long currentUserId) {
        SysUser currentUser = sysUserMapper.selectById(currentUserId);
        if (currentUser == null || isDeleted(currentUser.getIsDeleted())) {
            throw new BusinessException("USER_NOT_FOUND", "当前用户不存在。");
        }
        return currentUser;
    }

    private Workflow requireWorkflow(Long workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || isDeleted(workflow.getIsDeleted())) {
            throw new BusinessException("WORKFLOW_NOT_FOUND", "工作流不存在");
        }
        return workflow;
    }

    private ModelAsset requireUploadableModelAsset(Long modelAssetId, Long currentUserId) {
        ModelAsset modelAsset = modelAssetMapper.selectById(modelAssetId);
        if (modelAsset == null || isDeleted(modelAsset.getIsDeleted())) {
            throw new BusinessException("MODEL_ASSET_NOT_FOUND", "模型资产不存在。");
        }
        if (!currentUserId.equals(modelAsset.getOwnerUserId())) {
            throw new BusinessException("FORBIDDEN", "当前模型不属于你");
        }
        if (!CLIENT_ROLE.equalsIgnoreCase(modelAsset.getOwnerRoleCode())) {
            throw new BusinessException("MODEL_ASSET_ROLE_INVALID", "当前模型资产不允许客户端上传");
        }
        if (isClientPathRegistry(modelAsset)) {
            return modelAsset;
        }
        if (!MODEL_ASSET_READY_STATUS.equalsIgnoreCase(modelAsset.getStatus())) {
            throw new BusinessException("MODEL_ASSET_STATUS_INVALID", "当前模型状态不允许上传");
        }
        if (modelAsset.getFilePathValidated() == null || modelAsset.getFilePathValidated() != 1) {
            throw new BusinessException("MODEL_ASSET_INVALID", "当前模型文件路径尚未校验通过");
        }
        return modelAsset;
    }

    private ModelAsset requireUploadableModelAssetForWorkflow(Long modelAssetId, Long currentUserId, Workflow workflow) {
        if (workflow != null && currentUserId.equals(workflow.getInitiatorUserId())) {
            return requireUploadableModelAsset(modelAssetId, currentUserId);
        }
        if (workflow != null && Objects.equals(workflow.getIsPublic(), 1) && Objects.equals(workflow.getClientModelAssetId(), modelAssetId)) {
            ModelAsset sharedAsset = modelAssetMapper.selectById(modelAssetId);
            if (sharedAsset == null || isDeleted(sharedAsset.getIsDeleted())) {
                throw new BusinessException("MODEL_ASSET_NOT_FOUND", "Model asset not found.");
            }
            if (!CLIENT_ROLE.equalsIgnoreCase(sharedAsset.getOwnerRoleCode())) {
                throw new BusinessException("MODEL_ASSET_ROLE_INVALID", "Selected model asset is not a client model.");
            }
            if (isClientPathRegistry(sharedAsset)) {
                return sharedAsset;
            }
            if (!MODEL_ASSET_READY_STATUS.equalsIgnoreCase(sharedAsset.getStatus())) {
                throw new BusinessException("MODEL_ASSET_STATUS_INVALID", "Selected model asset is not READY.");
            }
            if (sharedAsset.getFilePathValidated() == null || sharedAsset.getFilePathValidated() != 1) {
                throw new BusinessException("MODEL_ASSET_INVALID", "Selected model asset path is not validated.");
            }
            return sharedAsset;
        }
        return requireUploadableModelAsset(modelAssetId, currentUserId);
    }

    private boolean isClientPathRegistry(ModelAsset modelAsset) {
        return modelAsset != null
                && AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY.equalsIgnoreCase(modelAsset.getRecordMode())
                && AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY.equalsIgnoreCase(modelAsset.getSourceType());
    }

    private void syncWorkflowModelAsset(Workflow workflow, Long modelAssetId) {
        if (workflow.getClientModelAssetId() != null && workflow.getClientModelAssetId().equals(modelAssetId)) {
            return;
        }
        workflow.setClientModelAssetId(modelAssetId);
        workflowMapper.updateById(workflow);
        log.info(
                "Synchronized workflow client model asset before upload: workflowId={}, workflowCode={}, clientModelAssetId={}",
                workflow.getId(),
                resolveWorkflowCode(workflow),
                modelAssetId
        );
    }

    private String resolveClientDisplayName(SysUser currentUser) {
        if (currentUser == null) {
            return null;
        }
        if (StringUtils.hasText(currentUser.getDisplayName())) {
            return currentUser.getDisplayName();
        }
        return currentUser.getUsername();
    }

    private void prepareUploadSlot(Workflow workflow, Long currentUserId, boolean replaceExisting) {
        List<WorkflowModelUpload> activeUploads = listActiveSlotUploads(workflow.getId());
        int required = requiredModelCount(workflow);
        if (activeUploads.size() < required) {
            return;
        }

        if (replaceExisting && required == 1) {
            for (WorkflowModelUpload activeUpload : activeUploads) {
                markUploadAsReplaced(activeUpload, currentUserId);
            }
            log.info(
                    "Prepared workflow upload slot by replacing previous uploads: workflowId={}, uploaderId={}, replacedCount={}",
                    workflow.getId(),
                    currentUserId,
                    activeUploads.size()
            );
            return;
        }

        if (required == 1) {
            throw new BusinessException(
                    "WORKFLOW_UPLOAD_FULL",
                    "This workflow already has an active upload record. Use replace upload to continue."
            );
        }

        throw new BusinessException(
                "WORKFLOW_UPLOAD_FULL",
                "This workflow has already reached the upload limit."
        );
    }

    private void validateReceiveQuota(Workflow workflow, Long currentUploadId) {
        int required = requiredModelCount(workflow);
        long occupiedByOtherUploads = listActiveSlotUploads(workflow.getId()).stream()
                .filter(upload -> !upload.getId().equals(currentUploadId))
                .count();
        if (occupiedByOtherUploads >= required) {
            throw new BusinessException(
                    "WORKFLOW_UPLOAD_FULL",
                    "The workflow already reached its upload slot limit. This file was rejected."
            );
        }
    }

    private List<WorkflowModelUpload> listActiveSlotUploads(Long workflowId) {
        return uploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .in(WorkflowModelUpload::getUploadStatus, WorkflowModelUploadStatusEnum.activeSlotCodes())
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByDesc(WorkflowModelUpload::getCreatedAt)
                        .orderByDesc(WorkflowModelUpload::getId)
        );
    }

    private void markUploadAsReplaced(WorkflowModelUpload upload, Long currentUserId) {
        uploadMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getId, upload.getId())
                        .set(WorkflowModelUpload::getIsDeleted, 1)
                        .set(
                                WorkflowModelUpload::getErrorMessage,
                                "REPLACED_BY_UPLOAD@" + currentUserId + "@" + LocalDateTime.now()
                        )
        );
        log.info(
                "Marked workflow upload as replaced: workflowId={}, uploadId={}, previousStatus={}, operatorUserId={}",
                upload.getWorkflowId(),
                upload.getId(),
                upload.getUploadStatus(),
                currentUserId
        );
    }

    private WorkflowModelUpload requireUploadRecord(Long uploadId) {
        WorkflowModelUpload record = uploadMapper.selectTrackedById(uploadId);
        if (record == null || isDeleted(record.getIsDeleted())) {
            throw new BusinessException("UPLOAD_NOT_FOUND", "Upload record not found.");
        }
        return record;
    }

    private Long registerDecryptedServerModel(Workflow workflow, WorkflowModelUpload upload, Path decryptedFilePath) {
        try {
            return modelAssetService.registerDecryptedServerModel(workflow, upload, decryptedFilePath);
        } catch (BusinessException e) {
            log.error(
                    "Failed to register server model asset: workflowId={}, uploadId={}, targetFile={}",
                    workflow.getId(),
                    upload.getId(),
                    decryptedFilePath,
                    e
            );
            throw e;
        }
    }

    private Path buildServerModelStoragePath(Workflow workflow, WorkflowModelUpload upload) {
        return buildServerModelFinalPath(workflow, upload);
    }

    private Path buildServerModelFinalPath(Workflow workflow, WorkflowModelUpload upload) {
        String workflowCode = resolveWorkflowCode(workflow);
        String directoryName = workflowCode + "_" + upload.getId();
        return workflowStorageProperties.modelRootDirPath()
                .resolve(directoryName)
                .resolve(resolveServerModelFilename(upload));
    }

    private String resolveWorkflowCode(Workflow workflow) {
        if (workflow == null) {
            return "workflow";
        }

        String candidate = workflow.getWorkflowCode();
        if (!StringUtils.hasText(candidate)) {
            candidate = workflow.getWorkflowName();
        }
        if (!StringUtils.hasText(candidate) && workflow.getId() != null) {
            candidate = "WF" + workflow.getId();
        }
        if (!StringUtils.hasText(candidate)) {
            candidate = "workflow";
        }

        String sanitized = sanitizeFilename(candidate);
        return StringUtils.hasText(sanitized) ? sanitized : "workflow";
    }

    private String resolveServerModelFilename(WorkflowModelUpload upload) {
        String originalFilename = sanitizeFilename(upload.getOriginalFilename());
        if (!StringUtils.hasText(originalFilename)) {
            String storedFilename = sanitizeFilename(upload.getStoredFilename());
            if (StringUtils.hasText(storedFilename)) {
                String candidate = storedFilename.endsWith(".enc")
                        ? storedFilename.substring(0, storedFilename.length() - 4)
                        : storedFilename;
                int separatorIndex = candidate.indexOf('_');
                if (separatorIndex >= 0 && separatorIndex < candidate.length() - 1) {
                    return candidate.substring(separatorIndex + 1);
                }
                return candidate;
            }
            return "model.bin";
        }
        return originalFilename;
    }

    private String resolvePersistedDecryptedFilePath(Path targetFile) {
        if (targetFile != null && Files.exists(targetFile)) {
            return targetFile.toString();
        }
        return null;
    }

    private void updateRecordAsFailed(WorkflowModelUpload record, String errorMessage) {
        record.setUploadStatus(UPLOAD_STATUS_FAILED);
        record.setErrorMessage(
                WorkflowErrorSummarySupport.summarizeForUpload(errorMessage, "模型处理失败，请查看服务端日志")
        );
        uploadMapper.updateById(record);
    }

    private BusinessException translateDataAccessException(DataAccessException e, String fallbackMessage) {
        Throwable rootCause = getRootCause(e);
        String message = rootCause.getMessage() == null ? "" : rootCause.getMessage();
        if (message.contains("Unknown column")) {
            return new DatabaseSchemaMismatchException(
                    "数据库字段缺失，请检查 workflow_model_upload 表结构是否已同步",
                    e
            );
        }
        return new BusinessException("UPLOAD_INIT_FAILED", fallbackMessage, e);
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private int requiredModelCount(Integer value) {
        return value == null || value <= 0 ? 1 : value;
    }

    private int requiredModelCount(Workflow workflow) {
        if (workflow == null) {
            return 1;
        }
        Integer expectedModelCount = workflow.getExpectedModelCount();
        if (expectedModelCount != null && expectedModelCount > 0) {
            return expectedModelCount;
        }
        return requiredModelCount(workflow.getClientModelCount());
    }

    private boolean isDeleted(Integer value) {
        return value != null && value == 1;
    }

    private boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    private String resolveClientCryptoMode(String clientCryptoMode) {
        if (!StringUtils.hasText(clientCryptoMode)) {
            return CLIENT_CRYPTO_MODE_WEB_CRYPTO;
        }
        String normalized = clientCryptoMode.trim().toUpperCase(Locale.ROOT);
        if (CLIENT_CRYPTO_MODE_SERVER_COMPAT.equals(normalized)) {
            return CLIENT_CRYPTO_MODE_SERVER_COMPAT;
        }
        if (!CLIENT_CRYPTO_MODE_WEB_CRYPTO.equals(normalized)) {
            log.warn("Unknown clientCryptoMode received for workflow upload, fallback to WEB_CRYPTO: {}", clientCryptoMode);
        }
        return CLIENT_CRYPTO_MODE_WEB_CRYPTO;
    }

    private String resolveUploadProtocol(Workflow workflow) {
        return weightsProtocolUploadService == null
                ? WeightsProtocolUploadService.LEGACY_PROTOCOL
                : weightsProtocolUploadService.protocolFor(workflow);
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "model";
        }
        return filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

}


