package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.dto.python.PythonJobTypes;
import com.workflow.dto.validation.CreateStandaloneValidationRequest;
import com.workflow.dto.validation.StandaloneCallbackRequest;
import com.workflow.dto.validation.StandaloneValidationVO;
import com.workflow.dto.validation.UploadStandaloneValidationRequest;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.StandaloneValidation;
import com.workflow.entity.SysUser;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationService;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationServiceImpl implements ValidationService {

    private static final String VALIDATION_MODE_STANDALONE = "STANDALONE";
    private static final String STATUS_INITIATED = "INITIATED";
    private static final String STATUS_VALIDATING = "VALIDATING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ASSET_STATUS_READY = "READY";
    private static final String INPUT_MODE_ASSET_REFERENCE = "ASSET_REFERENCE";
    private static final String INPUT_MODE_TEMP_UPLOAD = "TEMP_UPLOAD";
    private static final String INPUT_CLEANUP_COMPLETED = "COMPLETED";
    private static final String INPUT_CLEANUP_SKIPPED = "SKIPPED";

    private final StandaloneValidationMapper validationMapper;
    private final ModelAssetMapper modelAssetMapper;
    private final DatasetAssetMapper datasetAssetMapper;
    private final LoginUserContext loginUserContext;
    private final PythonJobClient pythonJobClient;
    private final PythonIntegrationProperties pythonIntegrationProperties;
    private final ObjectMapper objectMapper;
    private final ValidationImageCacheService validationImageCacheService;
    private final ValidationResultService validationResultService;
    private final TemporaryValidationInputStorageService temporaryValidationInputStorageService;
    private final ResultArtifactCleanupService resultArtifactCleanupService;

    @Override
    public Long createStandaloneValidation(CreateStandaloneValidationRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        ModelAsset modelAsset = resolveOwnedReadyModelAsset(request.getModelAssetId(), currentUser);
        DatasetAsset datasetAsset = resolveOwnedReadyDatasetAsset(request.getDatasetAssetId(), currentUser);

        StandaloneValidation validation = createValidationRecord(currentUser, modelAsset, datasetAsset, request);
        resultArtifactCleanupService.cleanupPreviousStandaloneResultsForUser(
                currentUser,
                validation.getId(),
                "new-standalone-validation-overwrites-previous-result"
        );
        log.info(
                "Created standalone validation record: validationId={}, validationCode={}, userId={}, roleCode={}, modelAssetId={}, datasetAssetId={}, modelPath={}, datasetPath={}, algorithmType={}",
                validation.getId(),
                validation.getValidationCode(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                modelAsset.getId(),
                datasetAsset.getId(),
                validation.getModelPath(),
                validation.getDatasetPath(),
                validation.getAlgorithmType()
        );
        return validation.getId();
    }

    @Override
    public StandaloneValidationVO submitStandaloneValidation(CreateStandaloneValidationRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        ModelAsset modelAsset = resolveOwnedReadyModelAsset(request.getModelAssetId(), currentUser);
        DatasetAsset datasetAsset = resolveOwnedReadyDatasetAsset(request.getDatasetAssetId(), currentUser);

        log.info(
                "Submitting standalone validation: userId={}, roleCode={}, modelAssetId={}, datasetAssetId={}, modelPath={}, datasetPath={}, jobType={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                modelAsset.getId(),
                datasetAsset.getId(),
                modelAsset.getFilePath(),
                datasetAsset.getFilePath(),
                PythonJobTypes.STANDALONE_VALIDATION
        );

        StandaloneValidation validation = createValidationRecord(currentUser, modelAsset, datasetAsset, request);
        resultArtifactCleanupService.cleanupPreviousStandaloneResultsForUser(
                currentUser,
                validation.getId(),
                "new-standalone-validation-overwrites-previous-result"
        );

        try {
            startStandaloneValidationInternal(validation, currentUser);
        } catch (RuntimeException ex) {
            log.error(
                    "Standalone validation start failed after record creation: validationId={}, userId={}, roleCode={}, message={}",
                    validation.getId(),
                    currentUser.getId(),
                    currentUser.getRoleCode(),
                    ex.getMessage(),
                    ex
            );
            markStandaloneValidationFailed(validation.getId(), ex.getMessage());
        }

        return getValidationDetail(validation.getId());
    }

    @Override
    public StandaloneValidationVO submitTemporaryStandaloneValidation(UploadStandaloneValidationRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        TemporaryValidationInputStorageService.PreparedStandaloneInput prepared =
                temporaryValidationInputStorageService.prepareStandaloneInput(request.getModelFile(), request.getDatasetArchive());

        StandaloneValidation validation = createTemporaryValidationRecord(currentUser, prepared, request);
        resultArtifactCleanupService.cleanupPreviousStandaloneResultsForUser(
                currentUser,
                validation.getId(),
                "new-standalone-validation-overwrites-previous-result"
        );
        log.info(
                "Submitting temporary standalone validation: validationId={}, validationCode={}, userId={}, roleCode={}, modelPath={}, datasetPath={}, inputRootPath={}, datasetImageCount={}, algorithmType={}",
                validation.getId(),
                validation.getValidationCode(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                validation.getModelPath(),
                validation.getDatasetPath(),
                validation.getInputRootPath(),
                prepared.getDatasetImageCount(),
                validation.getAlgorithmType()
        );

        try {
            startStandaloneValidationInternal(validation, currentUser);
        } catch (RuntimeException ex) {
            log.error(
                    "Temporary standalone validation start failed after record creation: validationId={}, userId={}, roleCode={}, message={}",
                    validation.getId(),
                    currentUser.getId(),
                    currentUser.getRoleCode(),
                    ex.getMessage(),
                    ex
            );
            markStandaloneValidationFailed(validation.getId(), ex.getMessage());
        }

        return getValidationDetail(validation.getId());
    }

    @Override
    public void startStandaloneValidation(Long validationId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        StandaloneValidation validation = resolveOwnedValidation(validationId, currentUser);

        try {
            startStandaloneValidationInternal(validation, currentUser);
        } catch (RuntimeException ex) {
            log.error(
                    "Standalone validation start failed: validationId={}, userId={}, roleCode={}, message={}",
                    validationId,
                    currentUser.getId(),
                    currentUser.getRoleCode(),
                    ex.getMessage(),
                    ex
            );
            markStandaloneValidationFailed(validationId, ex.getMessage());
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleStandaloneCallback(StandaloneCallbackRequest request) {
        log.info(
                "Received standalone validation callback: jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}, progress={}, hasMetrics={}",
                request.getJobType(),
                request.getWorkflowId(),
                request.getStandaloneValidationId(),
                request.getJobId(),
                request.getStatus(),
                request.getProgress(),
                request.getMetrics() != null
        );

        if (StringUtils.hasText(request.getJobType()) && !PythonJobTypes.isStandaloneValidation(request.getJobType())) {
            throw new RuntimeException("Standalone callback jobType mismatch: " + request.getJobType());
        }

        verifyCallbackSign(request);

        StandaloneValidation validation = validationMapper.selectOne(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getPythonJobId, request.getJobId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (validation == null) {
            log.warn("No standalone validation record found for callback jobId={}", request.getJobId());
            throw new RuntimeException("\u672A\u627E\u5230\u5BF9\u5E94\u7684\u72EC\u7ACB\u9A8C\u8BC1\u4EFB\u52A1");
        }
        if (request.getStandaloneValidationId() != null && !Objects.equals(validation.getId(), request.getStandaloneValidationId())) {
            throw new RuntimeException("\u72EC\u7ACB\u9A8C\u8BC1\u56DE\u8C03 standaloneValidationId \u4E0D\u5339\u914D");
        }

        String oldStatus = validation.getStatus();
        if (STATUS_COMPLETED.equals(request.getStatus())) {
            validation.setStatus(STATUS_COMPLETED);
            validation.setProgress(100);
            validation.setErrorMessage(null);
            if (request.getMetrics() != null) {
                validation.setMetricsJson(writeJson(request.getMetrics()));
                validation.setQualityLabel(calculateQualityLabel(request.getMetrics()));
            }
            applyStandaloneResultFile(validation, request.getResultFile());
            validation.setFinishedAt(LocalDateTime.now());
        } else if (STATUS_FAILED.equals(request.getStatus())) {
            validation.setStatus(STATUS_FAILED);
            validation.setProgress(0);
            validation.setErrorMessage(request.getErrorMessage());
            validation.setFinishedAt(LocalDateTime.now());
        } else if (STATUS_VALIDATING.equals(request.getStatus())) {
            validation.setStatus(STATUS_VALIDATING);
            if (request.getProgress() != null) {
                validation.setProgress(request.getProgress());
            }
        } else {
            validation.setStatus(request.getStatus());
            if (request.getProgress() != null) {
                validation.setProgress(request.getProgress());
            }
        }

        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.updateById(validation);

        if (STATUS_COMPLETED.equals(validation.getStatus())) {
            try {
                validationResultService.warmStandaloneResultCache(validation.getId());
            } catch (Exception ex) {
                log.warn("Warm standalone validation result cache failed: validationId={}", validation.getId(), ex);
            }
        }

        if (isStandaloneValidationTerminalStatus(validation.getStatus())) {
            cleanupTemporaryInputIfNeeded(validation, "standalone-callback-" + defaultString(request.getStatus()));
        } else {
            log.info(
                    "Skip temporary standalone validation input cleanup because callback status is not terminal: validationId={}, validationCode={}, status={}, inputMode={}, inputRootPath={}",
                    validation.getId(),
                    validation.getValidationCode(),
                    validation.getStatus(),
                    validation.getInputMode(),
                    validation.getInputRootPath()
            );
        }

        if (STATUS_COMPLETED.equals(validation.getStatus())) {
            StandaloneValidation refreshed = validationMapper.selectById(validation.getId());
            if (refreshed != null) {
                validation = refreshed;
            }
        }

        log.info(
                "Standalone validation updated from callback: validationId={}, validationCode={}, oldStatus={}, newStatus={}, progress={}, inputMode={}, inputCleanupStatus={}, jobType={}, standaloneValidationId={}",
                validation.getId(),
                validation.getValidationCode(),
                oldStatus,
                validation.getStatus(),
                validation.getProgress(),
                validation.getInputMode(),
                validation.getInputCleanupStatus(),
                request.getJobType(),
                request.getStandaloneValidationId()
        );
    }

    @Override
    public StandaloneValidationVO getValidationDetail(Long validationId) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        StandaloneValidation validation = resolveOwnedValidation(validationId, currentUser);
        StandaloneValidationVO detail = entityToVO(validation);
        log.info(
                "Loaded standalone validation detail: validationId={}, userId={}, roleCode={}, status={}",
                validationId,
                currentUser.getId(),
                currentUser.getRoleCode(),
                detail.getStatus()
        );
        return detail;
    }

    @Override
    public Page<StandaloneValidationVO> pageValidations(long pageNum, long pageSize) {
        SysUser currentUser = loginUserContext.getCurrentUser();

        Page<StandaloneValidation> page = validationMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getUserId, currentUser.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .orderByDesc(StandaloneValidation::getCreatedAt)
                        .orderByDesc(StandaloneValidation::getId)
        );

        Page<StandaloneValidationVO> voPage = new Page<>(pageNum, pageSize);
        voPage.setTotal(page.getTotal());
        voPage.setSize(page.getSize());
        voPage.setCurrent(page.getCurrent());
        voPage.setPages(page.getPages());
        voPage.setRecords(page.getRecords().stream().map(this::entityToVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public List<StandaloneValidationVO> listMyStandaloneValidations(int limit) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        int safeLimit = sanitizeRecentLimit(limit);
        List<StandaloneValidation> validations = validationMapper.selectList(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getUserId, currentUser.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .orderByDesc(StandaloneValidation::getCreatedAt)
                        .orderByDesc(StandaloneValidation::getId)
                        .last("limit " + safeLimit)
        );

        List<StandaloneValidationVO> results = validations.stream()
                .map(this::entityToVO)
                .collect(Collectors.toList());
        log.info(
                "Loaded my standalone validations: userId={}, roleCode={}, limit={}, actualCount={}",
                currentUser.getId(),
                currentUser.getRoleCode(),
                safeLimit,
                results.size()
        );
        return results;
    }

    private StandaloneValidation createValidationRecord(SysUser currentUser,
                                                        ModelAsset modelAsset,
                                                        DatasetAsset datasetAsset,
                                                        CreateStandaloneValidationRequest request) {
        StandaloneValidation validation = new StandaloneValidation();
        validation.setValidationCode(generateValidationCode());
        validation.setUserId(currentUser.getId());
        validation.setModelAssetId(modelAsset.getId());
        validation.setModelPath(modelAsset.getFilePath());
        validation.setDatasetAssetId(datasetAsset.getId());
        validation.setDatasetPath(datasetAsset.getFilePath());
        validation.setAlgorithmType(resolveAlgorithmType(request, modelAsset));
        validation.setStatus(STATUS_INITIATED);
        validation.setProgress(0);
        validation.setInputMode(INPUT_MODE_ASSET_REFERENCE);
        validation.setRetainInput(1);
        validation.setInputCleanupStatus(INPUT_CLEANUP_SKIPPED);
        validation.setIsDeleted(0);
        validation.setCreatedAt(LocalDateTime.now());
        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.insert(validation);
        return validation;
    }

    private StandaloneValidation createTemporaryValidationRecord(SysUser currentUser,
                                                                 TemporaryValidationInputStorageService.PreparedStandaloneInput prepared,
                                                                 UploadStandaloneValidationRequest request) {
        StandaloneValidation validation = new StandaloneValidation();
        validation.setValidationCode(generateValidationCode());
        validation.setUserId(currentUser.getId());
        validation.setModelAssetId(null);
        validation.setModelPath(prepared.getModelPath().toString());
        validation.setDatasetAssetId(null);
        validation.setDatasetPath(prepared.getDatasetPath().toString());
        validation.setAlgorithmType(resolveAlgorithmType(request, null));
        validation.setInputMode(INPUT_MODE_TEMP_UPLOAD);
        validation.setInputRootPath(prepared.getInputRootPath().toString());
        validation.setRetainInput(0);
        validation.setInputCleanupStatus(null);
        validation.setStatus(STATUS_INITIATED);
        validation.setProgress(0);
        validation.setIsDeleted(0);
        validation.setCreatedAt(LocalDateTime.now());
        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.insert(validation);
        return validation;
    }

    private void startStandaloneValidationInternal(StandaloneValidation validation, SysUser currentUser) {
        verifyValidationStateCanStart(validation);
        verifyModelFileExists(validation.getModelPath());
        verifyDatasetDirectoryExists(validation.getDatasetPath());
        logStandaloneValidationInputState(validation, "before_python_create_job");
        int deletedCacheCount = validationImageCacheService.clearValidationImageCache(
                currentUser.getRoleCode(),
                currentUser.getId()
        );
        log.info(
                "Prepared standalone validation image cache before new job: roleCode={}, userId={}, validationId={}, cacheDir={}, deletedImageCount={}",
                currentUser.getRoleCode(),
                currentUser.getId(),
                validation.getId(),
                validationImageCacheService.resolveValidationImageCacheDir(currentUser.getRoleCode(), currentUser.getId()),
                deletedCacheCount
        );

        PythonCreateJobRequest pythonRequest = buildPythonCreateJobRequest(validation);
        log.info(
                "Starting standalone validation via Python: validationId={}, validationCode={}, userId={}, roleCode={}, modelAssetId={}, datasetAssetId={}, modelPath={}, datasetPath={}, algorithmType={}, callbackUrl={}, jobType={}, standaloneValidationId={}",
                validation.getId(),
                validation.getValidationCode(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                validation.getModelAssetId(),
                validation.getDatasetAssetId(),
                validation.getModelPath(),
                validation.getDatasetPath(),
                validation.getAlgorithmType(),
                pythonRequest.getCallbackUrl(),
                pythonRequest.getJobType(),
                pythonRequest.getStandaloneValidationId()
        );

        PythonCreateJobResponse response = pythonJobClient.createJob(pythonRequest);

        validation.setPythonJobId(response.getData().getJobId());
        validation.setStatus(STATUS_VALIDATING);
        validation.setProgress(10);
        validation.setErrorMessage(null);
        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.updateById(validation);

        log.info(
                "Standalone validation started successfully: validationId={}, validationCode={}, pythonJobId={}, modelPath={}, datasetPath={}",
                validation.getId(),
                validation.getValidationCode(),
                validation.getPythonJobId(),
                validation.getModelPath(),
                validation.getDatasetPath()
        );
    }

    private ModelAsset resolveOwnedReadyModelAsset(Long modelAssetId, SysUser currentUser) {
        ModelAsset modelAsset = modelAssetMapper.selectOne(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getId, modelAssetId)
                        .eq(ModelAsset::getOwnerUserId, currentUser.getId())
                        .eq(ModelAsset::getOwnerRoleCode, currentUser.getRoleCode())
                        .eq(ModelAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (modelAsset == null) {
            throw new RuntimeException("\u6240\u9009\u6A21\u578B\u4E0D\u5B58\u5728\u6216\u65E0\u6743\u8BBF\u95EE");
        }
        if (isClientPathRegistry(modelAsset)) {
            throw new RuntimeException("\u5BA2\u6237\u7AEF\u8DEF\u5F84\u767B\u8BB0\u9879\u4E0D\u53EF\u76F4\u63A5\u7528\u4E8E\u72EC\u7ACB\u9A8C\u8BC1\u3002\u8BF7\u5728\u9A8C\u8BC1\u9875\u9762\u91CD\u65B0\u9009\u62E9\u672C\u5730\u6587\u4EF6\u5E76\u4E34\u65F6\u4E0A\u4F20\u3002");
        }
        if (AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT.equalsIgnoreCase(modelAsset.getSourceType())) {
            throw new RuntimeException("工作流解密中间模型不能用于新的独立验证，请使用浏览器上传、服务端导入或联邦聚合模型。");
        }
        if (isFormalOrServerRegistryRecord(modelAsset) && !ASSET_STATUS_READY.equalsIgnoreCase(defaultString(modelAsset.getStatus()))) {
            throw new RuntimeException("\u6240\u9009\u6A21\u578B\u672A\u5904\u4E8E\u53EF\u7528\u72B6\u6001");
        }
        if (modelAsset.getFilePathValidated() == null || modelAsset.getFilePathValidated() != 1) {
            throw new RuntimeException("\u6240\u9009\u6A21\u578B\u7684\u670D\u52A1\u5668\u53EF\u8BBF\u95EE\u8DEF\u5F84\u672A\u901A\u8FC7\u6821\u9A8C");
        }
        if (!StringUtils.hasText(modelAsset.getFilePath())) {
            throw new RuntimeException("\u6240\u9009\u6A21\u578B\u7F3A\u5C11\u670D\u52A1\u5668\u53EF\u8BBF\u95EE\u8DEF\u5F84");
        }
        verifyModelFileExists(modelAsset.getFilePath());
        return modelAsset;
    }

    private DatasetAsset resolveOwnedReadyDatasetAsset(Long datasetAssetId, SysUser currentUser) {
        DatasetAsset datasetAsset = datasetAssetMapper.selectOne(
                new LambdaQueryWrapper<DatasetAsset>()
                        .eq(DatasetAsset::getId, datasetAssetId)
                        .eq(DatasetAsset::getOwnerUserId, currentUser.getId())
                        .eq(DatasetAsset::getOwnerRoleCode, currentUser.getRoleCode())
                        .eq(DatasetAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (datasetAsset == null) {
            throw new RuntimeException("\u6240\u9009\u6570\u636E\u96C6\u4E0D\u5B58\u5728\u6216\u65E0\u6743\u8BBF\u95EE");
        }
        if (isClientPathRegistry(datasetAsset)) {
            throw new RuntimeException("\u5BA2\u6237\u7AEF\u8DEF\u5F84\u767B\u8BB0\u7684\u6570\u636E\u96C6\u4E0D\u53EF\u76F4\u63A5\u7528\u4E8E\u72EC\u7ACB\u9A8C\u8BC1\u3002\u8BF7\u5728\u9A8C\u8BC1\u9875\u9762\u91CD\u65B0\u9009\u62E9\u6570\u636E\u96C6\u538B\u7F29\u5305\u5E76\u4E34\u65F6\u4E0A\u4F20\u3002");
        }
        if (isFormalOrServerRegistryRecord(datasetAsset) && !ASSET_STATUS_READY.equalsIgnoreCase(defaultString(datasetAsset.getStatus()))) {
            throw new RuntimeException("\u6240\u9009\u6570\u636E\u96C6\u672A\u5904\u4E8E\u53EF\u7528\u72B6\u6001");
        }
        if (datasetAsset.getFilePathValidated() == null || datasetAsset.getFilePathValidated() != 1) {
            throw new RuntimeException("\u6240\u9009\u6570\u636E\u96C6\u7684\u670D\u52A1\u5668\u53EF\u8BBF\u95EE\u8DEF\u5F84\u672A\u901A\u8FC7\u6821\u9A8C");
        }
        if (!StringUtils.hasText(datasetAsset.getFilePath())) {
            throw new RuntimeException("\u6240\u9009\u6570\u636E\u96C6\u7F3A\u5C11\u670D\u52A1\u5668\u53EF\u8BBF\u95EE\u8DEF\u5F84");
        }
        verifyDatasetDirectoryExists(datasetAsset.getFilePath());
        return datasetAsset;
    }

    private StandaloneValidation resolveOwnedValidation(Long validationId, SysUser currentUser) {
        StandaloneValidation validation = validationMapper.selectOne(
                new LambdaQueryWrapper<StandaloneValidation>()
                        .eq(StandaloneValidation::getId, validationId)
                        .eq(StandaloneValidation::getUserId, currentUser.getId())
                        .eq(StandaloneValidation::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (validation == null) {
            throw new RuntimeException("\u72EC\u7ACB\u9A8C\u8BC1\u4EFB\u52A1\u4E0D\u5B58\u5728\u6216\u65E0\u6743\u67E5\u770B");
        }
        return validation;
    }

    private void verifyValidationStateCanStart(StandaloneValidation validation) {
        if (!STATUS_INITIATED.equals(validation.getStatus())) {
            throw new RuntimeException(
                    String.format(
                            Locale.ROOT,
                            "\u5F53\u524D\u9A8C\u8BC1\u4EFB\u52A1\u72B6\u6001\u4E3A %s\uFF0C\u4E0D\u80FD\u518D\u6B21\u542F\u52A8",
                            defaultString(validation.getStatus())
                    )
            );
        }
    }

    private void verifyModelFileExists(String modelPath) {
        Path modelFile = Path.of(modelPath).toAbsolutePath().normalize();
        boolean exists = Files.exists(modelFile);
        boolean regularFile = Files.isRegularFile(modelFile);
        boolean readable = Files.isReadable(modelFile);
        long fileSize = safeFileSize(modelFile);
        log.info(
                "Standalone validation model path check: modelPath={}, exists={}, regularFile={}, readable={}, fileSize={}",
                modelFile,
                exists,
                regularFile,
                readable,
                fileSize
        );
        if (!exists || !regularFile) {
            throw new RuntimeException("\u6A21\u578B\u6587\u4EF6\u5728\u670D\u52A1\u5668\u672C\u5730\u4E0D\u5B58\u5728\uFF1A" + modelPath);
        }
    }

    private void verifyDatasetDirectoryExists(String datasetPath) {
        Path datasetDir = Path.of(datasetPath).toAbsolutePath().normalize();
        boolean exists = Files.exists(datasetDir);
        boolean directory = Files.isDirectory(datasetDir);
        boolean readable = Files.isReadable(datasetDir);
        log.info(
                "Standalone validation dataset path check: datasetPath={}, exists={}, directory={}, readable={}",
                datasetDir,
                exists,
                directory,
                readable
        );
        if (!exists || !directory) {
            throw new RuntimeException("\u6570\u636E\u96C6\u76EE\u5F55\u5728\u670D\u52A1\u5668\u672C\u5730\u4E0D\u5B58\u5728\uFF1A" + datasetPath);
        }
    }

    private void logStandaloneValidationInputState(StandaloneValidation validation, String scene) {
        Path modelPath = Path.of(validation.getModelPath()).toAbsolutePath().normalize();
        Path datasetPath = Path.of(validation.getDatasetPath()).toAbsolutePath().normalize();
        log.info(
                "Standalone validation input state: scene={}, validationId={}, validationCode={}, inputMode={}, inputRootPath={}, modelPath={}, modelExists={}, modelReadable={}, modelSize={}, datasetPath={}, datasetExists={}, datasetReadable={}, datasetDirectory={}",
                scene,
                validation.getId(),
                validation.getValidationCode(),
                validation.getInputMode(),
                validation.getInputRootPath(),
                modelPath,
                Files.exists(modelPath),
                Files.isReadable(modelPath),
                safeFileSize(modelPath),
                datasetPath,
                Files.exists(datasetPath),
                Files.isReadable(datasetPath),
                Files.isDirectory(datasetPath)
        );
    }

    private boolean isStandaloneValidationTerminalStatus(String status) {
        return STATUS_COMPLETED.equalsIgnoreCase(defaultString(status))
                || STATUS_FAILED.equalsIgnoreCase(defaultString(status));
    }

    private long safeFileSize(Path path) {
        try {
            return Files.exists(path) && Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (Exception ex) {
            log.warn("Read standalone validation file size failed: path={}", path, ex);
            return -1L;
        }
    }

    private PythonCreateJobRequest buildPythonCreateJobRequest(StandaloneValidation validation) {
        PythonCreateJobRequest pythonRequest = new PythonCreateJobRequest();
        pythonRequest.setJobId(UUID.randomUUID().toString().replace("-", ""));
        pythonRequest.setJobType(PythonJobTypes.STANDALONE_VALIDATION);
        pythonRequest.setWorkflowId(null);
        pythonRequest.setStandaloneValidationId(validation.getId());
        pythonRequest.setModelPath(validation.getModelPath());
        pythonRequest.setDatasetPath(validation.getDatasetPath());
        pythonRequest.setAlgorithmType(validation.getAlgorithmType());
        pythonRequest.setCallbackUrl(pythonIntegrationProperties.getJavaBaseUrl() + "/api/internal/standalone/callback");
        pythonRequest.setCallbackSecret(pythonIntegrationProperties.getCallbackSecret());
        return pythonRequest;
    }

    private void markStandaloneValidationFailed(Long validationId, String errorMessage) {
        StandaloneValidation validation = validationMapper.selectById(validationId);
        if (validation == null || validation.getIsDeleted() != null && validation.getIsDeleted() == 1) {
            return;
        }
        validation.setStatus(STATUS_FAILED);
        validation.setProgress(0);
        validation.setErrorMessage(trimToLength(defaultString(errorMessage), 500));
        validation.setFinishedAt(LocalDateTime.now());
        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.updateById(validation);
        cleanupTemporaryInputIfNeeded(validation, "standalone-mark-failed");

        log.error(
                "Marked standalone validation as failed: validationId={}, validationCode={}, errorMessage={}",
                validation.getId(),
                validation.getValidationCode(),
                validation.getErrorMessage()
        );
    }

    private String resolveAlgorithmType(CreateStandaloneValidationRequest request, ModelAsset modelAsset) {
        if (request != null && StringUtils.hasText(request.getAlgorithmType())) {
            return request.getAlgorithmType().trim();
        }
        if (modelAsset != null && StringUtils.hasText(modelAsset.getYoloVersion())) {
            return modelAsset.getYoloVersion().trim();
        }
        if (StringUtils.hasText(pythonIntegrationProperties.getAlgorithmType())) {
            return pythonIntegrationProperties.getAlgorithmType().trim();
        }
        return "YOLOv10";
    }

    private String resolveAlgorithmType(UploadStandaloneValidationRequest request, ModelAsset modelAsset) {
        if (request != null && StringUtils.hasText(request.getAlgorithmType())) {
            return request.getAlgorithmType().trim();
        }
        if (modelAsset != null && StringUtils.hasText(modelAsset.getYoloVersion())) {
            return modelAsset.getYoloVersion().trim();
        }
        if (StringUtils.hasText(pythonIntegrationProperties.getAlgorithmType())) {
            return pythonIntegrationProperties.getAlgorithmType().trim();
        }
        return "YOLOv10";
    }

    private String generateValidationCode() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = new Random().nextInt(9000) + 1000;
        return "VAL" + time + random;
    }

    private String calculateQualityLabel(Map<String, Object> metrics) {
        double map50 = getDoubleValue(metrics, "map50", 0.0);
        double precision = getDoubleValue(metrics, "precision", 0.0);
        double recall = getDoubleValue(metrics, "recall", 0.0);

        if (map50 >= 0.75 && precision >= 0.75 && recall >= 0.75) {
            return "\u4F18\u79C0";
        }
        if (map50 >= 0.60 && precision >= 0.60 && recall >= 0.60) {
            return "\u826F\u597D";
        }
        return "\u5F85\u4F18\u5316";
    }

    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        if (map == null || !map.containsKey(key)) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private void verifyCallbackSign(StandaloneCallbackRequest request) {
        try {
            if (!StringUtils.hasText(request.getSign())) {
                log.warn("Standalone callback sign verify failed, jobId={}, reason=missing_sign", request.getJobId());
                throw new RuntimeException("\u56DE\u8C03\u7F3A\u5C11\u7B7E\u540D");
            }
            if (!StringUtils.hasText(pythonIntegrationProperties.getCallbackSecret())) {
                log.error("Standalone callback sign verify failed, jobId={}, reason=missing_server_secret", request.getJobId());
                throw new RuntimeException("callbackSecret \u672A\u914D\u7F6E");
            }

            String json = writeCanonicalJson(buildCallbackPayloadForSign(request));
            String expectedSign = sha256Hex(json + pythonIntegrationProperties.getCallbackSecret());
            if (!expectedSign.equalsIgnoreCase(request.getSign())) {
                log.warn(
                        "Standalone callback sign verify failed, jobId={}, status={}, expectedSign={}, actualSign={}",
                        request.getJobId(),
                        request.getStatus(),
                        expectedSign,
                        request.getSign()
                );
                throw new RuntimeException("\u56DE\u8C03\u7B7E\u540D\u6821\u9A8C\u5931\u8D25");
            }

            log.info("Standalone callback sign verify passed, jobId={}, status={}", request.getJobId(), request.getStatus());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Standalone callback sign verify exception, jobId={}", request.getJobId(), ex);
            throw new RuntimeException("\u56DE\u8C03\u7B7E\u540D\u6821\u9A8C\u5F02\u5E38: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildCallbackPayloadForSign(StandaloneCallbackRequest request) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("errorMessage", request.getErrorMessage());
        payload.put("jobId", request.getJobId());
        payload.put("jobType", request.getJobType());
        payload.put("message", request.getMessage());
        payload.put("metrics", request.getMetrics());
        payload.put("progress", request.getProgress());
        payload.put("resultFile", request.getResultFile());
        payload.put("standaloneValidationId", request.getStandaloneValidationId());
        payload.put("status", request.getStatus());
        payload.put("workflowId", request.getWorkflowId());
        return payload;
    }

    private String writeCanonicalJson(Object value) {
        try {
            ObjectMapper mapper = objectMapper.copy();
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException("JSON serialize failed: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RuntimeException("JSON serialize failed: " + ex.getMessage(), ex);
        }
    }

    private void applyStandaloneResultFile(StandaloneValidation validation, Object rawResultFile) {
        if (rawResultFile == null) {
            return;
        }

        String filePath = extractStandaloneResultFilePath(rawResultFile);
        if (StringUtils.hasText(filePath)) {
            validation.setResultFilePath(filePath);
            return;
        }

        validation.setResultFilePath(writeJson(normalizeStandaloneResultFile(rawResultFile)));
    }

    private String extractStandaloneResultFilePath(Object rawResultFile) {
        Map<String, Object> payload = normalizeStandaloneResultFile(rawResultFile);
        Object filePath = payload.get("filePath");
        return filePath == null ? null : filePath.toString();
    }

    private Map<String, Object> normalizeStandaloneResultFile(Object rawResultFile) {
        if (rawResultFile == null) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(rawResultFile, Map.class);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new RuntimeException("SHA256 calculate failed: " + ex.getMessage(), ex);
        }
    }

    private StandaloneValidationVO entityToVO(StandaloneValidation entity) {
        ModelAsset modelAsset = entity.getModelAssetId() == null ? null : modelAssetMapper.selectById(entity.getModelAssetId());
        DatasetAsset datasetAsset = entity.getDatasetAssetId() == null ? null : datasetAssetMapper.selectById(entity.getDatasetAssetId());

        StandaloneValidationVO vo = new StandaloneValidationVO();
        vo.setId(entity.getId());
        vo.setValidationCode(entity.getValidationCode());
        vo.setValidationMode(VALIDATION_MODE_STANDALONE);
        vo.setUserId(entity.getUserId());
        vo.setModelAssetId(entity.getModelAssetId());
        vo.setModelAssetName(resolveAssetDisplayName(modelAsset == null ? null : modelAsset.getAssetName(), entity.getModelPath()));
        vo.setModelPath(entity.getModelPath());
        vo.setModelYoloVersion(modelAsset == null ? null : modelAsset.getYoloVersion());
        vo.setModelStatus(modelAsset == null ? null : modelAsset.getStatus());
        vo.setDatasetAssetId(entity.getDatasetAssetId());
        vo.setDatasetAssetName(resolveAssetDisplayName(datasetAsset == null ? null : datasetAsset.getAssetName(), entity.getDatasetPath()));
        vo.setDatasetPath(entity.getDatasetPath());
        vo.setDatasetStatus(datasetAsset == null ? null : datasetAsset.getStatus());
        vo.setDatasetSampleCount(datasetAsset == null ? null : datasetAsset.getSampleCount());
        vo.setDatasetImageCount(datasetAsset == null ? null : datasetAsset.getImageCount());
        vo.setAlgorithmType(entity.getAlgorithmType());
        vo.setInputMode(entity.getInputMode());
        vo.setRetainInput(entity.getRetainInput());
        vo.setInputCleanupStatus(entity.getInputCleanupStatus());
        vo.setInputCleanedAt(entity.getInputCleanedAt());
        vo.setStatus(entity.getStatus());
        vo.setProgress(entity.getProgress());
        vo.setPythonJobId(entity.getPythonJobId());
        vo.setMetricsJson(entity.getMetricsJson());
        vo.setResultFilePath(entity.getResultFilePath());
        vo.setQualityLabel(entity.getQualityLabel());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setFinishedAt(entity.getFinishedAt());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private String resolveAssetDisplayName(String assetName, String path) {
        if (StringUtils.hasText(assetName)) {
            return assetName;
        }
        if (!StringUtils.hasText(path)) {
            return null;
        }
        File file = new File(path);
        return file.getName();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int sanitizeRecentLimit(int limit) {
        if (limit <= 0) {
            return 10;
        }
        return Math.min(limit, 20);
    }

    private void cleanupTemporaryInputIfNeeded(StandaloneValidation validation, String scene) {
        if (validation == null) {
            return;
        }
        if (!INPUT_MODE_TEMP_UPLOAD.equalsIgnoreCase(defaultString(validation.getInputMode()))) {
            return;
        }
        if (validation.getRetainInput() != null && validation.getRetainInput() == 1) {
            return;
        }
        if (StringUtils.hasText(validation.getInputCleanupStatus())
                && INPUT_CLEANUP_COMPLETED.equalsIgnoreCase(validation.getInputCleanupStatus())) {
            return;
        }
        if (!StringUtils.hasText(validation.getInputRootPath())) {
            return;
        }
        temporaryValidationInputStorageService.cleanupInputRoot(Path.of(validation.getInputRootPath()), scene);
        validation.setInputCleanupStatus(INPUT_CLEANUP_COMPLETED);
        validation.setInputCleanedAt(LocalDateTime.now());
        validation.setUpdatedAt(LocalDateTime.now());
        validationMapper.updateById(validation);
        log.info(
                "Temporary standalone validation input cleaned: validationId={}, validationCode={}, inputRootPath={}, scene={}",
                validation.getId(),
                validation.getValidationCode(),
                validation.getInputRootPath(),
                scene
        );
    }

    private boolean isClientPathRegistry(ModelAsset modelAsset) {
        return modelAsset != null
                && AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY.equalsIgnoreCase(defaultString(modelAsset.getRecordMode()))
                && AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY.equalsIgnoreCase(defaultString(modelAsset.getSourceType()));
    }

    private boolean isClientPathRegistry(DatasetAsset datasetAsset) {
        return datasetAsset != null
                && AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY.equalsIgnoreCase(defaultString(datasetAsset.getRecordMode()))
                && AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY.equalsIgnoreCase(defaultString(datasetAsset.getSourceType()));
    }

    private boolean isFormalOrServerRegistryRecord(ModelAsset modelAsset) {
        if (modelAsset == null) {
            return false;
        }
        return AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(defaultString(modelAsset.getRecordMode()))
                || AssetSourceCatalog.SOURCE_SERVER_PATH_REGISTRY.equalsIgnoreCase(defaultString(modelAsset.getSourceType()));
    }

    private boolean isFormalOrServerRegistryRecord(DatasetAsset datasetAsset) {
        if (datasetAsset == null) {
            return false;
        }
        return AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(defaultString(datasetAsset.getRecordMode()))
                || AssetSourceCatalog.SOURCE_SERVER_PATH_REGISTRY.equalsIgnoreCase(defaultString(datasetAsset.getSourceType()));
    }
}
