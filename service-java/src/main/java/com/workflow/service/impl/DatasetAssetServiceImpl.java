package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.dataset.CreateDatasetAssetRequest;
import com.workflow.dto.dataset.DatasetAssetDetailVO;
import com.workflow.dto.dataset.DatasetAssetListItemVO;
import com.workflow.dto.dataset.UpdateDatasetAssetRequest;
import com.workflow.dto.dataset.UploadDatasetAssetRequest;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.DatasetAssetService;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
public class DatasetAssetServiceImpl implements DatasetAssetService {

    private static final String ROLE_CLIENT = "CLIENT";
    private static final String ROLE_SERVER = "SERVER";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_REGISTERED = "REGISTERED";
    private static final String CHECK_STATUS_OK = "OK";
    private static final String CHECK_STATUS_FAILED = "FAILED";
    private static final String DEFAULT_DATA_FORMAT = "YOLO";
    private static final String DEFAULT_TASK_TYPE = "DETECTION";

    private final DatasetAssetMapper datasetAssetMapper;
    private final WorkflowMapper workflowMapper;
    private final LoginUserContext loginUserContext;
    private final AssetStorageService assetStorageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDatasetAsset(CreateDatasetAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertClientPathRegistryRole(currentUser);
        if (!StringUtils.hasText(request.getFilePath())) {
            throw new BusinessException("DATASET_PATH_REQUIRED", "请填写需要登记的数据集路径。");
        }

        DatasetAsset asset = new DatasetAsset();
        asset.setAssetCode(generateAssetCode());
        asset.setAssetName(trimRequired(request.getAssetName(), "请填写数据集名称。"));
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setDatasetType(defaultDatasetType(request.getDatasetType()));
        asset.setDataFormat(DEFAULT_DATA_FORMAT);
        asset.setTaskType(DEFAULT_TASK_TYPE);
        asset.setSampleCount(request.getSampleCount());
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setDescription(request.getDescription());
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_PATH_REGISTRY);
        asset.setStatus(STATUS_REGISTERED);
        asset.setIsDeleted(0);
        applyRegistryPath(asset, request.getFilePath(), currentUser.getRoleCode(), false);
        datasetAssetMapper.insert(asset);

        log.info(
                "Created dataset path registry entry: datasetAssetId={}, ownerUserId={}, ownerRoleCode={}, sourceType={}, sourcePath={}, resolvedFilePath={}, lastCheckStatus={}",
                asset.getId(),
                currentUser.getId(),
                currentUser.getRoleCode(),
                asset.getSourceType(),
                asset.getSourcePath(),
                asset.getFilePath(),
                asset.getLastCheckStatus()
        );
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadDatasetAsset(UploadDatasetAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        String assetCode = generateAssetCode();
        AssetStorageService.StoredDatasetAsset stored = assetStorageService.storeUploadedDataset(assetCode, request.getFile());

        DatasetAsset asset = new DatasetAsset();
        asset.setAssetCode(assetCode);
        asset.setAssetName(request.getAssetName());
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setDatasetType(defaultDatasetType(request.getDatasetType()));
        asset.setDataFormat(DEFAULT_DATA_FORMAT);
        asset.setTaskType(DEFAULT_TASK_TYPE);
        asset.setSampleCount(request.getSampleCount());
        asset.setFileName(stored.getOriginalFilename());
        asset.setFilePath(stored.getStoredPath().toString());
        asset.setSourcePath(request.getFile().getOriginalFilename());
        asset.setSourceType(AssetSourceCatalog.SOURCE_BROWSER_UPLOAD);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_BROWSER_UPLOAD);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setFileSize(stored.getFileSize());
        asset.setImageCount(stored.getImageCount());
        asset.setFilePathValidated(1);
        asset.setLastCheckAt(LocalDateTime.now());
        asset.setLastCheckStatus(CHECK_STATUS_OK);
        asset.setLastCheckMessage("浏览器上传数据集已保存到服务端正式存储。");
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setStatus(STATUS_READY);
        asset.setDescription(request.getDescription());
        asset.setIsDeleted(0);
        datasetAssetMapper.insert(asset);

        log.info(
                "Created browser-upload dataset asset: datasetAssetId={}, assetCode={}, storedPath={}, imageCount={}",
                asset.getId(),
                assetCode,
                asset.getFilePath(),
                asset.getImageCount()
        );
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importDatasetAssetFromServerPath(CreateDatasetAssetRequest request) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        assertServerRole(currentUser);
        if (!StringUtils.hasText(request.getFilePath())) {
            throw new BusinessException("SERVER_DATASET_PATH_REQUIRED", "请填写服务器可访问的数据集路径。");
        }

        String assetCode = generateAssetCode();
        AssetStorageService.StoredDatasetAsset stored = assetStorageService.importDatasetFromServerPath(assetCode, request.getFilePath());

        DatasetAsset asset = new DatasetAsset();
        asset.setAssetCode(assetCode);
        asset.setAssetName(request.getAssetName());
        asset.setOwnerUserId(currentUser.getId());
        asset.setOwnerRoleCode(currentUser.getRoleCode());
        asset.setDatasetType(defaultDatasetType(request.getDatasetType()));
        asset.setDataFormat(DEFAULT_DATA_FORMAT);
        asset.setTaskType(DEFAULT_TASK_TYPE);
        asset.setSampleCount(request.getSampleCount());
        asset.setFileName(stored.getOriginalFilename());
        asset.setFilePath(stored.getStoredPath().toString());
        asset.setSourcePath(stored.getSourcePath() == null ? request.getFilePath() : stored.getSourcePath().toString());
        asset.setSourceType(AssetSourceCatalog.SOURCE_SERVER_IMPORT);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_SERVER_PATH);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setFileSize(stored.getFileSize());
        asset.setImageCount(stored.getImageCount());
        asset.setFilePathValidated(1);
        asset.setLastCheckAt(LocalDateTime.now());
        asset.setLastCheckStatus(CHECK_STATUS_OK);
        asset.setLastCheckMessage("服务器路径数据集已复制到正式数据集资产存储。");
        asset.setIsPublic(request.getIsPublic() == null ? 0 : request.getIsPublic());
        asset.setStatus(STATUS_READY);
        asset.setDescription(request.getDescription());
        asset.setIsDeleted(0);
        datasetAssetMapper.insert(asset);

        log.info(
                "Imported formal dataset asset from server path: datasetAssetId={}, assetCode={}, inputPath={}, storedPath={}, imageCount={}",
                asset.getId(),
                assetCode,
                request.getFilePath(),
                asset.getFilePath(),
                asset.getImageCount()
        );
        return asset.getId();
    }

    @Override
    public Page<DatasetAssetListItemVO> pageDatasetAssets(long pageNum, long pageSize, String keyword, Boolean validated, String recordMode) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        Page<DatasetAsset> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<DatasetAsset> wrapper = new LambdaQueryWrapper<DatasetAsset>()
                .eq(DatasetAsset::getOwnerUserId, currentUser.getId())
                .eq(DatasetAsset::getOwnerRoleCode, currentUser.getRoleCode())
                .eq(DatasetAsset::getIsDeleted, 0)
                .orderByDesc(DatasetAsset::getUpdatedAt)
                .orderByDesc(DatasetAsset::getId);

        if (validated != null && validated) {
            wrapper.eq(DatasetAsset::getFilePathValidated, 1);
        }
        if (StringUtils.hasText(recordMode)) {
            wrapper.eq(DatasetAsset::getRecordMode, recordMode.trim());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(DatasetAsset::getAssetName, keyword)
                    .or()
                    .like(DatasetAsset::getDatasetType, keyword)
                    .or()
                    .like(DatasetAsset::getFilePath, keyword)
                    .or()
                    .like(DatasetAsset::getSourcePath, keyword));
        }

        Page<DatasetAsset> entityPage = datasetAssetMapper.selectPage(page, wrapper);
        Page<DatasetAssetListItemVO> result = new Page<>(pageNum, pageSize);
        result.setTotal(entityPage.getTotal());
        result.setSize(entityPage.getSize());
        result.setCurrent(entityPage.getCurrent());
        result.setPages(entityPage.getPages());
        result.setRecords(entityPage.getRecords().stream().map(this::toListVO).collect(Collectors.toList()));
        return result;
    }

    @Override
    public DatasetAssetDetailVO getDatasetAssetDetail(Long id) {
        return toDetailVO(getOwnedAsset(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDatasetAsset(Long id, UpdateDatasetAssetRequest request) {
        DatasetAsset asset = getOwnedAsset(id);

        if (StringUtils.hasText(request.getAssetName())) {
            asset.setAssetName(request.getAssetName().trim());
        }
        if (StringUtils.hasText(request.getDatasetType())) {
            asset.setDatasetType(defaultDatasetType(request.getDatasetType()));
        }
        if (request.getSampleCount() != null) {
            asset.setSampleCount(request.getSampleCount());
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
        } else if (!isPathRegistry(asset) && (StringUtils.hasText(request.getFilePath()) || StringUtils.hasText(request.getSourcePath()))) {
            log.warn(
                    "Ignoring attempt to modify formal dataset asset storage path: datasetAssetId={}, requestedFilePath={}, requestedSourcePath={}",
                    id,
                    request.getFilePath(),
                    request.getSourcePath()
            );
        }

        asset.setUpdatedAt(LocalDateTime.now());
        datasetAssetMapper.updateById(asset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkDatasetAsset(Long id) {
        DatasetAsset asset = getOwnedAsset(id);
        if (isPathRegistry(asset)) {
            applyRegistryPath(asset, defaultValue(asset.getSourcePath(), asset.getFilePath()), asset.getOwnerRoleCode(), true);
        } else {
            verifyFormalDatasetAsset(asset);
        }
        asset.setUpdatedAt(LocalDateTime.now());
        datasetAssetMapper.updateById(asset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDatasetAsset(Long id) {
        DatasetAsset asset = getOwnedAsset(id);
        Long refCount = workflowMapper.selectCount(
                new LambdaQueryWrapper<Workflow>()
                        .eq(Workflow::getServerDatasetAssetId, id)
                        .eq(Workflow::getIsDeleted, 0)
        );
        if (refCount != null && refCount > 0) {
            throw new BusinessException("DATASET_ASSET_IN_USE", "该数据集登记项或正式资产已被工作流引用，暂时不能删除。");
        }
        asset.setIsDeleted(1);
        asset.setUpdatedAt(LocalDateTime.now());
        datasetAssetMapper.updateById(asset);
    }

    private void applyRegistryPath(DatasetAsset asset, String rawPath, String roleCode, boolean raiseOnFailure) {
        String trimmedPath = trimRequired(rawPath, "请填写需要登记的数据集路径。");
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_PATH_REGISTRY);
        asset.setStatus(STATUS_REGISTERED);
        asset.setSourcePath(trimmedPath);
        asset.setFilePath(trimmedPath);
        asset.setFileName(extractFileName(trimmedPath));
        asset.setFileSize(null);
        asset.setImageCount(null);
        asset.setLastCheckAt(LocalDateTime.now());

        if (ROLE_SERVER.equalsIgnoreCase(roleCode)) {
            asset.setSourceType(AssetSourceCatalog.SOURCE_SERVER_PATH_REGISTRY);
            try {
                Path resolvedPath = assetStorageService.resolveAllowedServerImportPath(trimmedPath);
                if (!Files.exists(resolvedPath)) {
                    throw new BusinessException("SERVER_DATASET_PATH_NOT_FOUND", "服务器路径不可访问：" + resolvedPath);
                }
                asset.setFilePath(resolvedPath.toString());
                asset.setFileName(resolvedPath.getFileName() == null ? extractFileName(trimmedPath) : resolvedPath.getFileName().toString());
                if (Files.isDirectory(resolvedPath)) {
                    asset.setFileSize(null);
                } else {
                    asset.setFileSize(Files.size(resolvedPath));
                }
                DatasetPathCheckInfo checkInfo = inspectServerDatasetPath(resolvedPath);
                asset.setImageCount(checkInfo.imageCount());
                asset.setFilePathValidated(1);
                asset.setLastCheckStatus(CHECK_STATUS_OK);
                asset.setLastCheckMessage(checkInfo.message());
                asset.setStatus(STATUS_READY);
            } catch (Exception ex) {
                String message = resolveRegistryCheckMessage(ex, "服务器数据集路径检查失败。");
                asset.setFilePath(trimmedPath);
                asset.setFilePathValidated(0);
                asset.setLastCheckStatus(CHECK_STATUS_FAILED);
                asset.setLastCheckMessage(message);
                asset.setStatus(STATUS_REGISTERED);
                if (raiseOnFailure) {
                    throw new BusinessException("DATASET_PATH_CHECK_FAILED", message, ex);
                }
            }
            return;
        }

        asset.setSourceType(AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY);
        String normalized = trimmedPath.toLowerCase(Locale.ROOT);
        boolean looksReasonable = normalized.endsWith(".zip") || normalized.contains("/") || normalized.contains("\\");
        asset.setFilePathValidated(looksReasonable ? 1 : 0);
        asset.setLastCheckStatus(looksReasonable ? CHECK_STATUS_OK : CHECK_STATUS_FAILED);
        asset.setLastCheckMessage(
                looksReasonable
                        ? "已完成路径格式检查。客户端数据集登记项仅用于记录和快速发起，真正验证时仍需重新选择 zip 压缩包并临时上传。"
                        : "路径已登记，但未识别为常见数据集目录或 zip 压缩包。"
        );
        if (raiseOnFailure && !looksReasonable) {
            throw new BusinessException("DATASET_PATH_CHECK_FAILED", asset.getLastCheckMessage());
        }
    }

    private DatasetPathCheckInfo inspectServerDatasetPath(Path resolvedPath) {
        try {
            if (Files.isDirectory(resolvedPath)) {
                int imageCount = countImages(resolvedPath);
                if (imageCount <= 0) {
                    throw new BusinessException("SERVER_DATASET_EMPTY", "服务器数据集目录中未发现图片文件。");
                }
                return new DatasetPathCheckInfo(imageCount, "服务器目录检查通过，可直接用于服务端验证、导入或联邦学习。");
            }
            String filename = resolvedPath.getFileName() == null ? "" : resolvedPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!filename.endsWith(".zip")) {
                throw new BusinessException("SERVER_DATASET_TYPE_INVALID", "服务器数据集文件必须是 zip 压缩包或目录。");
            }
            return new DatasetPathCheckInfo(null, "服务器 zip 数据集路径检查通过，可用于正式导入。");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("SERVER_DATASET_CHECK_FAILED", "服务器数据集路径检查失败。", ex);
        }
    }

    private int countImages(Path rootPath) throws Exception {
        try (var stream = Files.walk(rootPath)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jpg")
                                || name.endsWith(".jpeg")
                                || name.endsWith(".png")
                                || name.endsWith(".bmp")
                                || name.endsWith(".webp");
                    })
                    .count();
        }
    }

    private void verifyFormalDatasetAsset(DatasetAsset asset) {
        try {
            if (!StringUtils.hasText(asset.getFilePath())) {
                throw new BusinessException("DATASET_ASSET_PATH_MISSING", "正式数据集资产缺少服务端存储路径。");
            }
            Path path = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new BusinessException("DATASET_ASSET_PATH_INVALID", "正式数据集资产路径不存在：" + path);
            }
            asset.setFilePathValidated(1);
            asset.setLastCheckAt(LocalDateTime.now());
            asset.setLastCheckStatus(CHECK_STATUS_OK);
            asset.setLastCheckMessage("正式数据集资产存储路径检查通过。");
            asset.setStatus(STATUS_READY);
            if (Files.isDirectory(path)) {
                asset.setImageCount(countImages(path));
            } else {
                asset.setFileSize(Files.size(path));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("DATASET_ASSET_CHECK_FAILED", "正式数据集资产校验失败。", ex);
        }
    }

    private boolean isPathRegistry(DatasetAsset asset) {
        return asset != null
                && AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY.equalsIgnoreCase(defaultValue(asset.getRecordMode(), ""));
    }

    private void assertServerRole(SysUser currentUser) {
        if (currentUser == null || !ROLE_SERVER.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException("SERVER_ONLY", "只有服务器管理员可以导入正式数据集资产。");
        }
    }

    private void assertClientPathRegistryRole(SysUser currentUser) {
        if (currentUser != null && ROLE_SERVER.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException(
                    "SERVER_DATASET_PATH_REGISTRY_UNSUPPORTED",
                    "服务端用户不支持本地数据集路径登记，请使用数据集上传或服务端路径导入。"
            );
        }
        if (currentUser == null || !ROLE_CLIENT.equalsIgnoreCase(currentUser.getRoleCode())) {
            throw new BusinessException("CLIENT_ONLY", "只有客户端用户可以登记本地数据集路径。");
        }
    }

    private DatasetAsset getOwnedAsset(Long id) {
        SysUser currentUser = loginUserContext.getCurrentUser();
        DatasetAsset asset = datasetAssetMapper.selectOne(
                new LambdaQueryWrapper<DatasetAsset>()
                        .eq(DatasetAsset::getId, id)
                        .eq(DatasetAsset::getOwnerUserId, currentUser.getId())
                        .eq(DatasetAsset::getOwnerRoleCode, currentUser.getRoleCode())
                        .eq(DatasetAsset::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (asset == null) {
            throw new BusinessException("DATASET_ASSET_NOT_FOUND", "未找到对应的数据集登记项或数据集资产。");
        }
        return asset;
    }

    private DatasetAssetListItemVO toListVO(DatasetAsset asset) {
        DatasetAssetListItemVO vo = new DatasetAssetListItemVO();
        vo.setId(asset.getId());
        vo.setAssetCode(asset.getAssetCode());
        vo.setAssetName(asset.getAssetName());
        vo.setDatasetType(asset.getDatasetType());
        vo.setDataFormat(asset.getDataFormat());
        vo.setTaskType(asset.getTaskType());
        vo.setSampleCount(asset.getSampleCount());
        vo.setFileName(asset.getFileName());
        vo.setFilePath(asset.getFilePath());
        vo.setSourcePath(asset.getSourcePath());
        vo.setFileSize(asset.getFileSize());
        vo.setImageCount(asset.getImageCount());
        vo.setFilePathValidated(asset.getFilePathValidated());
        vo.setSourceType(defaultValue(asset.getSourceType(), AssetSourceCatalog.SOURCE_LEGACY_PATH));
        vo.setImportMode(defaultValue(asset.getImportMode(), AssetSourceCatalog.IMPORT_MODE_LEGACY));
        vo.setRecordMode(defaultValue(asset.getRecordMode(), AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET));
        vo.setLastCheckAt(asset.getLastCheckAt());
        vo.setLastCheckStatus(asset.getLastCheckStatus());
        vo.setLastCheckMessage(asset.getLastCheckMessage());
        vo.setIsPublic(asset.getIsPublic());
        vo.setStatus(asset.getStatus());
        vo.setDescription(asset.getDescription());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setUpdatedAt(asset.getUpdatedAt());
        return vo;
    }

    private DatasetAssetDetailVO toDetailVO(DatasetAsset asset) {
        DatasetAssetDetailVO vo = new DatasetAssetDetailVO();
        vo.setId(asset.getId());
        vo.setAssetCode(asset.getAssetCode());
        vo.setAssetName(asset.getAssetName());
        vo.setOwnerUserId(asset.getOwnerUserId());
        vo.setOwnerRoleCode(asset.getOwnerRoleCode());
        vo.setDatasetType(asset.getDatasetType());
        vo.setDataFormat(asset.getDataFormat());
        vo.setTaskType(asset.getTaskType());
        vo.setSampleCount(asset.getSampleCount());
        vo.setFileName(asset.getFileName());
        vo.setFilePath(asset.getFilePath());
        vo.setSourcePath(asset.getSourcePath());
        vo.setFileSize(asset.getFileSize());
        vo.setImageCount(asset.getImageCount());
        vo.setFilePathValidated(asset.getFilePathValidated());
        vo.setSourceType(defaultValue(asset.getSourceType(), AssetSourceCatalog.SOURCE_LEGACY_PATH));
        vo.setImportMode(defaultValue(asset.getImportMode(), AssetSourceCatalog.IMPORT_MODE_LEGACY));
        vo.setRecordMode(defaultValue(asset.getRecordMode(), AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET));
        vo.setLastCheckAt(asset.getLastCheckAt());
        vo.setLastCheckStatus(asset.getLastCheckStatus());
        vo.setLastCheckMessage(asset.getLastCheckMessage());
        vo.setIsPublic(asset.getIsPublic());
        vo.setStatus(asset.getStatus());
        vo.setDescription(asset.getDescription());
        vo.setCreatedAt(asset.getCreatedAt());
        vo.setUpdatedAt(asset.getUpdatedAt());
        return vo;
    }

    private String defaultDatasetType(String datasetType) {
        String resolved = StringUtils.hasText(datasetType) ? datasetType.trim().toUpperCase(Locale.ROOT) : "GENERAL";
        if ("CROP".equals(resolved) || "LIVESTOCK".equals(resolved) || "GENERAL".equals(resolved)) {
            return resolved;
        }
        throw new BusinessException("DATASET_TYPE_INVALID", "数据集类型只支持 CROP、LIVESTOCK 或 GENERAL。");
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
        return "DA" + time + random;
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

    private record DatasetPathCheckInfo(Integer imageCount, String message) {
    }
}
