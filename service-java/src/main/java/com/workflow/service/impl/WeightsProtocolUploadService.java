package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WeightsProtocolProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonWeightsValidationResponse;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.ModelDefinitionService;
import com.workflow.service.python.PythonWeightsProtocolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeightsProtocolUploadService {

    public static final String PROTOCOL_V1 = "WEIGHTS_V1";
    public static final String LEGACY_PROTOCOL = "LEGACY_CHECKPOINT";
    private static final String TOKEN_PREFIX = "wv1_";
    private static final String STORED_FILE_PREFIX = "v1_";
    private static final long MAX_METADATA_BYTES = 1024L * 1024L;

    private final WeightsProtocolProperties properties;
    private final WorkflowStorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final PythonWeightsProtocolClient pythonClient;
    private final ModelAssetService modelAssetService;
    private final WorkflowModelUploadStateService stateService;
    private final WorkflowModelUploadMapper uploadMapper;
    private final ControlledArtifactDeletionService deletionService;

    public boolean appliesTo(Workflow workflow) {
        return properties.isEnabled() && workflow != null && workflow.getModelDefinitionId() != null;
    }

    public String protocolFor(Workflow workflow) {
        return appliesTo(workflow) ? PROTOCOL_V1 : LEGACY_PROTOCOL;
    }

    public String decorateToken(String token, Workflow workflow) {
        return appliesTo(workflow) ? TOKEN_PREFIX + token : token;
    }

    public boolean isV1Token(String token) {
        return StringUtils.hasText(token) && token.startsWith(TOKEN_PREFIX);
    }

    public boolean isV1Record(WorkflowModelUpload upload) {
        return upload != null
                && StringUtils.hasText(upload.getStoredFilename())
                && upload.getStoredFilename().startsWith(STORED_FILE_PREFIX);
    }

    public String storedFilename(Long uploadId, String sanitizedOriginalName) {
        return STORED_FILE_PREFIX + uploadId + "_" + sanitizedOriginalName + ".enc";
    }

    public void persistMetadata(Long workflowId, Long uploadId, String manifestJson, String descriptorJson) {
        JsonNode manifest = parseMetadata(manifestJson, "manifest");
        JsonNode descriptor = parseMetadata(descriptorJson, "descriptor");
        Path directory = metadataDirectory(workflowId);
        try {
            Files.createDirectories(directory);
            Files.writeString(manifestPath(workflowId, uploadId), objectMapper.writeValueAsString(manifest),
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(descriptorPath(workflowId, uploadId), objectMapper.writeValueAsString(descriptor),
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) {
            cleanupMetadata(workflowId, uploadId);
            throw new BusinessException("WEIGHTS_MANIFEST_INVALID", "权重包元数据保存失败。", ex);
        }
    }

    public void processDecryptedUpload(Workflow workflow, WorkflowModelUpload upload, byte[] decryptedWeights) {
        if (!isV1Record(upload) || !appliesTo(workflow)) {
            throw new BusinessException("WEIGHTS_PROTOCOL_DISABLED", "当前上传记录不允许使用权重协议 V1。");
        }
        Path quarantineRoot = storageProperties.tempDirPath().resolve("weights-v1-quarantine").normalize();
        Path quarantineDirectory = controlledChild(quarantineRoot, workflow.getId().toString(), upload.getId().toString());
        Path quarantineWeights = quarantineDirectory.resolve("weights.pt");
        Path finalDirectory = controlledChild(
                storageProperties.weightsAssetRootDirPath(), workflow.getId().toString(), upload.getId().toString());
        Path finalWeights = finalDirectory.resolve("weights.pt");

        Long registeredAssetId = null;
        try {
            ModelDefinition definition = modelDefinitionService.requireEnabledDefinition(workflow.getModelDefinitionId());
            JsonNode trustedDefinition = modelDefinitionService.readTrustedDefinitionJson(definition);
            JsonNode manifest = readMetadata(manifestPath(workflow.getId(), upload.getId()), "manifest");
            JsonNode descriptor = readMetadata(descriptorPath(workflow.getId(), upload.getId()), "descriptor");
            Files.createDirectories(quarantineDirectory);
            Files.write(quarantineWeights, decryptedWeights, StandardOpenOption.CREATE_NEW);
            PythonWeightsValidationResponse response = pythonClient.validateUpload(
                    quarantineWeights.toString(), manifest, descriptor, trustedDefinition);
            if (response == null || !Boolean.TRUE.equals(response.getPassed())) {
                String reason = response == null ? "WEIGHTS_INSPECTION_FAILED" : response.getReasonCode();
                throw new BusinessException(mapClientErrorCode(reason),
                        "上传的模型权重与当前工作流选择的模型不兼容。");
            }

            Files.createDirectories(finalDirectory);
            moveFile(quarantineWeights, finalWeights);
            Files.writeString(finalDirectory.resolve("manifest.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(finalDirectory.resolve("descriptor.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor),
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(finalDirectory.resolve("inspection-report.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response),
                    StandardOpenOption.CREATE_NEW);

            registeredAssetId = modelAssetService.registerWeightsProtocolV1Asset(
                    workflow, upload, definition, finalWeights,
                    "Protocol v1 safe inspection and Definition compatibility passed.");
            stateService.markWeightsProtocolSucceeded(
                    workflow.getId(), upload.getId(), finalWeights.toString(), registeredAssetId);
            cleanupAcceptedInput(upload);
            log.info(
                    "Weights Protocol v1 upload accepted: workflowId={}, modelDefinitionId={}, uploadId={}, assetId={}, shaPrefix={}",
                    workflow.getId(), definition.getId(), upload.getId(), registeredAssetId,
                    shaPrefix(upload.getFileSha256()));
        } catch (BusinessException ex) {
            cleanupPartialFiles(
                    quarantineRoot, quarantineDirectory, finalDirectory, registeredAssetId == null);
            cleanupMetadata(workflow.getId(), upload.getId());
            throw ex;
        } catch (Exception ex) {
            cleanupPartialFiles(
                    quarantineRoot, quarantineDirectory, finalDirectory, registeredAssetId == null);
            cleanupMetadata(workflow.getId(), upload.getId());
            if (registeredAssetId != null) {
                log.warn(
                        "Weights asset remains intact after post-registration finalization failure: workflowId={}, uploadId={}, assetId={}",
                        workflow.getId(), upload.getId(), registeredAssetId);
            }
            throw new BusinessException("WEIGHTS_UPLOAD_FAILED", "模型权重校验或纳管失败。", ex);
        }
    }

    private JsonNode parseMetadata(String raw, String name) {
        if (!StringUtils.hasText(raw) || raw.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new BusinessException("WEIGHTS_MANIFEST_INVALID", name + " 缺失或超过 1 MiB 限制。");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) throw new IOException("metadata must be a JSON object");
            return node;
        } catch (IOException ex) {
            throw new BusinessException("WEIGHTS_MANIFEST_INVALID", name + " 不是合法 JSON。", ex);
        }
    }

    private JsonNode readMetadata(Path path, String name) {
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > MAX_METADATA_BYTES) {
                throw new IOException("metadata is missing, linked, or oversized");
            }
            return objectMapper.readTree(path.toFile());
        } catch (IOException ex) {
            throw new BusinessException("WEIGHTS_MANIFEST_INVALID", name + " 元数据不可用。", ex);
        }
    }

    private Path metadataDirectory(Long workflowId) {
        return controlledChild(storageProperties.modelUploadDirPath(), workflowId.toString(), "protocol-v1");
    }

    private Path manifestPath(Long workflowId, Long uploadId) {
        return metadataDirectory(workflowId).resolve(uploadId + ".manifest.json");
    }

    private Path descriptorPath(Long workflowId, Long uploadId) {
        return metadataDirectory(workflowId).resolve(uploadId + ".descriptor.json");
    }

    private Path controlledChild(Path root, String... parts) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot;
        for (String part : parts) result = result.resolve(part);
        result = result.toAbsolutePath().normalize();
        if (result.equals(normalizedRoot) || !result.startsWith(normalizedRoot)) {
            throw new BusinessException("PATH_NOT_ALLOWED", "协议文件路径超出受控存储目录。");
        }
        return result;
    }

    private void cleanupAcceptedInput(WorkflowModelUpload upload) {
        ControlledArtifactDeletionService.DeleteResult result = deletionService.deleteRegularFile(
                upload.getEncryptedFilePath(), storageProperties.modelUploadDirPath());
        if (result.removedOrMissing()) {
            uploadMapper.update(null, new LambdaUpdateWrapper<WorkflowModelUpload>()
                    .eq(WorkflowModelUpload::getId, upload.getId())
                    .set(WorkflowModelUpload::getEncryptedFilePath, null));
        } else {
            log.warn("Encrypted V1 upload cleanup deferred: uploadId={}, reason={}", upload.getId(), result.reason());
        }
        cleanupMetadata(upload.getWorkflowId(), upload.getId());
    }

    private void cleanupMetadata(Long workflowId, Long uploadId) {
        deletionService.deleteRegularFile(manifestPath(workflowId, uploadId).toString(),
                storageProperties.modelUploadDirPath());
        deletionService.deleteRegularFile(descriptorPath(workflowId, uploadId).toString(),
                storageProperties.modelUploadDirPath());
    }

    private void cleanupPartialFiles(
            Path quarantineRoot,
            Path quarantineDirectory,
            Path finalDirectory,
            boolean removeUnregisteredFinalFiles
    ) {
        deletionService.deleteRegularFile(
                quarantineDirectory.resolve("weights.pt").toString(), quarantineRoot);
        if (!removeUnregisteredFinalFiles) {
            return;
        }
        for (String name : new String[]{"weights.pt", "manifest.json", "descriptor.json", "inspection-report.json"}) {
            deletionService.deleteRegularFile(finalDirectory.resolve(name).toString(),
                    storageProperties.weightsAssetRootDirPath());
        }
    }

    private void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private String mapClientErrorCode(String reason) {
        if (reason == null) return "WEIGHTS_INSPECTION_FAILED";
        if (reason.contains("ARCHITECTURE")) return "WEIGHTS_ARCHITECTURE_MISMATCH";
        if (reason.contains("KEY")) return "WEIGHTS_KEY_MISMATCH";
        if (reason.contains("SHAPE")) return "WEIGHTS_SHAPE_MISMATCH";
        if (reason.contains("SHA")) return "WEIGHTS_SHA_MISMATCH";
        if (reason.contains("DTYPE")) return "WEIGHTS_DTYPE_UNSUPPORTED";
        if (reason.contains("DEFINITION")) return "WEIGHTS_DEFINITION_MISMATCH";
        return "WEIGHTS_INSPECTION_FAILED";
    }

    private String shaPrefix(String sha) {
        return sha == null ? "(none)" : sha.substring(0, Math.min(12, sha.length()));
    }
}
