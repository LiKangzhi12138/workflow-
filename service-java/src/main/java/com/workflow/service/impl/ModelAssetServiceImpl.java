package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.model.CreateModelAssetRequest;
import com.workflow.dto.model.ModelAssetDetailVO;
import com.workflow.dto.model.ModelAssetListItemVO;
import com.workflow.dto.model.UpdateModelAssetRequest;
import com.workflow.dto.model.UploadModelAssetRequest;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ModelAssetService;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelAssetServiceImpl implements ModelAssetService {

    private static final String ROLE_CLIENT = "CLIENT";
    private static final String ROLE_SERVER = "SERVER";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_REGISTERED = "REGISTERED";
    private static final String CHECK_STATUS_OK = "OK";
    private static final String CHECK_STATUS_FAILED = "FAILED";
    private static final String DEFAULT_MODEL_TYPE = "YOLO";
    private static final String DEFAULT_MODEL_VERSION = "YOLO10";
    private static final String DEFAULT_TASK_TYPE = "DETECTION";
    private static final String DEFAULT_YOLO_VERSION = "YOLOv10";

    private final ModelAssetMapper modelAssetMapper;
    private final WorkflowMapper workflowMapper;
    private final LoginUserContext loginUserContext;
    private final AssetStorageService assetStorageService;
    private final FederatedGlobalModelDeletionService federatedGlobalModelDeletionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createModelAsset(CreateModelAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertClientPathRegistryRole(currentUser);
        if (!StringUtils.hasText(request.getFilePath())) {
            throw new BusinessException("MODEL_PATH_REQUIRED", "请填写需要登记的模型路径。");
        }

        ModelAsset asset = new ModelAsset();
        asset.setAssetCode(generateAssetCode());
        asset.setAssetName(trimRequired(request.getAssetName(), "请填写模型名称。"));
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setModelType(defaultValue(request.getModelType(), DEFAULT_MODEL_TYPE));
        asset.setModelVersion(defaultValue(request.getModelVersion(), DEFAULT_MODEL_VERSION));
        asset.setTaskType(defaultValue(request.getTaskType(), DEFAULT_TASK_TYPE));
        asset.setYoloVersion(defaultValue(request.getYoloVersion(), DEFAULT_YOLO_VERSION));
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setDescription(request.getDescription());
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_PATH_REGISTRY);
        asset.setStatus(STATUS_REGISTERED);
        asset.setIsDeleted(0);
        applyRegistryPath(asset, request.getFilePath(), currentUser.getRoleCode(), false);
        modelAssetMapper.insert(asset);

        log.info(
                "Created model path registry entry: modelAssetId={}, ownerUserId={}, ownerRoleCode={}, recordMode={}, sourceType={}, sourcePath={}, resolvedFilePath={}, lastCheckStatus={}",
                asset.getId(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                asset.getRecordMode(),
                asset.getSourceType(),
                asset.getSourcePath(),
                asset.getFilePath(),
                asset.getLastCheckStatus()
        );
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadModelAsset(UploadModelAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        String assetCode = generateAssetCode();
        AssetStorageService.StoredFileAsset stored = assetStorageService.storeUploadedModel(assetCode, request.getFile());

        ModelAsset asset = new ModelAsset();
        asset.setAssetCode(assetCode);
        asset.setAssetName(request.getAssetName());
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setModelType(defaultValue(request.getModelType(), DEFAULT_MODEL_TYPE));
        asset.setModelVersion(defaultValue(request.getModelVersion(), DEFAULT_MODEL_VERSION));
        asset.setTaskType(defaultValue(request.getTaskType(), DEFAULT_TASK_TYPE));
        asset.setFileName(stored.getOriginalFilename());
        asset.setFilePath(stored.getStoredPath().toString());
        asset.setSourcePath(request.getFile().getOriginalFilename());
        asset.setSourceType(AssetSourceCatalog.SOURCE_BROWSER_UPLOAD);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_BROWSER_UPLOAD);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setFileSize(stored.getFileSize());
        asset.setFilePathValidated(1);
        asset.setLastCheckAt(LocalDateTime.now());
        asset.setLastCheckStatus(CHECK_STATUS_OK);
        asset.setLastCheckMessage("浏览器上传文件已保存到服务端正式存储。");
        asset.setYoloVersion(defaultValue(request.getYoloVersion(), DEFAULT_YOLO_VERSION));
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setStatus(STATUS_READY);
        asset.setDescription(request.getDescription());
        asset.setIsDeleted(0);
        modelAssetMapper.insert(asset);

        log.info(
                "Created browser-upload model asset: modelAssetId={}, assetCode={}, ownerUserId={}, ownerRoleCode={}, storedPath={}",
                asset.getId(),
                assetCode,
                currentUser.getId(),
                currentUser.getRoleCode(),
                asset.getFilePath()
        );
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importModelAssetFromServerPath(CreateModelAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertServerRole(currentUser);
        if (!StringUtils.hasText(request.getFilePath())) {
            throw new BusinessException("SERVER_MODEL_PATH_REQUIRED", "请填写服务器可访问的模型路径。");
        }

        String assetCode = generateAssetCode();
        AssetStorageService.StoredFileAsset stored = assetStorageService.importModelFromServerPath(assetCode, request.getFilePath());

        ModelAsset asset = new ModelAsset();
        asset.setAssetCode(assetCode);
        asset.setAssetName(request.getAssetName());
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setModelType(defaultValue(request.getModelType(), DEFAULT_MODEL_TYPE));
        asset.setModelVersion(defaultValue(request.getModelVersion(), DEFAULT_MODEL_VERSION));
        asset.setTaskType(defaultValue(request.getTaskType(), DEFAULT_TASK_TYPE));
        asset.setFileName(stored.getOriginalFilename());
        asset.setFilePath(stored.getStoredPath().toString());
        asset.setSourcePath(stored.getSourcePath() == null ? request.getFilePath() : stored.getSourcePath().toString());
        asset.setSourceType(AssetSourceCatalog.SOURCE_SERVER_IMPORT);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_SERVER_PATH);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setFileSize(stored.getFileSize());
        asset.setFilePathValidated(1);
        asset.setLastCheckAt(LocalDateTime.now());
        asset.setLastCheckStatus(CHECK_STATUS_OK);
        asset.setLastCheckMessage("服务器路径文件已复制到正式模型资产存储。");
        asset.setYoloVersion(defaultValue(request.getYoloVersion(), DEFAULT_YOLO_VERSION));
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setStatus(STATUS_READY);
        asset.setDescription(request.getDescription());
        asset.setIsDeleted(0);
        modelAssetMapper.insert(asset);

        log.info(
                "Imported formal model asset from server path: modelAssetId={}, assetCode={}, inputPath={}, storedPath={}",
                asset.getId(),
                assetCode,
                request.getFilePath(),
                asset.getFilePath()
        );
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long registerDecryptedServerModel(Workflow workflow, WorkflowModelUpload upload, Path decryptedFilePath) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow must not be null");
        }
        if (upload == null) {
            throw new IllegalArgumentException("upload must not be null");
        }
        if (decryptedFilePath == null || !Files.exists(decryptedFilePath) || !Files.isRegularFile(decryptedFilePath)) {
            throw new BusinessException("MODEL_ASSET_FILE_MISSING", "解密后的服务端模型文件不存在。");
        }

        try {
            ModelAsset asset = new ModelAsset();
            asset.setAssetCode(generateAssetCode());
            asset.setAssetName(resolveImportedServerAssetName(workflow, upload, decryptedFilePath));
            asset.setOwnerUserId(workflow.getServerUserId());
            asset.setOwnerRoleCode(ROLE_SERVER);
            asset.setModelType(DEFAULT_MODEL_TYPE);
            asset.setModelVersion(resolveModelVersion(workflow));
            asset.setTaskType(DEFAULT_TASK_TYPE);
            asset.setFileName(decryptedFilePath.getFileName().toString());
            asset.setFilePath(decryptedFilePath.toAbsolutePath().normalize().toString());
            asset.setSourcePath(upload.getEncryptedFilePath());
            asset.setSourceType(AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT);
            asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_WORKFLOW_RECEIVE);
            asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
            asset.setFileSize(Files.size(decryptedFilePath));
            asset.setFilePathValidated(1);
            asset.setLastCheckAt(LocalDateTime.now());
            asset.setLastCheckStatus(CHECK_STATUS_OK);
            asset.setLastCheckMessage("工作流接收并解密后的模型已纳入正式资产。");
            asset.setYoloVersion(resolveYoloVersion(workflow));
            asset.setIsPublic(0);
            asset.setStatus(STATUS_READY);
            asset.setDescription(buildImportedServerAssetDescription(workflow, upload));
            asset.setIsDeleted(0);
            modelAssetMapper.insert(asset);

            log.info(
                    "Registered decrypted workflow model asset: workflowId={}, uploadId={}, modelAssetId={}, storedPath={}",
                    workflow.getId(),
                    upload.getId(),
                    asset.getId(),
                    asset.getFilePath()
            );
            return asset.getId();
        } catch (IOException ex) {
            throw new BusinessException("MODEL_ASSET_REGISTER_FAILED", "解密模型纳管为正式资产失败。", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long registerFederatedServerModel(Workflow workflow, Path federatedModelPath, int sourceModelCount) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow must not be null");
        }
        if (federatedModelPath == null || !Files.exists(federatedModelPath) || !Files.isRegularFile(federatedModelPath)) {
            throw new BusinessException("MODEL_ASSET_FILE_MISSING", "联邦聚合输出模型文件不存在。");
        }

        try {
            ModelAsset asset = new ModelAsset();
            asset.setAssetCode(generateAssetCode());
            asset.setAssetName(resolveFederatedAssetName(workflow));
            asset.setOwnerUserId(workflow.getServerUserId());
            asset.setOwnerRoleCode(ROLE_SERVER);
            asset.setModelType(DEFAULT_MODEL_TYPE);
            asset.setModelVersion(resolveModelVersion(workflow));
            asset.setTaskType(DEFAULT_TASK_TYPE);
            asset.setFileName(federatedModelPath.getFileName().toString());
            asset.setFilePath(federatedModelPath.toAbsolutePath().normalize().toString());
            asset.setSourcePath(null);
            asset.setSourceType(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
            asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_FEDERATED_OUTPUT);
            asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
            asset.setFileSize(Files.size(federatedModelPath));
            asset.setFilePathValidated(1);
            asset.setLastCheckAt(LocalDateTime.now());
            asset.setLastCheckStatus(CHECK_STATUS_OK);
            asset.setLastCheckMessage("联邦聚合输出模型已纳入正式资产。");
            asset.setYoloVersion(resolveYoloVersion(workflow));
            asset.setIsPublic(0);
            asset.setStatus(STATUS_READY);
            asset.setDescription(buildFederatedAssetDescription(workflow, sourceModelCount));
            asset.setIsDeleted(0);
            modelAssetMapper.insert(asset);

            log.info(
                    "Registered federated output model asset: workflowId={}, modelAssetId={}, storedPath={}, sourceModelCount={}",
                    workflow.getId(),
                    asset.getId(),
                    asset.getFilePath(),
                    sourceModelCount
            );
            return asset.getId();
        } catch (IOException ex) {
            throw new BusinessException("MODEL_ASSET_REGISTER_FAILED", "联邦聚合模型纳管为正式资产失败。", ex);
        }
    }

    @Override
    public Page<ModelAssetListItemVO> pageModelAssets(long pageNum, long pageSize, String keyword, Boolean validated, String recordMode) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        Page<ModelAsset> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<ModelAsset> wrapper = new LambdaQueryWrapper<ModelAsset>()
                .eq(ModelAsset::getOwnerUserId, currentUser.getId())
                .eq(ModelAsset::getOwnerRoleCode, currentUser.getRoleCode())
                .eq(ModelAsset::getIsDeleted, 0)
                .orderByDesc(ModelAsset::getUpdatedAt)
                .orderByDesc(ModelAsset::getId);

        if (validated != null && validated) {
            wrapper.eq(ModelAsset::getFilePathValidated, 1);
        }
        if (StringUtils.hasText(recordMode)) {
            wrapper.eq(ModelAsset::getRecordMode, recordMode.trim());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(ModelAsset::getAssetName, keyword)
                    .or()
                    .like(ModelAsset::getModelVersion, keyword)
                    .or()
                    .like(ModelAsset::getModelType, keyword)
                    .or()
                    .like(ModelAsset::getFilePath, keyword)
                    .or()
                    .like(ModelAsset::getSourcePath, keyword));
        }

        Page<ModelAsset> entityPage = modelAssetMapper.selectPage(page, wrapper);
        Page<ModelAssetListItemVO> result = new Page<>(pageNum, pageSize);
        result.setTotal(entityPage.getTotal());
        result.setSize(entityPage.getSize());
        result.setCurrent(entityPage.getCurrent());
        result.setPages(entityPage.getPages());
        result.setRecords(entityPage.getRecords().stream().map(this::toListVO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public ModelAssetDetailVO getModelAssetDetail(Long id) {
        return toDetailVO(getOwnedAsset(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModelAsset(Long id, UpdateModelAssetRequest request) {
        ModelAsset asset = getOwnedAsset(id);
        boolean pathChanged = false;

        if (StringUtils.hasText(request.getAssetName())) {
            asset.setAssetName(request.getAssetName().trim());
        }
        if (StringUtils.hasText(request.getModelType())) {
            asset.setModelType(request.getModelType().trim());
        }
        if (StringUtils.hasText(request.getModelVersion())) {
            asset.setModelVersion(request.getModelVersion().trim());
        }
        if (StringUtils.hasText(request.getTaskType())) {
            asset.setTaskType(request.getTaskType().trim());
        }
        if (StringUtils.hasText(request.getYoloVersion())) {
            asset.setYoloVersion(request.getYoloVersion().trim());
        }
        if (request.getIsPublic() != null) {
            asset.setIsPublic(request.getIsPublic());
        }
        if (request.getDescription() != null) {
            asset.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())
                && AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equalsIgnoreCase(defaultValue(asset.getRecordMode(), ""))) {
            asset.setStatus(request.getStatus().trim());
        }

        if (isPathRegistry(asset) && StringUtils.hasText(request.getFilePath())) {
            applyRegistryPath(asset, request.getFilePath(), asset.getOwnerRoleCode(), false);
            pathChanged = true;
        } else if (!isPathRegistry(asset) && (StringUtils.hasText(request.getFilePath()) || StringUtils.hasText(request.getSourcePath()))) {
            log.warn(
                    "Ignoring attempt to modify formal model asset storage path: modelAssetId={}, requestedFilePath={}, requestedSourcePath={}",
                    id,
                    request.getFilePath(),
                    request.getSourcePath()
            );
        }

        if (!pathChanged && isPathRegistry(asset) && !StringUtils.hasText(asset.getStatus())) {
            asset.setStatus(STATUS_REGISTERED);
        }
        asset.setUpdatedAt(LocalDateTime.now());
        modelAssetMapper.updateById(asset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkModelAsset(Long id) {
        ModelAsset asset = getOwnedAsset(id);
        if (isPathRegistry(asset)) {
            applyRegistryPath(asset, defaultValue(asset.getSourcePath(), asset.getFilePath()), asset.getOwnerRoleCode(), true);
        } else {
            verifyFormalModelAsset(asset);
        }
        asset.setUpdatedAt(LocalDateTime.now());
        modelAssetMapper.updateById(asset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModelAsset(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        ModelAsset asset = getOwnedAsset(id);
        if (AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT.equalsIgnoreCase(asset.getSourceType())) {
            FederatedGlobalModelDeletionService.DeletionPlan deletionPlan =
                    federatedGlobalModelDeletionService.prepareDeletion(asset, currentUser);
            int updated = modelAssetMapper.softDeleteOwnedFederatedOutputAsset(asset.getId(), currentUser.getId());
            if (updated != 1) {
                throw new BusinessException("FEDERATED_MODEL_SOFT_DELETE_FAILED", "联邦全局模型删除失败，请刷新模型列表后重试。");
            }
            federatedGlobalModelDeletionService.schedulePhysicalDeletionAfterCommit(deletionPlan);
            return;
        }
        Long refCount = workflowMapper.selectCount(
                new LambdaQueryWrapper<Workflow>()
                        .eq(Workflow::getClientModelAssetId, id)
                        .eq(Workflow::getIsDeleted, 0)
        );
        if (refCount != null && refCount > 0) {
            throw new BusinessException("MODEL_ASSET_IN_USE", "该模型登记项或正式资产已被工作流引用，暂时不能删除。");
        }
        asset.setIsDeleted(1);
        asset.setUpdatedAt(LocalDateTime.now());
        modelAssetMapper.updateById(asset);
    }

    private void applyRegistryPath(ModelAsset asset, String rawPath, String roleCode, boolean raiseOnFailure) {
        String trimmedPath = trimRequired(rawPath, "请填写需要登记的模型路径。");
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_PATH_REGISTRY);
        asset.setStatus(STATUS_REGISTERED);
        asset.setSourcePath(trimmedPath);
        asset.setFilePath(trimmedPath);
        asset.setFileName(extractFileName(trimmedPath));
        asset.setFileSize(null);
        asset.setLastCheckAt(LocalDateTime.now());

        if (ROLE_SERVER.equalsIgnoreCase(roleCode)) {
            asset.setSourceType(AssetSourceCatalog.SOURCE_SERVER_PATH_REGISTRY);
            try {
                Path resolvedPath = assetStorageService.resolveAllowedServerImportPath(trimmedPath);
                if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
                    throw new BusinessException("SERVER_MODEL_PATH_NOT_FOUND", "服务器路径不可访问或不是模型文件：" + resolvedPath);
                }
                asset.setFilePath(resolvedPath.toString());
                asset.setFileName(resolvedPath.getFileName() == null ? extractFileName(trimmedPath) : resolvedPath.getFileName().toString());
                asset.setFileSize(Files.size(resolvedPath));
                asset.setFilePathValidated(1);
                asset.setLastCheckStatus(CHECK_STATUS_OK);
                asset.setLastCheckMessage("服务器路径检查通过，可直接用于服务端验证、导入或联邦学习。");
                asset.setStatus(STATUS_READY);
            } catch (Exception ex) {
                String message = resolveRegistryCheckMessage(ex, "服务器路径检查失败。");
                asset.setFilePath(trimmedPath);
                asset.setFilePathValidated(0);
                asset.setLastCheckStatus(CHECK_STATUS_FAILED);
                asset.setLastCheckMessage(message);
                asset.setStatus(STATUS_REGISTERED);
                if (raiseOnFailure) {
                    throw new BusinessException("MODEL_PATH_CHECK_FAILED", message, ex);
                }
            }
            return;
        }

        asset.setSourceType(AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY);
        String normalized = trimmedPath.replace('\\', '/');
        boolean supported = normalized.toLowerCase(Locale.ROOT).matches(".*\\.(pt|pth|weights|onnx|bin)$");
        asset.setFilePathValidated(supported ? 1 : 0);
        asset.setLastCheckStatus(supported ? CHECK_STATUS_OK : CHECK_STATUS_FAILED);
        asset.setLastCheckMessage(
                supported
                        ? "已完成路径格式检查。客户端路径登记项仅用于记录和快速发起，真正使用时仍需重新选择文件并临时上传。"
                        : "路径已登记，但文件扩展名未识别为支持的模型格式。"
        );
        if (raiseOnFailure && !supported) {
            throw new BusinessException("MODEL_PATH_CHECK_FAILED", asset.getLastCheckMessage());
        }
    }

    private void verifyFormalModelAsset(ModelAsset asset) {
        try {
            if (!StringUtils.hasText(asset.getFilePath())) {
                throw new BusinessException("MODEL_ASSET_PATH_MISSING", "正式模型资产缺少服务端存储路径。");
            }
            Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                throw new BusinessException("MODEL_ASSET_PATH_INVALID", "正式模型资产文件不存在：" + path);
            }
            asset.setFileSize(Files.size(path));
            asset.setFilePathValidated(1);
            asset.setLastCheckAt(LocalDateTime.now());
            asset.setLastCheckStatus(CHECK_STATUS_OK);
            asset.setLastCheckMessage("正式模型资产存储路径检查通过。");
            asset.setStatus(STATUS_READY);
        } catch (IOException ex) {
            throw new BusinessException("MODEL_ASSET_CHECK_FAILED", "正式模型资产校验失败。", ex);
        }
    }

    private boolean isPathRegistry(ModelAsset asset) {
        return asset != null
                && AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY.equalsIgnoreCase(defaultValue(asset.getRecordMode(), ""));
    }

    private void assertServerRole(SysUser currentUser) {
        if (currentUser == null || !ROLE_SERVER.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException("SERVER_ONLY", "只有服务器管理员可以导入正式模型资产。");
        }
    }

    private void assertClientPathRegistryRole(SysUser currentUser) {
        if (currentUser != null && ROLE_SERVER.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException(
                    "SERVER_MODEL_PATH_REGISTRY_UNSUPPORTED",
                    "服务端用户不支持本地模型路径登记，请使用模型上传或服务端路径导入。"
            );
        }
        if (currentUser == null || !ROLE_CLIENT.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException("CLIENT_ONLY", "只有客户端用户可以登记本地模型路径。");
        }
    }

    private ModelAsset getOwnedAsset(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        ModelAsset asset = modelAssetMapper.selectOne(
                new LambdaQueryWrapper<ModelAsset>()
                        .eq(ModelAsset::getId, id)
                        .eq(ModelAsset::getOwnerUserId, currentUser.getId())
                        .eq(ModelAsset::getOwnerRoleCode, currentUser.getRoleCode())
                        .eq(ModelAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (asset == null) {
            throw new BusinessException("MODEL_ASSET_NOT_FOUND", "未找到对应的模型登记项或模型资产。");
        }
        return asset;
    }

    private ModelAssetListItemVO toListVO(ModelAsset asset) {
        ModelAssetListItemVO vo = new ModelAssetListItemVO();
        vo.setId(asset.getId());
        vo.setAssetCode(asset.getAssetCode());
        vo.setAssetName(asset.getAssetName());
        vo.setModelType(asset.getModelType());
        vo.setModelVersion(asset.getModelVersion());
        vo.setTaskType(asset.getTaskType());
        vo.setFileName(asset.getFileName());
        vo.setFilePath(asset.getFilePath());
        vo.setSourcePath(asset.getSourcePath());
        vo.setFileSize(asset.getFileSize());
        vo.setFilePathValidated(asset.getFilePathValidated());
        vo.setSourceType(defaultValue(asset.getSourceType(), AssetSourceCatalog.SOURCE_LEGACY_PATH));
        vo.setImportMode(defaultValue(asset.getImportMode(), AssetSourceCatalog.IMPORT_MODE_LEGACY));
        vo.setRecordMode(defaultValue(asset.getRecordMode(), AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET));
        vo.setLastCheckAt(asset.getLastCheckAt());
        vo.setLastCheckStatus(asset.getLastCheckStatus());
        vo.setLastCheckMessage(asset.getLastCheckMessage());
        vo.setYoloVersion(asset.getYoloVersion());
        vo.setIsPublic(asset.getIsPublic());
        vo.setStatus(asset.getStatus());
        vo.setDescription(asset.getDescription());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setUpdatedAt(asset.getUpdatedAt());
        return vo;
    }

    private ModelAssetDetailVO toDetailVO(ModelAsset asset) {
        ModelAssetDetailVO vo = new ModelAssetDetailVO();
        vo.setId(asset.getId());
        vo.setAssetCode(asset.getAssetCode());
        vo.setAssetName(asset.getAssetName());
        vo.setOwnerUserId(asset.getOwnerUserId());
        vo.setOwnerRoleCode(asset.getOwnerRoleCode());
        vo.setModelType(asset.getModelType());
        vo.setModelVersion(asset.getModelVersion());
        vo.setTaskType(asset.getTaskType());
        vo.setFileName(asset.getFileName());
        vo.setFilePath(asset.getFilePath());
        vo.setSourcePath(asset.getSourcePath());
        vo.setFileSize(asset.getFileSize());
        vo.setFilePathValidated(asset.getFilePathValidated());
        vo.setSourceType(defaultValue(asset.getSourceType(), AssetSourceCatalog.SOURCE_LEGACY_PATH));
        vo.setImportMode(defaultValue(asset.getImportMode(), AssetSourceCatalog.IMPORT_MODE_LEGACY));
        vo.setRecordMode(defaultValue(asset.getRecordMode(), AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET));
        vo.setLastCheckAt(asset.getLastCheckAt());
        vo.setLastCheckStatus(asset.getLastCheckStatus());
        vo.setLastCheckMessage(asset.getLastCheckMessage());
        vo.setYoloVersion(asset.getYoloVersion());
        vo.setIsPublic(asset.getIsPublic());
        vo.setStatus(asset.getStatus());
        vo.setDescription(asset.getDescription());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setUpdatedAt(asset.getUpdatedAt());
        return vo;
    }

    private String resolveImportedServerAssetName(Workflow workflow, WorkflowModelUpload upload, Path decryptedFilePath) {
        String baseName = StringUtils.hasText(upload.getOriginalFilename())
                ? upload.getOriginalFilename()
                : (StringUtils.hasText(workflow.getWorkflowName())
                ? workflow.getWorkflowName()
                : decryptedFilePath.getFileName().toString());
        return limitLength("工作流模型_" + baseName, 128);
    }

    private String buildImportedServerAssetDescription(Workflow workflow, WorkflowModelUpload upload) {
        String workflowCode = StringUtils.hasText(workflow.getWorkflowCode()) ? workflow.getWorkflowCode() : "WF" + workflow.getId();
        return "工作流 " + workflowCode + " 接收并解密后的正式模型资产，uploadId=" + upload.getId();
    }

    private String resolveFederatedAssetName(Workflow workflow) {
        String workflowCode = StringUtils.hasText(workflow.getWorkflowCode()) ? workflow.getWorkflowCode() : "WF" + workflow.getId();
        return limitLength(workflowCode + "_global_model", 128);
    }

    private String buildFederatedAssetDescription(Workflow workflow, int sourceModelCount) {
        String workflowCode = StringUtils.hasText(workflow.getWorkflowCode()) ? workflow.getWorkflowCode() : "WF" + workflow.getId();
        return "联邦聚合输出模型，workflow=" + workflowCode + ", strategy=FEDML, sourceModelCount=" + sourceModelCount;
    }

    private String resolveModelVersion(Workflow workflow) {
        String yoloVersion = resolveYoloVersion(workflow);
        return StringUtils.hasText(yoloVersion)
                ? yoloVersion.replace("YOLOv", "YOLO").replace("yolov", "YOLO")
                : DEFAULT_MODEL_VERSION;
    }

    private String resolveYoloVersion(Workflow workflow) {
        return StringUtils.hasText(workflow.getYoloVersion()) ? workflow.getYoloVersion() : DEFAULT_YOLO_VERSION;
    }

    private String resolveRegistryCheckMessage(Exception ex, String fallback) {
        if (ex instanceof BusinessException) {
            BusinessException businessException = (BusinessException) ex;
            if (StringUtils.hasText(businessException.getMessage())) {
                return businessException.getMessage();
            }
        }
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return fallback;
    }

    private String extractFileName(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return null;
        }
        String normalized = rawPath.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String generateAssetCode() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = new Random().nextInt(9000) + 1000;
        return "MA" + time + random;
    }

    private String defaultValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trimRequired(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("PARAM_REQUIRED", message);
        }
        return value.trim();
    }

    private String limitLength(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
