package com.workflow.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WeightsValidationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.service.ModelDefinitionService;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowWeightsValidationPreparationServiceTest {

    private static final String DEFINITION_ID = "YOLOV8N_SHEEP_V1";
    private static final String DEFINITION_SHA = "302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2";
    private static final String ARCHITECTURE_SHA = "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a";
    private static final String KEY_HASH = "1".repeat(64);
    private static final String SHAPE_HASH = "2".repeat(64);
    private static final String DTYPE_HASH = "3".repeat(64);

    @TempDir
    Path tempDir;

    @Mock
    ModelAssetMapper modelAssetMapper;
    @Mock
    ModelDefinitionService modelDefinitionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WeightsValidationProperties properties;
    private WorkflowWeightsValidationPreparationService service;
    private Workflow workflow;
    private ModelAsset asset;
    private ModelDefinition definition;
    private Path weightsPath;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tempDir.resolve("federated-models");
        Path assetDirectory = root.resolve("WF-37").resolve("weights-v1").resolve("asset-77");
        Files.createDirectories(assetDirectory);
        weightsPath = assetDirectory.resolve("global_weights.pt");
        Files.writeString(weightsPath, "global-weights-v1");

        WorkflowStorageProperties storage = new WorkflowStorageProperties();
        storage.setFederatedModelRootPath(root.toString());
        properties = new WeightsValidationProperties();
        properties.setEnabled(true);
        service = new WorkflowWeightsValidationPreparationService(
                properties, storage, modelAssetMapper, modelDefinitionService, objectMapper
        );

        workflow = new Workflow();
        workflow.setId(37L);
        workflow.setModelDefinitionId(1L);
        workflow.setFederatedStrategy(WorkflowWeightsFederatedAggregationService.STRATEGY);
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(77L);

        asset = new ModelAsset();
        asset.setId(77L);
        asset.setFilePath(weightsPath.toString());
        asset.setSourceType(AssetSourceCatalog.SOURCE_FEDERATED_WEIGHTS_V1);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_WEIGHTS_PROTOCOL_V1);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setStatus("READY");
        asset.setLastCheckStatus("OK");
        asset.setIsDeleted(0);

        definition = new ModelDefinition();
        definition.setId(1L);
        definition.setCode(DEFINITION_ID);
        definition.setDefinitionSha256(DEFINITION_SHA);
        definition.setArchitectureSignature(ARCHITECTURE_SHA);
        definition.setRuntimeProfileId("YOLO_RUNTIME_V1");

        lenient().when(modelAssetMapper.selectById(77L)).thenReturn(asset);
        lenient().when(modelDefinitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        lenient().when(modelDefinitionService.readTrustedDefinitionJson(definition)).thenReturn(trustedDefinition());
        writeSidecars(DEFINITION_ID, DEFINITION_SHA, ARCHITECTURE_SHA);
    }

    @Test
    void preparesRealV1IdentityWithoutComparingDatabasePkToProtocolId() {
        var prepared = service.prepare(workflow);

        assertEquals("YOLO_RUNTIME_V1", prepared.runtimeProfileId());
        assertEquals(DEFINITION_ID, prepared.trustedModelDefinition().path("definitionId").asText());
        assertEquals(77L, prepared.globalWeights().getAssetId());
        assertEquals(sha256(weightsPath), prepared.globalWeights().getExpectedSha256());
        assertEquals(355, prepared.globalWeights().getExpectedTensorCount());
    }

    @Test
    void rejectsDescriptorDefinitionMismatch() throws Exception {
        writeSidecars("OTHER_MODEL_V1", DEFINITION_SHA, ARCHITECTURE_SHA);

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH", error.getCode());
    }

    @Test
    void rejectsWrongDefinitionShaAndArchitecture() throws Exception {
        writeSidecars(DEFINITION_ID, "0".repeat(64), "9".repeat(64));

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH", error.getCode());
    }

    @Test
    void rejectsUntrustedAssetSource() {
        asset.setSourceType(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("WEIGHTS_VALIDATION_V1_ASSET_INVALID", error.getCode());
    }

    @Test
    void rejectsAssetThatIsNotReadyAndSuccessfullyChecked() {
        asset.setLastCheckStatus("FAILED");

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("WEIGHTS_VALIDATION_V1_ASSET_INVALID", error.getCode());
    }

    @Test
    void rejectsWhenFeatureFlagIsDisabled() {
        properties.setEnabled(false);

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("WEIGHTS_VALIDATION_V1_DISABLED", error.getCode());
    }

    @Test
    void rejectsWhenTrustedDefinitionRuntimeIsUnavailable() {
        when(modelDefinitionService.requireAvailableDefinition(1L)).thenThrow(
                new BusinessException(
                        "MODEL_DEFINITION_NOT_AVAILABLE",
                        "The trusted ModelDefinition runtime is unavailable."
                )
        );

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepare(workflow));

        assertEquals("MODEL_DEFINITION_NOT_AVAILABLE", error.getCode());
    }

    private JsonNode trustedDefinition() throws Exception {
        return objectMapper.readTree("""
                {
                  "definitionId":"YOLOV8N_SHEEP_V1",
                  "code":"YOLOV8N_SHEEP_V1",
                  "definitionSha256":"302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2",
                  "architectureSignature":"2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a"
                }
                """);
    }

    private void writeSidecars(String definitionId, String definitionSha, String architectureSha) throws Exception {
        String weightsSha = sha256(weightsPath);
        String modelReference = "\"modelDefinition\":{\"definitionId\":\"" + definitionId
                + "\",\"definitionSha256\":\"" + definitionSha + "\"}";
        String state = "\"tensorCount\":355,\"elementCount\":3021500,"
                + "\"stateDictKeyHash\":\"" + KEY_HASH + "\","
                + "\"shapeSignature\":\"" + SHAPE_HASH + "\","
                + "\"dtypeSignature\":\"" + DTYPE_HASH + "\"";
        Files.writeString(weightsPath.getParent().resolve("aggregation-manifest.json"),
                "{" + modelReference + ",\"architectureSignature\":\"" + architectureSha
                        + "\",\"weights\":{\"sha256\":\"" + weightsSha + "\"},\"state\":{" + state + "}}");
        Files.writeString(weightsPath.getParent().resolve("descriptor.json"),
                "{" + modelReference + ",\"extensions\":{\"architectureSignature\":\"" + architectureSha
                        + "\"},\"stateContract\":{\"tensorCount\":355,\"elementCount\":3021500,"
                        + "\"stateDictKeyHash\":\"" + KEY_HASH + "\",\"shapeSignature\":\"" + SHAPE_HASH
                        + "\",\"clientDtypeSignature\":\"" + DTYPE_HASH + "\"}}");
        Files.writeString(weightsPath.getParent().resolve("inspection-report.json"),
                "{\"passed\":true,\"weightsSha256\":\"" + weightsSha + "\"," + state + "}");
        Files.writeString(weightsPath.getParent().resolve("aggregation-report.json"),
                "{\"modelDefinitionId\":\"" + definitionId + "\",\"modelDefinitionCode\":\"" + DEFINITION_ID
                        + "\",\"definitionSha256\":\"" + definitionSha + "\",\"architectureSignature\":\""
                        + architectureSha + "\",\"output\":{\"sha256\":\"" + weightsSha + "\"," + state + "}}");
    }

    private String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
