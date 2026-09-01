package com.workflow.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "workflow.storage")
public class WorkflowStorageProperties {

    @NotBlank
    private String runtimeMode = "local-dev";

    @NotBlank
    private String rootPath = "/opt/workflow-platform/storage";

    @NotBlank
    private String tempDir = "/opt/workflow-platform/storage/tmp";

    @NotBlank
    private String modelUploadDir = "/opt/workflow-platform/storage/model-uploads";

    @NotBlank
    private String modelRootPath = "/opt/workflow-platform/storage/models";

    @NotBlank
    private String datasetRootPath = "/opt/workflow-platform/storage/datasets";

    @NotBlank
    private String validationCacheRootPath = "/opt/workflow-platform/storage/validation-cache";

    @NotBlank
    private String federatedModelRootPath = "/opt/workflow-platform/storage/federated-models";

    private boolean serverPathImportEnabled;

    private String serverPathImportRoots = "";

    private String serverPathMappings = "";

    private String serverPathImportRoles = "SERVER";

    public Path rootDirPath() {
        return normalize(rootPath);
    }

    public Path tempDirPath() {
        return normalize(tempDir);
    }

    public Path modelUploadDirPath() {
        return normalize(modelUploadDir);
    }

    public Path modelRootDirPath() {
        return normalize(modelRootPath);
    }

    public Path datasetRootDirPath() {
        return normalize(datasetRootPath);
    }

    public Path validationCacheRootDirPath() {
        return normalize(validationCacheRootPath);
    }

    public Path federatedModelRootDirPath() {
        return normalize(federatedModelRootPath);
    }

    public List<Path> resolvedServerImportRoots() {
        List<Path> configuredRoots = splitCsv(serverPathImportRoots).stream()
                .map(this::normalize)
                .distinct()
                .toList();
        if (!configuredRoots.isEmpty()) {
            return configuredRoots;
        }
        if (!serverPathImportEnabled) {
            return List.of();
        }
        return List.of(rootDirPath());
    }

    public List<PathMapping> resolvedServerPathMappings() {
        return splitCsv(serverPathMappings).stream()
                .map(this::parsePathMapping)
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean isServerPathImportAllowedForRole(String roleCode) {
        if (!serverPathImportEnabled || !StringUtils.hasText(roleCode)) {
            return false;
        }
        String normalizedRole = roleCode.trim().toUpperCase(Locale.ROOT);
        return splitCsv(serverPathImportRoles).stream()
                .map(item -> item.toUpperCase(Locale.ROOT))
                .anyMatch(normalizedRole::equals);
    }

    private Path normalize(String value) {
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private PathMapping parsePathMapping(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String[] pair = raw.split("=>", 2);
        if (pair.length != 2) {
            pair = raw.split("=", 2);
        }
        if (pair.length != 2) {
            return null;
        }
        if (!StringUtils.hasText(pair[0]) || !StringUtils.hasText(pair[1])) {
            return null;
        }
        return new PathMapping(pair[0].trim(), pair[1].trim());
    }

    private List<String> splitCsv(String raw) {
        return Arrays.stream((raw == null ? "" : raw).split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    public record PathMapping(String sourcePrefix, String targetPrefix) {
    }
}
