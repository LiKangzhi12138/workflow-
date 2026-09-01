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
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemporaryValidationInputStorageService {

    private static final Set<String> MODEL_EXTENSIONS = Set.of(".pt", ".pth", ".weights", ".onnx", ".bin");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp", ".webp");

    private final WorkflowStorageProperties workflowStorageProperties;

    public PreparedStandaloneInput prepareStandaloneInput(MultipartFile modelFile, MultipartFile datasetArchive) {
        if (modelFile == null || modelFile.isEmpty()) {
            throw new BusinessException("VALIDATION_MODEL_FILE_REQUIRED", "临时验证必须上传模型文件。");
        }
        if (datasetArchive == null || datasetArchive.isEmpty()) {
            throw new BusinessException("VALIDATION_DATASET_FILE_REQUIRED", "临时验证必须上传数据集压缩包。");
        }

        String modelFilename = sanitizeFilename(modelFile.getOriginalFilename(), "model.pt");
        String datasetFilename = sanitizeFilename(datasetArchive.getOriginalFilename(), "dataset.zip");
        validateModelFilename(modelFilename);
        validateDatasetArchiveFilename(datasetFilename);

        Path inputRoot = workflowStorageProperties.tempDirPath()
                .resolve("standalone-inputs")
                .resolve(UUID.randomUUID().toString().replace("-", ""))
                .toAbsolutePath()
                .normalize();
        String requestId = inputRoot.getFileName() == null ? "unknown" : inputRoot.getFileName().toString();
        Path modelDir = inputRoot.resolve("model");
        Path datasetUploadDir = inputRoot.resolve("dataset-upload");
        Path datasetContentDir = inputRoot.resolve("dataset-content");
        try {
            Files.createDirectories(modelDir);
            Files.createDirectories(datasetUploadDir);
            Files.createDirectories(datasetContentDir);

            Path storedModelPath = modelDir.resolve(modelFilename).toAbsolutePath().normalize();
            storeMultipartFile(modelFile, storedModelPath);
            verifyStoredRegularFile(storedModelPath, "modelFile", requestId, modelFile.getOriginalFilename());

            Path archivePath = datasetUploadDir.resolve(datasetFilename).toAbsolutePath().normalize();
            storeMultipartFile(datasetArchive, archivePath);
            verifyStoredRegularFile(archivePath, "datasetArchive", requestId, datasetArchive.getOriginalFilename());

            unzipSafely(archivePath, datasetContentDir);
            Path datasetRoot = detectDatasetRoot(datasetContentDir);
            int imageCount = countImages(datasetRoot);
            if (imageCount <= 0) {
                throw new BusinessException("VALIDATION_DATASET_IMAGES_MISSING", "上传的数据集压缩包中未找到可识别图片。");
            }

            PreparedStandaloneInput prepared = new PreparedStandaloneInput();
            prepared.setInputRootPath(inputRoot);
            prepared.setModelPath(storedModelPath);
            prepared.setDatasetPath(datasetRoot);
            prepared.setModelName(modelFilename);
            prepared.setDatasetName(datasetRoot.getFileName() == null ? datasetFilename : datasetRoot.getFileName().toString());
            prepared.setDatasetImageCount(imageCount);
            log.info(
                    "Prepared temporary standalone validation input: requestId={}, inputRootPath={}, modelOriginalName={}, datasetOriginalName={}, modelPath={}, datasetArchivePath={}, datasetPath={}, modelExists={}, modelReadable={}, modelSize={}, datasetExists={}, datasetReadable={}, imageCount={}",
                    requestId,
                    inputRoot,
                    modelFile.getOriginalFilename(),
                    datasetArchive.getOriginalFilename(),
                    storedModelPath,
                    archivePath,
                    datasetRoot,
                    Files.exists(storedModelPath),
                    Files.isReadable(storedModelPath),
                    safeSize(storedModelPath),
                    Files.exists(datasetRoot),
                    Files.isReadable(datasetRoot),
                    imageCount
            );
            return prepared;
        } catch (BusinessException ex) {
            cleanupQuietly(inputRoot);
            throw ex;
        } catch (IOException ex) {
            cleanupQuietly(inputRoot);
            throw new BusinessException("VALIDATION_TEMP_INPUT_PREPARE_FAILED", "临时验证输入准备失败。", ex);
        }
    }

    public void cleanupInputRoot(Path inputRootPath, String scene) {
        if (inputRootPath == null) {
            return;
        }
        if (!Files.exists(inputRootPath)) {
            log.info("Skip temporary standalone input cleanup because path already missing: inputRootPath={}, scene={}", inputRootPath, scene);
            return;
        }
        cleanupQuietly(inputRootPath);
        log.info("Temporary standalone input cleanup completed: inputRootPath={}, scene={}", inputRootPath, scene);
    }

    private void storeMultipartFile(MultipartFile source, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (InputStream inputStream = source.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void verifyStoredRegularFile(Path path, String fieldName, String requestId, String originalFilename) {
        boolean exists = Files.exists(path);
        boolean regularFile = Files.isRegularFile(path);
        boolean readable = Files.isReadable(path);
        long fileSize = safeSize(path);
        log.info(
                "Temporary standalone input file stored: requestId={}, fieldName={}, originalFilename={}, targetPath={}, exists={}, regularFile={}, readable={}, fileSize={}",
                requestId,
                fieldName,
                originalFilename,
                path,
                exists,
                regularFile,
                readable,
                fileSize
        );
        if (!exists || !regularFile || fileSize <= 0L) {
            throw new BusinessException(
                    "VALIDATION_TEMP_INPUT_STORE_FAILED",
                    String.format(Locale.ROOT, "Temporary upload file save failed: %s -> %s", defaultString(originalFilename), path)
            );
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.exists(path) && Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (IOException ex) {
            log.warn("Read temporary standalone input file size failed: path={}", path, ex);
            return -1L;
        }
    }

    private void validateModelFilename(String filename) {
        String ext = extensionOf(filename);
        if (!MODEL_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("VALIDATION_MODEL_TYPE_UNSUPPORTED", "模型文件仅支持 .pt / .pth / .weights / .onnx / .bin。");
        }
    }

    private void validateDatasetArchiveFilename(String filename) {
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException("VALIDATION_DATASET_ARCHIVE_REQUIRED", "临时验证的数据集必须上传 zip 压缩包。");
        }
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
        return StringUtils.hasText(candidate) ? candidate : fallback;
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(index) : "";
    }

    private void unzipSafely(Path archivePath, Path targetDir) throws IOException {
        try (InputStream inputStream = Files.newInputStream(archivePath);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName()).toAbsolutePath().normalize();
                if (!targetPath.startsWith(targetDir)) {
                    throw new BusinessException("VALIDATION_ZIP_ENTRY_INVALID", "数据集压缩包中包含非法路径。");
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

    private Path detectDatasetRoot(Path contentDir) throws IOException {
        if (looksLikeDatasetRoot(contentDir)) {
            return contentDir;
        }
        try (var stream = Files.list(contentDir)) {
            var childDirectories = stream.filter(Files::isDirectory).sorted().toList();
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
            return stream.anyMatch(item ->
                    Files.isRegularFile(item)
                            && IMAGE_EXTENSIONS.contains(extensionOf(item.getFileName().toString()).toLowerCase(Locale.ROOT)));
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
            throw new BusinessException("VALIDATION_DATASET_SCAN_FAILED", "扫描临时数据集目录失败。", ex);
        }
    }

    private void cleanupQuietly(Path rootPath) {
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        try (var stream = Files.walk(rootPath)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        } catch (Exception ex) {
            log.warn("Temporary standalone input cleanup failed: inputRootPath={}", rootPath, ex);
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    @Data
    public static class PreparedStandaloneInput {
        private Path inputRootPath;
        private Path modelPath;
        private Path datasetPath;
        private String modelName;
        private String datasetName;
        private Integer datasetImageCount;
    }
}
