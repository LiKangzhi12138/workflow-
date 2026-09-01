package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WorkflowStorageProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationImageCacheService {

    private static final String CURRENT_DIR_NAME = "current";
    private static final String MANIFEST_FILE_NAME = "manifest.json";

    private final WorkflowStorageProperties workflowStorageProperties;
    private final ObjectMapper objectMapper;

    public Path resolveValidationImageCacheDir(String roleCode, Long userId) {
        if (!StringUtils.hasText(roleCode) || userId == null) {
            throw new IllegalArgumentException("roleCode and userId must not be empty.");
        }
        String normalizedRoleCode = roleCode.trim().toUpperCase(Locale.ROOT);
        return workflowStorageProperties.validationCacheRootDirPath()
                .resolve(normalizedRoleCode + "_" + userId)
                .resolve(CURRENT_DIR_NAME)
                .toAbsolutePath()
                .normalize();
    }

    public int clearValidationImageCache(String roleCode, Long userId) {
        Path cacheDir = resolveValidationImageCacheDir(roleCode, userId);
        try {
            int deletedImageCount = 0;
            if (Files.exists(cacheDir)) {
                try (Stream<Path> stream = Files.walk(cacheDir)) {
                    List<Path> paths = stream
                            .sorted(Comparator.reverseOrder())
                            .collect(Collectors.toList());
                    for (Path path : paths) {
                        if (path.equals(cacheDir)) {
                            continue;
                        }
                        if (Files.isRegularFile(path) && !MANIFEST_FILE_NAME.equals(path.getFileName().toString())) {
                            deletedImageCount++;
                        }
                        Files.deleteIfExists(path);
                    }
                }
            }
            Files.createDirectories(cacheDir);
            log.info(
                    "Cleared validation image cache directory: roleCode={}, userId={}, cacheDir={}, deletedImageCount={}",
                    roleCode,
                    userId,
                    cacheDir,
                    deletedImageCount
            );
            return deletedImageCount;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to clear validation image cache directory: " + cacheDir, ex);
        }
    }

    public CachedValidationImageManifest cacheValidationResultImages(String roleCode,
                                                                     Long userId,
                                                                     String ownerType,
                                                                     Long ownerId,
                                                                     List<SourceSampleImage> sourceImages) {
        Path cacheDir = resolveValidationImageCacheDir(roleCode, userId);
        int deletedImageCount = clearValidationImageCache(roleCode, userId);
        CachedValidationImageManifest manifest = new CachedValidationImageManifest();
        manifest.setRoleCode(roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT));
        manifest.setUserId(userId);
        manifest.setOwnerType(ownerType);
        manifest.setOwnerId(ownerId);
        manifest.setCacheDir(cacheDir.toString());
        manifest.setGeneratedAt(java.time.LocalDateTime.now().toString());

        List<CachedValidationImageEntry> entries = new ArrayList<>();
        int copiedImageCount = 0;
        for (SourceSampleImage sourceImage : sourceImages == null ? List.<SourceSampleImage>of() : sourceImages) {
            if (sourceImage == null || sourceImage.getSampleIndex() == null || sourceImage.getSourcePath() == null) {
                continue;
            }
            Path sourcePath = sourceImage.getSourcePath().toAbsolutePath().normalize();
            if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
                log.warn(
                        "Skipping validation cache copy because source image does not exist: roleCode={}, userId={}, ownerType={}, ownerId={}, sampleIndex={}, sourcePath={}",
                        roleCode,
                        userId,
                        ownerType,
                        ownerId,
                        sourceImage.getSampleIndex(),
                        sourcePath
                );
                continue;
            }

            String extension = resolveExtension(sourcePath.getFileName().toString());
            String targetFileName = String.format(
                    Locale.ROOT,
                    "sample-%02d%s%s",
                    sourceImage.getSampleIndex(),
                    Boolean.TRUE.equals(sourceImage.getAnnotatedImage()) ? "-annotated" : "-source",
                    extension
            );
            Path targetPath = cacheDir.resolve(targetFileName).toAbsolutePath().normalize();

            try {
                Files.createDirectories(cacheDir);
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                copiedImageCount++;

                CachedValidationImageEntry entry = new CachedValidationImageEntry();
                entry.setSampleIndex(sourceImage.getSampleIndex());
                entry.setImageName(sourceImage.getImageName());
                entry.setAnnotatedImage(Boolean.TRUE.equals(sourceImage.getAnnotatedImage()));
                entry.setCachedFileName(targetFileName);
                entry.setSourcePath(sourcePath.toString());
                entries.add(entry);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to copy validation sample image into cache: " + sourcePath, ex);
            }
        }

        manifest.setEntries(entries);
        try {
            objectMapper.writeValue(cacheDir.resolve(MANIFEST_FILE_NAME).toFile(), manifest);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to persist validation image cache manifest: " + cacheDir, ex);
        }

        log.info(
                "Cached validation result images: roleCode={}, userId={}, ownerType={}, ownerId={}, cacheDir={}, deletedImageCount={}, copiedImageCount={}, copiedFiles={}",
                roleCode,
                userId,
                ownerType,
                ownerId,
                cacheDir,
                deletedImageCount,
                copiedImageCount,
                entries.stream().map(CachedValidationImageEntry::getCachedFileName).collect(Collectors.toList())
        );
        return manifest;
    }

    public Path resolveCachedSampleImage(String roleCode,
                                         Long userId,
                                         String ownerType,
                                         Long ownerId,
                                         int sampleIndex) {
        if (sampleIndex < 0) {
            return null;
        }
        Path cacheDir = resolveValidationImageCacheDir(roleCode, userId);
        CachedValidationImageManifest manifest = readManifest(cacheDir);
        if (manifest == null) {
            return null;
        }
        if (!Objects.equals(normalizeType(manifest.getOwnerType()), normalizeType(ownerType))
                || !Objects.equals(manifest.getOwnerId(), ownerId)
                || !Objects.equals(normalizeRole(manifest.getRoleCode()), normalizeRole(roleCode))
                || !Objects.equals(manifest.getUserId(), userId)) {
            return null;
        }

        CachedValidationImageEntry entry = manifest.getEntries() == null
                ? null
                : manifest.getEntries().stream()
                .filter(item -> Objects.equals(item.getSampleIndex(), sampleIndex))
                .findFirst()
                .orElse(null);
        if (entry == null || !StringUtils.hasText(entry.getCachedFileName())) {
            return null;
        }

        Path cachedImagePath = cacheDir.resolve(entry.getCachedFileName()).toAbsolutePath().normalize();
        if (!Files.exists(cachedImagePath) || !Files.isRegularFile(cachedImagePath)) {
            return null;
        }
        return cachedImagePath;
    }

    public CachedValidationImageManifest getCachedManifest(String roleCode,
                                                           Long userId,
                                                           String ownerType,
                                                           Long ownerId) {
        Path cacheDir = resolveValidationImageCacheDir(roleCode, userId);
        CachedValidationImageManifest manifest = readManifest(cacheDir);
        if (manifest == null) {
            return null;
        }
        if (!Objects.equals(normalizeType(manifest.getOwnerType()), normalizeType(ownerType))
                || !Objects.equals(manifest.getOwnerId(), ownerId)
                || !Objects.equals(normalizeRole(manifest.getRoleCode()), normalizeRole(roleCode))
                || !Objects.equals(manifest.getUserId(), userId)) {
            return null;
        }
        return manifest;
    }

    private CachedValidationImageManifest readManifest(Path cacheDir) {
        Path manifestPath = cacheDir.resolve(MANIFEST_FILE_NAME).toAbsolutePath().normalize();
        if (!Files.exists(manifestPath) || !Files.isRegularFile(manifestPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(manifestPath.toFile(), CachedValidationImageManifest.class);
        } catch (IOException ex) {
            log.warn("Failed to read validation image cache manifest: manifestPath={}", manifestPath, ex);
            return null;
        }
    }

    private String resolveExtension(String filename) {
        int lastDot = filename == null ? -1 : filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) {
            return ".jpg";
        }
        return filename.substring(lastDot);
    }

    private String normalizeRole(String roleCode) {
        return roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String ownerType) {
        return ownerType == null ? null : ownerType.trim().toUpperCase(Locale.ROOT);
    }

    @Data
    public static class SourceSampleImage {
        private Integer sampleIndex;
        private String imageName;
        private Path sourcePath;
        private Boolean annotatedImage;
    }

    @Data
    public static class CachedValidationImageManifest {
        private String roleCode;
        private Long userId;
        private String ownerType;
        private Long ownerId;
        private String cacheDir;
        private String generatedAt;
        private List<CachedValidationImageEntry> entries = new ArrayList<>();
    }

    @Data
    public static class CachedValidationImageEntry {
        private Integer sampleIndex;
        private String imageName;
        private Boolean annotatedImage;
        private String cachedFileName;
        private String sourcePath;
    }
}
