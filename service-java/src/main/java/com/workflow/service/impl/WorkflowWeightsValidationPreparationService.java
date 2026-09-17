package com.workflow.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WeightsValidationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonWeightsValidationJobInput;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.service.ModelDefinitionService;
import com.workflow.support.AssetSourceCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowWeightsValidationPreparationService {

    public static final String VALIDATION_MODE_V1 = "WEIGHTS_PROTOCOL_V1";
    public static final String VALIDATION_MODE_LEGACY = "LEGACY_CHECKPOINT";
    private static final long MAX_SIDECAR_BYTES = 10L * 1024L * 1024L;

    private final WeightsValidationProperties properties;
    private final WorkflowStorageProperties storageProperties;
    private final ModelAssetMapper modelAssetMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final ObjectMapper objectMapper;

    public boolean isV1Workflow(Workflow workflow) {
        return workflow != null
                && workflow.getModelDefinitionId() != null
                && WorkflowWeightsFederatedAggregationService.STRATEGY.equals(workflow.getFederatedStrategy());
    }

    public PreparedValidation prepare(Workflow workflow) {
        if (!isV1Workflow(workflow)) {
            throw new IllegalArgumentException("Workflow is not a weights-only v1 workflow.");
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(
                    "WEIGHTS_VALIDATION_V1_DISABLED",
                    "当前 weights-only Validation 尚未启用。"
            );
        }
        if (!"COMPLETED".equals(workflow.getFederatedStatus())
                || workflow.getFederatedModelAssetId() == null) {
            throw new BusinessException(
                    "WEIGHTS_VALIDATION_V1_NOT_READY",
                    "当前工作流的联邦权重尚未准备完成。"
            );
        }

        ModelAsset asset = modelAssetMapper.selectById(workflow.getFederatedModelAssetId());
        requireValidAsset(asset);

        ModelDefinition definition = modelDefinitionService.requireEnabledDefinition(workflow.getModelDefinitionId());
        JsonNode trustedDefinition = modelDefinitionService.readTrustedDefinitionJson(definition);
        modelDefinitionService.requireAvailableDefinition(definition.getId());

        Path weightsPath = requireControlledRegularFile(asset.getFilePath());
        if (!"global_weights.pt".equals(weightsPath.getFileName().toString())) {
            throw invalidAsset("Global weights file name is not server-controlled.");
        }
        Path directory = weightsPath.getParent();
        Path manifestPath = requireSidecar(directory, "aggregation-manifest.json");
        Path descriptorPath = requireSidecar(directory, "descriptor.json");
        Path inspectionPath = requireSidecar(directory, "inspection-report.json");
        Path reportPath = requireSidecar(directory, "aggregation-report.json");

        JsonNode manifest = readSidecar(manifestPath);
        JsonNode descriptor = readSidecar(descriptorPath);
        JsonNode inspection = readSidecar(inspectionPath);
        JsonNode report = readSidecar(reportPath);

        String protocolDefinitionId = requiredText(trustedDefinition, "definitionId");
        String definitionCode = requiredText(trustedDefinition, "code");
        String definitionSha = requiredText(trustedDefinition, "definitionSha256");
        String architectureSignature = requiredText(trustedDefinition, "architectureSignature");
        if (!Objects.equals(definition.getCode(), definitionCode)) {
            throw identityMismatch(workflow, asset, definition, protocolDefinitionId, null, null, "trusted-definition");
        }

        validateDefinitionIdentity(
                workflow,
                asset,
                definition,
                protocolDefinitionId,
                definitionSha,
                architectureSignature,
                manifest.path("modelDefinition"),
                manifest.path("architectureSignature").asText(null),
                "manifest"
        );
        validateDefinitionIdentity(
                workflow,
                asset,
                definition,
                protocolDefinitionId,
                definitionSha,
                architectureSignature,
                descriptor.path("modelDefinition"),
                descriptor.path("extensions").path("architectureSignature").asText(null),
                "descriptor"
        );
        validateReportIdentity(
                workflow, asset, definition, protocolDefinitionId, definitionCode,
                definitionSha, architectureSignature, report
        );

        String actualSha = sha256(weightsPath);
        requireEqualSha(actualSha, manifest.path("weights").path("sha256").asText(null), "manifest weights SHA");
        requireEqualSha(actualSha, inspection.path("weightsSha256").asText(null), "inspection weights SHA");
        requireEqualSha(actualSha, report.path("output").path("sha256").asText(null), "aggregation report weights SHA");
        if (!inspection.path("passed").asBoolean(false)) {
            throw invalidAsset("Global weights inspection evidence is not successful.");
        }

        JsonNode state = manifest.path("state");
        JsonNode contract = descriptor.path("stateContract");
        JsonNode reportOutput = report.path("output");
        requireEqualNumber(state, "tensorCount", inspection, "tensorCount", "inspection tensor count");
        requireEqualNumber(state, "tensorCount", reportOutput, "tensorCount", "report tensor count");
        requireEqualNumber(state, "elementCount", inspection, "elementCount", "inspection element count");
        requireEqualNumber(state, "elementCount", reportOutput, "elementCount", "report element count");
        requireEqualText(state, "stateDictKeyHash", contract, "stateDictKeyHash", "descriptor key hash");
        requireEqualText(state, "stateDictKeyHash", inspection, "stateDictKeyHash", "inspection key hash");
        requireEqualText(state, "stateDictKeyHash", reportOutput, "stateDictKeyHash", "report key hash");
        requireEqualText(state, "shapeSignature", contract, "shapeSignature", "descriptor shape signature");
        requireEqualText(state, "shapeSignature", inspection, "shapeSignature", "inspection shape signature");
        requireEqualText(state, "shapeSignature", reportOutput, "shapeSignature", "report shape signature");
        requireEqualText(state, "dtypeSignature", contract, "clientDtypeSignature", "descriptor dtype signature");
        requireEqualText(state, "dtypeSignature", inspection, "dtypeSignature", "inspection dtype signature");
        requireEqualText(state, "dtypeSignature", reportOutput, "dtypeSignature", "report dtype signature");

        PythonWeightsValidationJobInput input = new PythonWeightsValidationJobInput();
        input.setAssetId(asset.getId());
        input.setWeightsPath(weightsPath.toString());
        input.setExpectedSha256(actualSha);
        input.setManifestPath(manifestPath.toString());
        input.setDescriptorPath(descriptorPath.toString());
        input.setInspectionReportPath(inspectionPath.toString());
        input.setAggregationReportPath(reportPath.toString());
        input.setExpectedTensorCount(state.path("tensorCount").asInt());
        input.setExpectedElementCount(state.path("elementCount").asLong());
        input.setExpectedStateDictKeyHash(requiredText(state, "stateDictKeyHash"));
        input.setExpectedShapeSignature(requiredText(state, "shapeSignature"));
        input.setExpectedDtypeSignature(requiredText(state, "dtypeSignature"));

        log.info(
                "Prepared weights-only validation: workflowId={}, assetId={}, trustedDbModelDefinitionId={}, "
                        + "trustedProtocolDefinitionId={}, definitionShaPrefix={}, architectureSignaturePrefix={}, weightsShaPrefix={}",
                workflow.getId(), asset.getId(), definition.getId(), protocolDefinitionId,
                prefix(definitionSha), prefix(architectureSignature), prefix(actualSha)
        );
        return new PreparedValidation(
                weightsPath.toString(), definition.getRuntimeProfileId(), trustedDefinition, input
        );
    }

    private void requireValidAsset(ModelAsset asset) {
        boolean valid = asset != null
                && AssetSourceCatalog.SOURCE_FEDERATED_WEIGHTS_V1.equals(asset.getSourceType())
                && AssetSourceCatalog.IMPORT_MODE_WEIGHTS_PROTOCOL_V1.equals(asset.getImportMode())
                && AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET.equals(asset.getRecordMode())
                && "READY".equals(asset.getStatus())
                && "OK".equals(asset.getLastCheckStatus())
                && !Objects.equals(asset.getIsDeleted(), 1);
        if (!valid) {
            throw invalidAsset("Global asset is not a trusted FEDERATED_WEIGHTS_V1 asset.");
        }
    }

    private void validateDefinitionIdentity(
            Workflow workflow,
            ModelAsset asset,
            ModelDefinition definition,
            String expectedId,
            String expectedSha,
            String expectedArchitecture,
            JsonNode reference,
            String actualArchitecture,
            String source
    ) {
        String actualId = reference.path("definitionId").asText(null);
        String actualSha = reference.path("definitionSha256").asText(null);
        if (!Objects.equals(expectedId, actualId)
                || !equalsIgnoreCase(expectedSha, actualSha)
                || !equalsIgnoreCase(expectedArchitecture, actualArchitecture)) {
            throw identityMismatch(workflow, asset, definition, expectedId, actualId, actualSha, source);
        }
    }

    private void validateReportIdentity(
            Workflow workflow,
            ModelAsset asset,
            ModelDefinition definition,
            String expectedId,
            String expectedCode,
            String expectedSha,
            String expectedArchitecture,
            JsonNode report
    ) {
        String actualId = report.path("modelDefinitionId").asText(null);
        String actualSha = report.path("definitionSha256").asText(null);
        if (!Objects.equals(expectedId, actualId)
                || !Objects.equals(expectedCode, report.path("modelDefinitionCode").asText(null))
                || !equalsIgnoreCase(expectedSha, actualSha)
                || !equalsIgnoreCase(expectedArchitecture, report.path("architectureSignature").asText(null))) {
            throw identityMismatch(workflow, asset, definition, expectedId, actualId, actualSha, "aggregation-report");
        }
    }

    private BusinessException identityMismatch(
            Workflow workflow,
            ModelAsset asset,
            ModelDefinition definition,
            String expectedId,
            String actualId,
            String actualSha,
            String source
    ) {
        log.warn(
                "Weights validation Definition mismatch: workflowId={}, assetId={}, trustedDbModelDefinitionId={}, "
                        + "trustedProtocolDefinitionId={}, trustedDefinitionShaPrefix={}, assetDefinitionId={}, "
                        + "assetDefinitionShaPrefix={}, identitySource={}",
                workflow.getId(), asset.getId(), definition.getId(), expectedId,
                prefix(definition.getDefinitionSha256()), actualId, prefix(actualSha), source
        );
        return new BusinessException(
                "WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH",
                "联邦权重与当前工作流的模型定义不匹配。"
        );
    }

    private Path requireControlledRegularFile(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            throw invalidAsset("Global weights path is missing.");
        }
        Path root = storageProperties.federatedModelRootDirPath().toAbsolutePath().normalize();
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            throw invalidAsset("Global weights path is outside the controlled federated model root.");
        }
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw invalidAsset("Symbolic links are not allowed in global weights paths.");
            }
        }
        return candidate;
    }

    private Path requireSidecar(Path directory, String fileName) {
        Path path = directory.resolve(fileName).toAbsolutePath().normalize();
        if (!path.getParent().equals(directory)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw invalidAsset("Required global weights evidence is missing: " + fileName);
        }
        return path;
    }

    private JsonNode readSidecar(Path path) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_SIDECAR_BYTES) {
                throw invalidAsset("Global weights evidence size is invalid: " + path.getFileName());
            }
            return objectMapper.readTree(path.toFile());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    "WEIGHTS_VALIDATION_V1_EVIDENCE_INVALID",
                    "联邦权重验证证据不可读取。",
                    ex
            );
        }
    }

    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException(
                    "WEIGHTS_VALIDATION_V1_ASSET_INVALID",
                    "联邦权重完整性校验失败。",
                    ex
            );
        }
    }

    private void requireEqualSha(String expected, String actual, String label) {
        if (!equalsIgnoreCase(expected, actual)) {
            throw invalidAsset(label + " does not match the stored global weights.");
        }
    }

    private void requireEqualText(JsonNode left, String leftField, JsonNode right, String rightField, String label) {
        String expected = left.path(leftField).asText(null);
        String actual = right.path(rightField).asText(null);
        if (!StringUtils.hasText(expected) || !Objects.equals(expected, actual)) {
            throw invalidAsset(label + " is inconsistent.");
        }
    }

    private void requireEqualNumber(JsonNode left, String leftField, JsonNode right, String rightField, String label) {
        if (!left.path(leftField).isIntegralNumber()
                || !right.path(rightField).isIntegralNumber()
                || left.path(leftField).asLong() != right.path(rightField).asLong()) {
            throw invalidAsset(label + " is inconsistent.");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (!StringUtils.hasText(value)) {
            throw invalidAsset("Required evidence field is missing: " + field);
        }
        return value;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.equalsIgnoreCase(right);
    }

    private String prefix(String value) {
        return StringUtils.hasText(value) ? value.substring(0, Math.min(12, value.length())) : "<missing>";
    }

    private BusinessException invalidAsset(String detail) {
        log.warn("Weights validation global asset rejected: {}", detail);
        return new BusinessException(
                "WEIGHTS_VALIDATION_V1_ASSET_INVALID",
                "联邦权重资产完整性校验失败。"
        );
    }

    public record PreparedValidation(
            String modelPath,
            String runtimeProfileId,
            JsonNode trustedModelDefinition,
            PythonWeightsValidationJobInput globalWeights
    ) {
    }
}
