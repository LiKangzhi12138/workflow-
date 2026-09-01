package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.exception.BusinessException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssetStorageService {

    private static final Set<String> MODEL_EXTENSIONS = Set.of(".pt", ".pth", ".weights", ".onnx", ".bin");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp", ".webp");

    private final WorkflowStorageProperties workflowStorageProperties;

    public StoredFileAsset storeUploadedModel(String assetCode, MultipartFile file) {
        validateMultipartFile(file, "模型文件");
        String originalFilename = sanitizeFilename(file.getOriginalFilename(), "model.pt");
        validateModelFilename(originalFilename);

        try {
            Path assetDir = workflowStorageProperties.modelRootDirPath().resolve(assetCode).toAbsolutePath().normalize();
            Files.createDirectories(assetDir);
            Path targetPath = assetDir.resolve(originalFilename).toAbsolutePath().normalize();
            file.transferTo(targetPath);

            StoredFileAsset result = new StoredFileAsset();
            result.setStoredPath(targetPath);
            result.setOriginalFilename(originalFilename);
            result.setFileSize(Files.size(targetPath));
            log.info(
                    "Stored uploaded model asset file: assetCode={}, originalFilename={}, storedPath={}, fileSize={}",
                    assetCode,
                    originalFilename,
                    targetPath,
                    result.getFileSize()
            );
            return result;
        } catch (IOException ex) {
            throw new BusinessException("MODEL_UPLOAD_FAILED", "模型文件上传到服务端存储失败", ex);
        }
    }

    public StoredFileAsset importModelFromServerPath(String assetCode, String rawPath) {
        Path sourcePath = resolveAllowedServerImportPath(rawPath);
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException("SERVER_PATH_NOT_FOUND", "服务器路径不可访问或不是文件: " + sourcePath);
        }
        validateModelFilename(sourcePath.getFileName().toString());

        try {
            Path assetDir = workflowStorageProperties.modelRootDirPath().resolve(assetCode).toAbsolutePath().normalize();
            Files.createDirectories(assetDir);
            Path targetPath = assetDir.resolve(sanitizeFilename(sourcePath.getFileName().toString(), "model.pt"))
                    .toAbsolutePath()
                    .normalize();
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            StoredFileAsset result = new StoredFileAsset();
            result.setStoredPath(targetPath);
            result.setSourcePath(sourcePath);
            result.setOriginalFilename(sourcePath.getFileName().toString());
            result.setFileSize(Files.size(targetPath));
            log.info(
                    "Imported model from server path into managed storage: assetCode={}, sourcePath={}, storedPath={}, fileSize={}",
                    assetCode,
                    sourcePath,
                    targetPath,
                    result.getFileSize()
            );
            return result;
        } catch (IOException ex) {
            throw new BusinessException("SERVER_PATH_IMPORT_FAILED", "服务器模型导入失败", ex);
        }
    }

    public StoredDatasetAsset storeUploadedDataset(String assetCode, MultipartFile file) {
        validateMultipartFile(file, "数据集文件");
        String originalFilename = sanitizeFilename(file.getOriginalFilename(), "dataset.zip");
        if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException("DATASET_ARCHIVE_REQUIRED", "浏览器上传数据集时请上传 zip 压缩包");
        }

        Path assetRoot = workflowStorageProperties.datasetRootDirPath().resolve(assetCode).toAbsolutePath().normalize();
        Path uploadDir = assetRoot.resolve("upload").toAbsolutePath().normalize();
        Path contentDir = assetRoot.resolve("content").toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
            Files.createDirectories(contentDir);
            Path archivePath = uploadDir.resolve(originalFilename).toAbsolutePath().normalize();
            file.transferTo(archivePath);
            unzipSafely(archivePath, contentDir);

            Path datasetRoot = detectDatasetRoot(contentDir);
            int imageCount = countImages(datasetRoot);
            if (imageCount <= 0) {
                throw new BusinessException("DATASET_IMAGES_MISSING", "上传的数据集压缩包中未找到可用图片文件");
            }

            StoredDatasetAsset result = new StoredDatasetAsset();
            result.setStoredPath(datasetRoot);
            result.setArchivePath(archivePath);
            result.setOriginalFilename(originalFilename);
            result.setFileSize(Files.size(archivePath));
            result.setImageCount(imageCount);
            log.info(
                    "Stored uploaded dataset asset: assetCode={}, archivePath={}, datasetRoot={}, imageCount={}, fileSize={}",
                    assetCode,
                    archivePath,
                    datasetRoot,
                    imageCount,
                    result.getFileSize()
            );
            return result;
        } catch (BusinessException ex) {
            cleanupQuietly(assetRoot);
            throw ex;
        } catch (IOException ex) {
            cleanupQuietly(assetRoot);
            throw new BusinessException("DATASET_UPLOAD_FAILED", "数据集上传到服务端存储失败", ex);
        }
    }

    public StoredDatasetAsset importDatasetFromServerPath(String assetCode, String rawPath) {
        Path sourcePath = resolveAllowedServerImportPath(rawPath);
        if (!Files.exists(sourcePath)) {
            throw new BusinessException("SERVER_PATH_NOT_FOUND", "服务器路径不可访问: " + sourcePath);
        }

        Path assetRoot = workflowStorageProperties.datasetRootDirPath().resolve(assetCode).toAbsolutePath().normalize();
        Path uploadDir = assetRoot.resolve("import").toAbsolutePath().normalize();
        Path contentDir = assetRoot.resolve("content").toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadDir);
            Files.createDirectories(contentDir);

            Path datasetRoot;
            String originalFilename;
            long fileSize = 0L;
            Path archivePath = null;

            if (Files.isDirectory(sourcePath)) {
                Path targetDir = contentDir.resolve(sanitizeFilename(sourcePath.getFileName() == null ? assetCode : sourcePath.getFileName().toString(), assetCode))
                        .toAbsolutePath()
                        .normalize();
                copyDirectory(sourcePath, targetDir);
                datasetRoot = detectDatasetRoot(contentDir);
                originalFilename = sourcePath.getFileName() == null ? assetCode : sourcePath.getFileName().toString();
            } else if (Files.isRegularFile(sourcePath) && sourcePath.getFileName() != null
                    && sourcePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                originalFilename = sourcePath.getFileName().toString();
                archivePath = uploadDir.resolve(sanitizeFilename(originalFilename, "dataset.zip")).toAbsolutePath().normalize();
                Files.copy(sourcePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
                unzipSafely(archivePath, contentDir);
                datasetRoot = detectDatasetRoot(contentDir);
                fileSize = Files.size(archivePath);
            } else {
                throw new BusinessException("SERVER_DATASET_PATH_INVALID", "服务器路径导入数据集时，路径必须是目录或 zip 压缩包");
            }

            int imageCount = countImages(datasetRoot);
            if (imageCount <= 0) {
                throw new BusinessException("DATASET_IMAGES_MISSING", "服务器路径导入的数据集中未找到可用图片文件");
            }
            if (fileSize <= 0L && Files.exists(sourcePath) && Files.isRegularFile(sourcePath)) {
                fileSize = Files.size(sourcePath);
            }

            StoredDatasetAsset result = new StoredDatasetAsset();
            result.setStoredPath(datasetRoot);
            result.setSourcePath(sourcePath);
            result.setArchivePath(archivePath);
            result.setOriginalFilename(originalFilename);
            result.setFileSize(fileSize);
            result.setImageCount(imageCount);
            log.info(
                    "Imported dataset from server path into managed storage: assetCode={}, sourcePath={}, datasetRoot={}, imageCount={}, fileSize={}",
                    assetCode,
                    sourcePath,
                    datasetRoot,
                    imageCount,
                    fileSize
            );
            return result;
        } catch (BusinessException ex) {
            cleanupQuietly(assetRoot);
            throw ex;
        } catch (IOException ex) {
            cleanupQuietly(assetRoot);
            throw new BusinessException("SERVER_PATH_IMPORT_FAILED", "服务器数据集导入失败", ex);
        }
    }

    public Path resolveAllowedServerImportPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw new BusinessException("SERVER_PATH_REQUIRED", "请填写服务器可访问路径");
        }
        String mappedPath = applyServerPathMapping(rawPath.trim());
        Path candidate;
        try {
            candidate = Path.of(mappedPath).toAbsolutePath().normalize();
        } catch (Exception ex) {
            throw new BusinessException("SERVER_PATH_INVALID", "服务器路径格式不正确: " + rawPath, ex);
        }

        List<Path> allowedRoots = workflowStorageProperties.resolvedServerImportRoots();
        boolean allowed = allowedRoots.stream().anyMatch(candidate::startsWith);
        log.info(
                "Resolved server import path: rawPath={}, mappedPath={}, resolvedPath={}, allowedRoots={}, allowed={}",
                rawPath,
                mappedPath,
                candidate,
                allowedRoots,
                allowed
        );
        if (!allowed) {
            throw new BusinessException("SERVER_PATH_OUT_OF_SCOPE", "服务器路径不在允许导入的挂载目录范围内: " + candidate);
        }
        return candidate;
    }

    private void validateMultipartFile(MultipartFile file, String displayName) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("UPLOAD_FILE_MISSING", "请先选择需要上传的" + displayName);
        }
    }

    private void validateModelFilename(String filename) {
        String ext = extensionOf(filename);
        if (!MODEL_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("MODEL_FILE_TYPE_UNSUPPORTED", "模型文件格式不支持，仅支持 .pt / .pth / .weights / .onnx / .bin");
        }
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(index) : "";
    }

    private String sanitizeFilename(String raw, String fallback) {
        String candidate = StringUtils.hasText(raw) ? raw.trim() : fallback;
        candidate = candidate.replace("\\", "/");
        int lastSlash = candidate.lastIndexOf('/');
        if (lastSlash >= 0) {
            candidate = candidate.substring(lastSlash + 1);
        }
        candidate = candidate.replaceAll("[\\r\\n]+", "_");
        candidate = candidate.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (!StringUtils.hasText(candidate)) {
            return fallback;
        }
        return candidate;
    }

    private String applyServerPathMapping(String rawPath) {
        List<WorkflowStorageProperties.PathMapping> mappings = new ArrayList<>(workflowStorageProperties.resolvedServerPathMappings());
        mappings.sort(Comparator.comparingInt((WorkflowStorageProperties.PathMapping item) -> item.sourcePrefix().length()).reversed());
        for (WorkflowStorageProperties.PathMapping mapping : mappings) {
            if (mapping == null || !StringUtils.hasText(mapping.sourcePrefix()) || !StringUtils.hasText(mapping.targetPrefix())) {
                continue;
            }
            String normalizedRaw = normalizeForCompare(rawPath);
            String normalizedSource = normalizeForCompare(mapping.sourcePrefix());
            if (normalizedRaw.startsWith(normalizedSource)) {
                String suffix = rawPath.substring(Math.min(rawPath.length(), mapping.sourcePrefix().length()));
                String mapped = mapping.targetPrefix() + suffix;
                log.info(
                        "Applied server path mapping: rawPath={}, sourcePrefix={}, targetPrefix={}, mappedPath={}",
                        rawPath,
                        mapping.sourcePrefix(),
                        mapping.targetPrefix(),
                        mapped
                );
                return mapped;
            }
        }
        return rawPath;
    }

    private String normalizeForCompare(String path) {
        return String.valueOf(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private void unzipSafely(Path archivePath, Path targetDir) throws IOException {
        try (InputStream inputStream = Files.newInputStream(archivePath);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName()).toAbsolutePath().normalize();
                if (!targetPath.startsWith(targetDir)) {
                    throw new BusinessException("ZIP_ENTRY_INVALID", "数据集压缩包包含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path sourcePath : stream.sorted().toList()) {
                Path relativePath = sourceRoot.relativize(sourcePath);
                Path targetPath = targetRoot.resolve(relativePath).toAbsolutePath().normalize();
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path detectDatasetRoot(Path contentDir) throws IOException {
        if (looksLikeDatasetRoot(contentDir)) {
            return contentDir;
        }
        try (var stream = Files.list(contentDir)) {
            List<Path> childDirectories = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            if (childDirectories.size() == 1 && looksLikeDatasetRoot(childDirectories.get(0))) {
                return childDirectories.get(0);
            }
        }
        return contentDir;
    }

    private boolean looksLikeDatasetRoot(Path path) throws IOException {
        if (path == null || !Files.exists(path) || !Files.isDirectory(path)) {
            return false;
        }
        if (Files.exists(path.resolve("images")) || Files.exists(path.resolve("labels")) || Files.exists(path.resolve("data.yaml"))) {
            return true;
        }
        try (var stream = Files.list(path)) {
            return stream.anyMatch(item -> Files.isRegularFile(item) && IMAGE_EXTENSIONS.contains(extensionOf(item.getFileName().toString()).toLowerCase(Locale.ROOT)));
        }
    }

    private int countImages(Path datasetRoot) {
        if (datasetRoot == null || !Files.exists(datasetRoot) || !Files.isDirectory(datasetRoot)) {
            return 0;
        }
        try (var stream = Files.walk(datasetRoot)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> IMAGE_EXTENSIONS.contains(extensionOf(path.getFileName().toString()).toLowerCase(Locale.ROOT)))
                    .count();
        } catch (IOException ex) {
            throw new BusinessException("DATASET_SCAN_FAILED", "扫描数据集图片失败: " + datasetRoot, ex);
        }
    }

    private void cleanupQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        } catch (Exception ex) {
            log.warn("Failed to cleanup path after storage error: path={}", path, ex);
        }
    }

    @Data
    public static class StoredFileAsset {
        private Path storedPath;
        private Path sourcePath;
        private String originalFilename;
        private long fileSize;
    }

    @Data
    public static class StoredDatasetAsset {
        private Path storedPath;
        private Path sourcePath;
        private Path archivePath;
        private String originalFilename;
        private long fileSize;
        private int imageCount;
    }
}
