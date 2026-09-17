package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WeightsFedAvgProperties;
import com.workflow.config.WeightsProtocolProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonWeightsFedAvgRequest;
import com.workflow.dto.python.PythonWeightsFedAvgResponse;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.ModelDefinitionService;
import com.workflow.service.WorkflowStepService;
import com.workflow.service.python.PythonWeightsFedAvgClient;
import com.workflow.support.AssetSourceCatalog;
import com.workflow.support.WorkflowCurrentStepSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowWeightsFederatedAggregationService {

    public static final String STRATEGY = "WEIGHTS_FEDAVG_V1";
    private static final String AGGREGATION_METHOD = "EQUAL_FEDAVG";
    private static final String SOURCE_TYPE = AssetSourceCatalog.SOURCE_WORKFLOW_WEIGHTS_V1;
    private static final String IMPORT_MODE = AssetSourceCatalog.IMPORT_MODE_WEIGHTS_PROTOCOL_V1;
    private static final String STATUS_READY = "READY";
    private static final String CHECK_OK = "OK";

    private final WeightsProtocolProperties protocolProperties;
    private final WeightsFedAvgProperties fedAvgProperties;
    private final WorkflowStorageProperties storageProperties;
    private final WorkflowMapper workflowMapper;
    private final WorkflowModelUploadMapper uploadMapper;
    private final ModelAssetMapper assetMapper;
    private final ModelDefinitionService definitionService;
    private final ModelAssetService modelAssetService;
    private final PythonWeightsFedAvgClient pythonClient;
    private final WorkflowStepService workflowStepService;
    private final ControlledArtifactDeletionService deletionService;
    private final ObjectMapper objectMapper;

    public boolean triggerIfReady(Long workflowId, Long serverUserId, String triggerSource) {
        if (!protocolProperties.isEnabled() || !fedAvgProperties.isEnabled()) {
            return false;
        }

        Workflow workflow = workflowMapper.selectById(workflowId);
        if (!isEligibleWorkflow(workflow)) {
            return false;
        }
        if (serverUserId != null && !serverUserId.equals(workflow.getServerUserId())) {
            throw new BusinessException("FORBIDDEN", "Only the assigned server can aggregate workflow weights.");
        }

        int requiredCount = requiredModelCount(workflow);
        List<WorkflowModelUpload> inputs = listInspectedUploads(workflowId);
        if (inputs.size() < requiredCount) {
            return false;
        }
        inputs = new ArrayList<>(inputs.subList(0, requiredCount));

        int claimed = workflowMapper.claimWeightsFedAvgV1(
                workflowId, WorkflowCurrentStepSupport.WEIGHTS_INSPECTED_WAITING_FEDERATED);
        if (claimed != 1) {
            log.info("Weights FedAvg v1 trigger skipped because another worker owns the transition: workflowId={}", workflowId);
            return false;
        }

        Long registeredAssetId = null;
        Path outputDirectory = buildOutputDirectory(workflow);
        List<Long> uploadIds = inputs.stream().map(WorkflowModelUpload::getId).toList();
        try {
            ModelDefinition definition = definitionService.requireEnabledDefinition(workflow.getModelDefinitionId());
            JsonNode trustedDefinition = definitionService.readTrustedDefinitionJson(definition);
            String protocolDefinitionId = requireTrustedProtocolDefinitionId(definition, trustedDefinition);
            List<PythonWeightsFedAvgRequest.Input> pythonInputs = validateAndBuildInputs(
                    workflow, definition, protocolDefinitionId, inputs);

            int started = workflowMapper.startWeightsFedAvgV1(
                    workflowId, WorkflowCurrentStepSupport.FEDERATED_AGGREGATING);
            if (started != 1) {
                throw new BusinessException("WEIGHTS_FEDAVG_ALREADY_RUNNING", "联邦权重聚合已被其他任务处理。");
            }
            markInputAggregationStatus(uploadIds, "RUNNING", null);
            appendStep(workflow, "WEIGHTS_FEDAVG_V1_START", "联邦权重聚合", "服务端已启动 weights-only 等权 FedAvg。");

            PythonWeightsFedAvgRequest request = new PythonWeightsFedAvgRequest();
            request.setWorkflowId(workflowId);
            request.setTrustedModelDefinition(trustedDefinition);
            request.setInputs(pythonInputs);
            request.setOutputDirectory(outputDirectory.toString());
            request.setAggregationMethod(AGGREGATION_METHOD);
            PythonWeightsFedAvgResponse response = pythonClient.aggregate(request);
            validatePythonResponse(workflow, definition, protocolDefinitionId, pythonInputs, response);

            Path outputWeights = requireOutputFile(response.getOutputWeightsPath(), outputDirectory, "global_weights.pt");
            requireOutputFile(response.getOutputManifestPath(), outputDirectory, "aggregation-manifest.json");
            requireOutputFile(response.getOutputDescriptorPath(), outputDirectory, "descriptor.json");
            requireOutputFile(response.getOutputInspectionPath(), outputDirectory, "inspection-report.json");
            requireOutputFile(response.getOutputReportPath(), outputDirectory, "aggregation-report.json");
            String actualOutputSha = sha256(outputWeights);
            if (!actualOutputSha.equalsIgnoreCase(response.getOutputSha256())) {
                throw new BusinessException("WEIGHTS_FEDAVG_INPUT_TAMPERED", "联邦全局权重 SHA256 校验失败。");
            }

            registeredAssetId = modelAssetService.registerFederatedWeightsV1Asset(
                    workflow, definition, outputWeights, inputs.size(), actualOutputSha);
            int completed = workflowMapper.completeWeightsFedAvgV1(
                    workflowId, registeredAssetId, WorkflowCurrentStepSupport.FEDERATED_COMPLETED);
            if (completed != 1) {
                throw new BusinessException("WEIGHTS_FEDAVG_FAILED", "联邦权重聚合完成状态保存失败。");
            }
            markInputAggregationStatus(uploadIds, "COMPLETED", null);
            appendStep(
                    workflow,
                    "WEIGHTS_FEDAVG_V1_COMPLETE",
                    "联邦权重聚合",
                    "weights-only FedAvg 已完成，globalAssetId=" + registeredAssetId + "，inputCount=" + inputs.size()
            );
            log.info(
                    "Weights FedAvg v1 completed: workflowId={}, modelDefinitionId={}, inputAssetIds={}, globalAssetId={}, outputShaPrefix={}",
                    workflowId,
                    definition.getId(),
                    pythonInputs.stream().map(PythonWeightsFedAvgRequest.Input::getAssetId).toList(),
                    registeredAssetId,
                    actualOutputSha.substring(0, 12)
            );
            return true;
        } catch (Exception ex) {
            String safeMessage = safeFailureMessage(ex);
            workflowMapper.failWeightsFedAvgV1(
                    workflowId, WorkflowCurrentStepSupport.FEDERATED_FAILED, safeMessage);
            markInputAggregationStatus(uploadIds, "FAILED", safeMessage);
            appendStep(workflow, "WEIGHTS_FEDAVG_V1_FAILED", "联邦权重聚合", safeMessage);
            if (registeredAssetId == null) {
                cleanupUnregisteredOutput(outputDirectory);
            } else {
                log.warn(
                        "Global weights asset remains intact after post-registration state failure: workflowId={}, assetId={}",
                        workflowId,
                        registeredAssetId
                );
            }
            log.error(
                    "Weights FedAvg v1 failed: workflowId={}, triggerSource={}, reason={}",
                    workflowId,
                    triggerSource,
                    safeMessage,
                    ex
            );
            return false;
        }
    }

    private List<PythonWeightsFedAvgRequest.Input> validateAndBuildInputs(
            Workflow workflow,
            ModelDefinition definition,
            String protocolDefinitionId,
            List<WorkflowModelUpload> uploads
    ) throws IOException {
        List<PythonWeightsFedAvgRequest.Input> result = new ArrayList<>();
        Set<Long> assetIds = new HashSet<>();
        for (WorkflowModelUpload upload : uploads) {
            if (upload.getServerModelAssetId() == null || !assetIds.add(upload.getServerModelAssetId())) {
                throw new BusinessException("WEIGHTS_FEDAVG_INPUT_INVALID", "参与联邦聚合的权重资产重复或缺失。");
            }
            ModelAsset asset = assetMapper.selectById(upload.getServerModelAssetId());
            requireTrustedV1Asset(workflow, asset);
            Path weightsPath = requireInputPath(asset.getFilePath());
            if (!StringUtils.hasText(upload.getFileSha256())
                    || !sha256(weightsPath).equalsIgnoreCase(upload.getFileSha256())) {
                throw new BusinessException("WEIGHTS_FEDAVG_INPUT_TAMPERED", "参与联邦聚合的权重文件完整性校验失败。");
            }

            Path directory = weightsPath.getParent();
            Path manifestPath = requireInputPath(directory.resolve("manifest.json").toString());
            Path descriptorPath = requireInputPath(directory.resolve("descriptor.json").toString());
            Path inspectionPath = requireInputPath(directory.resolve("inspection-report.json").toString());
            validateTrustedIdentity(
                    workflow, upload, asset, definition, protocolDefinitionId,
                    manifestPath, descriptorPath, inspectionPath);

            PythonWeightsFedAvgRequest.Input item = new PythonWeightsFedAvgRequest.Input();
            item.setAssetId(asset.getId());
            item.setUploadId(upload.getId());
            item.setWeightsPath(weightsPath.toString());
            item.setExpectedSha256(upload.getFileSha256());
            item.setManifestPath(manifestPath.toString());
            item.setDescriptorPath(descriptorPath.toString());
            item.setInspectionReportPath(inspectionPath.toString());
            result.add(item);
        }
        return result;
    }

    private void requireTrustedV1Asset(Workflow workflow, ModelAsset asset) {
        boolean valid = asset != null
                && Objects.equals(workflow.getServerUserId(), asset.getOwnerUserId())
                && "SERVER".equals(asset.getOwnerRoleCode())
                && SOURCE_TYPE.equals(asset.getSourceType())
                && IMPORT_MODE.equals(asset.getImportMode())
                && AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equals(asset.getRecordMode())
                && STATUS_READY.equals(asset.getStatus())
                && CHECK_OK.equals(asset.getLastCheckStatus())
                && Integer.valueOf(1).equals(asset.getFilePathValidated())
                && !Integer.valueOf(1).equals(asset.getIsDeleted());
        if (!valid) {
            throw new BusinessException("WEIGHTS_FEDAVG_INPUT_INVALID", "参与联邦聚合的资产不是可信 V1 权重资产。");
        }
    }

    private void validateTrustedIdentity(
            Workflow workflow,
            WorkflowModelUpload upload,
            ModelAsset asset,
            ModelDefinition definition,
            String protocolDefinitionId,
            Path manifestPath,
            Path descriptorPath,
            Path inspectionPath
    ) throws IOException {
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
        JsonNode descriptor = objectMapper.readTree(descriptorPath.toFile());
        JsonNode inspection = objectMapper.readTree(inspectionPath.toFile());
        String manifestDefinitionId = manifest.path("modelDefinition").path("definitionId").asText(null);
        String descriptorDefinitionId = descriptor.path("modelDefinition").path("definitionId").asText(null);
        String manifestDefinitionSha = manifest.path("modelDefinition").path("definitionSha256").asText(null);
        String descriptorDefinitionSha = descriptor.path("modelDefinition").path("definitionSha256").asText(null);
        String manifestArchitecture = manifest.path("architectureSignature").asText(null);
        String descriptorArchitecture = descriptor.path("extensions").path("architectureSignature").asText(null);
        boolean identityMatches = protocolDefinitionId.equals(manifestDefinitionId)
                && protocolDefinitionId.equals(descriptorDefinitionId)
                && equalsIgnoreCase(definition.getDefinitionSha256(), manifestDefinitionSha)
                && equalsIgnoreCase(definition.getDefinitionSha256(), descriptorDefinitionSha)
                && equalsIgnoreCase(definition.getArchitectureSignature(), manifestArchitecture)
                && equalsIgnoreCase(definition.getArchitectureSignature(), descriptorArchitecture)
                && inspection.path("passed").asBoolean(false);
        if (!identityMatches) {
            log.warn(
                    "Weights FedAvg v1 Definition identity mismatch: workflowId={}, uploadId={}, assetId={}, "
                            + "trustedDbModelDefinitionId={}, trustedProtocolDefinitionId={}, trustedDefinitionCode={}, "
                            + "trustedDefinitionShaPrefix={}, manifestDefinitionId={}, manifestDefinitionShaPrefix={}, "
                            + "descriptorDefinitionId={}, descriptorDefinitionShaPrefix={}, "
                            + "trustedArchitecturePrefix={}, manifestArchitecturePrefix={}, descriptorArchitecturePrefix={}, "
                            + "inspectionPassed={}, identitySources=manifest,descriptor,persisted-inspection",
                    workflow.getId(),
                    upload.getId(),
                    asset.getId(),
                    definition.getId(),
                    protocolDefinitionId,
                    definition.getCode(),
                    prefix(definition.getDefinitionSha256()),
                    manifestDefinitionId,
                    prefix(manifestDefinitionSha),
                    descriptorDefinitionId,
                    prefix(descriptorDefinitionSha),
                    prefix(definition.getArchitectureSignature()),
                    prefix(manifestArchitecture),
                    prefix(descriptorArchitecture),
                    inspection.path("passed").asBoolean(false)
            );
            throw new BusinessException(
                    "WEIGHTS_FEDAVG_DEFINITION_MISMATCH",
                    "参与联邦聚合的权重不属于当前工作流 ModelDefinition。"
            );
        }
    }

    private void validatePythonResponse(
            Workflow workflow,
            ModelDefinition definition,
            String protocolDefinitionId,
            List<PythonWeightsFedAvgRequest.Input> inputs,
            PythonWeightsFedAvgResponse response
    ) {
        List<Long> expectedAssetIds = inputs.stream().map(PythonWeightsFedAvgRequest.Input::getAssetId).toList();
        boolean valid = response != null
                && Boolean.TRUE.equals(response.getPassed())
                && Objects.equals(workflow.getId(), response.getWorkflowId())
                && protocolDefinitionId.equals(String.valueOf(response.getModelDefinitionId()))
                && definition.getCode().equals(response.getModelDefinitionCode())
                && Objects.equals(inputs.size(), response.getInputCount())
                && expectedAssetIds.equals(response.getInputAssetIds())
                && AGGREGATION_METHOD.equals(response.getAggregationMethod())
                && response.getInspection() != null
                && response.getInspection().path("passed").asBoolean(false)
                && StringUtils.hasText(response.getOutputSha256());
        if (!valid) {
            throw new BusinessException(
                    "WEIGHTS_FEDAVG_OUTPUT_INVALID",
                    "联邦权重聚合服务返回的身份或检查证据不匹配。"
            );
        }
    }

    private String requireTrustedProtocolDefinitionId(ModelDefinition definition, JsonNode trustedDefinition) {
        String protocolDefinitionId = trustedDefinition.path("definitionId").asText(null);
        String trustedCode = trustedDefinition.path("code").asText(null);
        if (!StringUtils.hasText(protocolDefinitionId)
                || !Objects.equals(definition.getCode(), trustedCode)) {
            log.warn(
                    "Trusted ModelDefinition protocol identity mismatch: trustedDbModelDefinitionId={}, "
                            + "registryCode={}, trustedJsonCode={}, trustedProtocolDefinitionId={}",
                    definition.getId(), definition.getCode(), trustedCode, protocolDefinitionId
            );
            throw new BusinessException(
                    "WEIGHTS_FEDAVG_DEFINITION_MISMATCH",
                    "服务器模型定义身份校验失败。"
            );
        }
        return protocolDefinitionId;
    }

    private boolean equalsIgnoreCase(String expected, String actual) {
        return StringUtils.hasText(expected)
                && StringUtils.hasText(actual)
                && expected.equalsIgnoreCase(actual);
    }

    private String prefix(String value) {
        if (!StringUtils.hasText(value)) {
            return "<missing>";
        }
        return value.substring(0, Math.min(12, value.length()));
    }

    private Path requireInputPath(String rawPath) throws IOException {
        return requireControlledRegularFile(rawPath, storageProperties.weightsAssetRootDirPath());
    }

    private Path requireOutputFile(String rawPath, Path expectedDirectory, String expectedName) throws IOException {
        Path file = requireControlledRegularFile(rawPath, storageProperties.federatedModelRootDirPath());
        if (!file.getParent().equals(expectedDirectory) || !expectedName.equals(file.getFileName().toString())) {
            throw new BusinessException("WEIGHTS_FEDAVG_OUTPUT_INVALID", "联邦权重输出路径不符合服务端约束。");
        }
        return file;
    }

    private Path requireControlledRegularFile(String rawPath, Path root) throws IOException {
        if (!StringUtils.hasText(rawPath)) {
            throw new BusinessException("WEIGHTS_FEDAVG_INPUT_INVALID", "联邦权重文件路径缺失。");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (candidate.equals(normalizedRoot) || !candidate.startsWith(normalizedRoot)
                || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException("PATH_NOT_ALLOWED", "联邦权重文件不在受控目录内。");
        }
        Path realRoot = normalizedRoot.toRealPath();
        Path realFile = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realFile.startsWith(realRoot)) {
            throw new BusinessException("PATH_NOT_ALLOWED", "联邦权重文件路径越界。");
        }
        return realFile;
    }

    private Path buildOutputDirectory(Workflow workflow) {
        String code = StringUtils.hasText(workflow.getWorkflowCode())
                ? workflow.getWorkflowCode().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "WF" + workflow.getId();
        return storageProperties.federatedModelRootDirPath()
                .resolve(code)
                .resolve("weights-v1")
                .resolve(UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
    }

    private void cleanupUnregisteredOutput(Path outputDirectory) {
        for (String name : List.of(
                "global_weights.pt",
                "aggregation-manifest.json",
                "descriptor.json",
                "inspection-report.json",
                "aggregation-report.json"
        )) {
            deletionService.deleteRegularFile(
                    outputDirectory.resolve(name).toString(), storageProperties.federatedModelRootDirPath());
        }
    }

    private void markInputAggregationStatus(List<Long> uploadIds, String status, String errorMessage) {
        if (uploadIds.isEmpty()) {
            return;
        }
        uploadMapper.update(
                null,
                new UpdateWrapper<WorkflowModelUpload>()
                        .in("id", uploadIds)
                        .set("aggregation_status", status)
                        .set("error_message", errorMessage)
        );
    }

    private void appendStep(Workflow workflow, String code, String name, String message) {
        workflowStepService.appendWorkflowStep(
                workflow.getId(), code, name, workflow.getStatus(), workflow.getStatus(),
                workflow.getServerUserId(), "SERVER", message);
    }

    private List<WorkflowModelUpload> listInspectedUploads(Long workflowId) {
        return uploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .eq(WorkflowModelUpload::getWorkflowId, workflowId)
                        .eq(WorkflowModelUpload::getUploadStatus, "COMPLETED")
                        .eq(WorkflowModelUpload::getAggregationStatus, "INSPECTED")
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByAsc(WorkflowModelUpload::getCreatedAt)
                        .orderByAsc(WorkflowModelUpload::getId)
        );
    }

    private boolean isEligibleWorkflow(Workflow workflow) {
        return workflow != null
                && !Integer.valueOf(1).equals(workflow.getIsDeleted())
                && "ACCEPTED".equals(workflow.getStatus())
                && workflow.getModelDefinitionId() != null
                && !"RUNNING".equalsIgnoreCase(workflow.getFederatedStatus())
                && !"STARTING".equalsIgnoreCase(workflow.getFederatedStatus())
                && !"COMPLETED".equalsIgnoreCase(workflow.getFederatedStatus());
    }

    private int requiredModelCount(Workflow workflow) {
        if (workflow.getExpectedModelCount() != null && workflow.getExpectedModelCount() > 0) {
            return workflow.getExpectedModelCount();
        }
        return workflow.getClientModelCount() == null || workflow.getClientModelCount() <= 0
                ? 1 : workflow.getClientModelCount();
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8 * 1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String safeFailureMessage(Exception ex) {
        if (ex instanceof BusinessException business && StringUtils.hasText(business.getMessage())) {
            return business.getMessage().length() <= 255
                    ? business.getMessage() : business.getMessage().substring(0, 255);
        }
        return "联邦权重聚合失败，请查看服务端日志。";
    }
}
