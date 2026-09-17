package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.WeightsFedAvgProperties;
import com.workflow.config.WeightsProtocolProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonWeightsFedAvgResponse;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.ModelDefinitionService;
import com.workflow.service.WorkflowStepService;
import com.workflow.service.python.PythonWeightsFedAvgClient;
import com.workflow.support.AssetSourceCatalog;
import com.workflow.support.WorkflowCurrentStepSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowWeightsFederatedAggregationServiceTest {

    private static final String PROTOCOL_DEFINITION_ID = "YOLOV8N_SHEEP_V1";
    private static final String DEFINITION_SHA =
            "302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2";
    private static final String ARCHITECTURE_SIGNATURE =
            "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a";

    @TempDir
    Path tempDir;

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowModelUploadMapper uploadMapper;
    @Mock ModelAssetMapper assetMapper;
    @Mock ModelDefinitionService definitionService;
    @Mock ModelAssetService modelAssetService;
    @Mock PythonWeightsFedAvgClient pythonClient;
    @Mock WorkflowStepService workflowStepService;
    @Mock ControlledArtifactDeletionService deletionService;

    private WeightsProtocolProperties protocol;
    private WeightsFedAvgProperties fedAvg;
    private WorkflowStorageProperties storage;
    private WorkflowWeightsFederatedAggregationService service;
    private Workflow workflow;
    private WorkflowModelUpload upload;
    private ModelAsset asset;
    private ModelDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        protocol = new WeightsProtocolProperties();
        protocol.setEnabled(true);
        fedAvg = new WeightsFedAvgProperties();
        fedAvg.setEnabled(true);
        storage = new WorkflowStorageProperties();
        storage.setWeightsAssetRootPath(tempDir.resolve("weights-assets").toString());
        storage.setFederatedModelRootPath(tempDir.resolve("federated-models").toString());
        service = new WorkflowWeightsFederatedAggregationService(
                protocol, fedAvg, storage, workflowMapper, uploadMapper, assetMapper,
                definitionService, modelAssetService, pythonClient, workflowStepService,
                deletionService, new ObjectMapper());

        workflow = new Workflow();
        workflow.setId(10L);
        workflow.setWorkflowCode("WF-10");
        workflow.setStatus("ACCEPTED");
        workflow.setFederatedStatus("PENDING");
        workflow.setModelDefinitionId(1L);
        workflow.setServerUserId(30L);
        workflow.setExpectedModelCount(1);

        upload = new WorkflowModelUpload();
        upload.setId(20L);
        upload.setWorkflowId(10L);
        upload.setServerModelAssetId(40L);
        upload.setUploadStatus("COMPLETED");
        upload.setAggregationStatus("INSPECTED");
        upload.setIsDeleted(0);

        Path assetDir = tempDir.resolve("weights-assets/10/20");
        Files.createDirectories(assetDir);
        Path weights = assetDir.resolve("weights.pt");
        Files.writeString(weights, "trusted-weights");
        writeIdentitySidecars(
                PROTOCOL_DEFINITION_ID, DEFINITION_SHA,
                PROTOCOL_DEFINITION_ID, DEFINITION_SHA,
                ARCHITECTURE_SIGNATURE, ARCHITECTURE_SIGNATURE);
        Files.writeString(assetDir.resolve("inspection-report.json"), "{\"passed\":true}");
        upload.setFileSha256(sha256(weights));

        asset = new ModelAsset();
        asset.setId(40L);
        asset.setOwnerUserId(30L);
        asset.setOwnerRoleCode("SERVER");
        asset.setSourceType(AssetSourceCatalog.SOURCE_WORKFLOW_WEIGHTS_V1);
        asset.setImportMode(AssetSourceCatalog.IMPORT_MODE_WEIGHTS_PROTOCOL_V1);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setStatus("READY");
        asset.setLastCheckStatus("OK");
        asset.setFilePathValidated(1);
        asset.setIsDeleted(0);
        asset.setFilePath(weights.toString());

        definition = new ModelDefinition();
        definition.setId(1L);
        definition.setCode(PROTOCOL_DEFINITION_ID);
        definition.setDisplayName("YOLOv8n sheep");
        definition.setModelFamily("YOLO");
        definition.setModelVersion("YOLOv8");
        definition.setTaskType("DETECTION");
        definition.setDefinitionSha256(DEFINITION_SHA);
        definition.setArchitectureSignature(ARCHITECTURE_SIGNATURE);
    }

    @Test
    void validV1AssetTriggersAndCompletesExactlyOnce() throws Exception {
        arrangeReady();
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(1);
        when(workflowMapper.startWeightsFedAvgV1(10L, WorkflowCurrentStepSupport.FEDERATED_AGGREGATING)).thenReturn(1);
        when(pythonClient.aggregate(any())).thenAnswer(invocation -> successfulResponse(
                Path.of(invocation.getArgument(0, com.workflow.dto.python.PythonWeightsFedAvgRequest.class)
                        .getOutputDirectory())));
        when(modelAssetService.registerFederatedWeightsV1Asset(
                eq(workflow), eq(definition), any(), eq(1), any())).thenReturn(90L);
        when(workflowMapper.completeWeightsFedAvgV1(
                10L, 90L, WorkflowCurrentStepSupport.FEDERATED_COMPLETED)).thenReturn(1);

        assertTrue(service.triggerIfReady(10L, 30L, "test"));

        verify(pythonClient).aggregate(any());
        verify(workflowMapper).startWeightsFedAvgV1(10L, WorkflowCurrentStepSupport.FEDERATED_AGGREGATING);
        verify(workflowMapper).completeWeightsFedAvgV1(10L, 90L, WorkflowCurrentStepSupport.FEDERATED_COMPLETED);
    }

    @Test
    void disabledFlagStopsAtInspectedWithoutPythonCall() {
        fedAvg.setEnabled(false);
        assertFalse(service.triggerIfReady(10L, 30L, "test"));
        verify(pythonClient, never()).aggregate(any());
        verify(workflowMapper, never()).claimWeightsFedAvgV1(anyLong(), any());
    }

    @Test
    void legacyOrMixedAssetIsRejectedAndNeverCallsPython() {
        arrangeReady();
        asset.setSourceType(AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT);
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(1);

        assertFalse(service.triggerIfReady(10L, 30L, "test"));

        verify(pythonClient, never()).aggregate(any());
        verify(workflowMapper).failWeightsFedAvgV1(
                10L, WorkflowCurrentStepSupport.FEDERATED_FAILED, "参与联邦聚合的资产不是可信 V1 权重资产。");
    }

    @Test
    void duplicateTriggerLosesAtomicClaimAndDoesNothing() {
        arrangeUntilClaim();
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(0);
        assertFalse(service.triggerIfReady(10L, 30L, "test"));
        verify(pythonClient, never()).aggregate(any());
    }

    @Test
    void pythonFailureDoesNotLeaveWorkflowAggregating() {
        arrangeReady();
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(1);
        when(workflowMapper.startWeightsFedAvgV1(10L, WorkflowCurrentStepSupport.FEDERATED_AGGREGATING)).thenReturn(1);
        when(pythonClient.aggregate(any())).thenThrow(new RuntimeException("offline"));

        assertFalse(service.triggerIfReady(10L, 30L, "test"));

        verify(workflowMapper).failWeightsFedAvgV1(
                10L, WorkflowCurrentStepSupport.FEDERATED_FAILED, "联邦权重聚合失败，请查看服务端日志。");
        verify(workflowMapper, never()).completeWeightsFedAvgV1(anyLong(), anyLong(), any());
    }

    @Test
    void wrongProtocolDefinitionIdIsRejectedBeforePythonCall() throws Exception {
        arrangeReady();
        writeIdentitySidecars(
                "OTHER_MODEL_V1", DEFINITION_SHA,
                "OTHER_MODEL_V1", DEFINITION_SHA,
                ARCHITECTURE_SIGNATURE, ARCHITECTURE_SIGNATURE);
        assertIdentityRejected();
    }

    @Test
    void wrongDefinitionShaIsRejectedBeforePythonCall() throws Exception {
        arrangeReady();
        writeIdentitySidecars(
                PROTOCOL_DEFINITION_ID, "wrong-definition-sha",
                PROTOCOL_DEFINITION_ID, "wrong-definition-sha",
                ARCHITECTURE_SIGNATURE, ARCHITECTURE_SIGNATURE);
        assertIdentityRejected();
    }

    @Test
    void wrongDescriptorDefinitionIsRejectedBeforePythonCall() throws Exception {
        arrangeReady();
        writeIdentitySidecars(
                PROTOCOL_DEFINITION_ID, DEFINITION_SHA,
                "OTHER_MODEL_V1", DEFINITION_SHA,
                ARCHITECTURE_SIGNATURE, ARCHITECTURE_SIGNATURE);
        assertIdentityRejected();
    }

    @Test
    void manifestDescriptorIdentityDisagreementIsRejectedBeforePythonCall() throws Exception {
        arrangeReady();
        writeIdentitySidecars(
                "OTHER_MODEL_V1", DEFINITION_SHA,
                "THIRD_MODEL_V1", DEFINITION_SHA,
                ARCHITECTURE_SIGNATURE, ARCHITECTURE_SIGNATURE);
        assertIdentityRejected();
    }

    @Test
    void wrongArchitectureSignatureIsRejectedBeforePythonCall() throws Exception {
        arrangeReady();
        writeIdentitySidecars(
                PROTOCOL_DEFINITION_ID, DEFINITION_SHA,
                PROTOCOL_DEFINITION_ID, DEFINITION_SHA,
                "wrong-architecture", ARCHITECTURE_SIGNATURE);
        assertIdentityRejected();
    }

    @Test
    void mismatchedPythonEvidenceIsRejectedWithoutRegisteringAsset() throws Exception {
        arrangeReady();
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(1);
        when(workflowMapper.startWeightsFedAvgV1(10L, WorkflowCurrentStepSupport.FEDERATED_AGGREGATING)).thenReturn(1);
        when(pythonClient.aggregate(any())).thenAnswer(invocation -> {
            PythonWeightsFedAvgResponse response = successfulResponse(
                    Path.of(invocation.getArgument(0, com.workflow.dto.python.PythonWeightsFedAvgRequest.class)
                            .getOutputDirectory()));
            response.setModelDefinitionCode("WRONG_DEFINITION");
            return response;
        });

        assertFalse(service.triggerIfReady(10L, 30L, "test"));

        verify(modelAssetService, never()).registerFederatedWeightsV1Asset(any(), any(), any(), anyInt(), any());
        verify(workflowMapper).failWeightsFedAvgV1(eq(10L), eq(WorkflowCurrentStepSupport.FEDERATED_FAILED), any());
    }

    @Test
    void insufficientInspectedInputsRemainUnclaimed() {
        workflow.setExpectedModelCount(2);
        arrangeUntilClaim();

        assertFalse(service.triggerIfReady(10L, 30L, "test"));

        verify(workflowMapper, never()).claimWeightsFedAvgV1(anyLong(), any());
        verify(pythonClient, never()).aggregate(any());
    }

    private void arrangeReady() {
        arrangeUntilClaim();
        when(assetMapper.selectById(40L)).thenReturn(asset);
        when(definitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        when(definitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().createObjectNode()
                        .put("definitionId", PROTOCOL_DEFINITION_ID)
                        .put("code", PROTOCOL_DEFINITION_ID)
                        .put("definitionSha256", DEFINITION_SHA)
                        .put("architectureSignature", ARCHITECTURE_SIGNATURE));
    }

    private void arrangeUntilClaim() {
        when(workflowMapper.selectById(10L)).thenReturn(workflow);
        when(uploadMapper.selectList(any())).thenReturn(List.of(upload));
    }

    private PythonWeightsFedAvgResponse successfulResponse(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path weights = directory.resolve("global_weights.pt");
        Files.writeString(weights, "global-weights");
        Files.writeString(directory.resolve("aggregation-manifest.json"), "{}");
        Files.writeString(directory.resolve("descriptor.json"), "{}");
        Files.writeString(directory.resolve("inspection-report.json"), "{}");
        Files.writeString(directory.resolve("aggregation-report.json"), "{}");
        PythonWeightsFedAvgResponse response = new PythonWeightsFedAvgResponse();
        response.setPassed(true);
        response.setWorkflowId(10L);
        response.setModelDefinitionId(PROTOCOL_DEFINITION_ID);
        response.setModelDefinitionCode(PROTOCOL_DEFINITION_ID);
        response.setInputCount(1);
        response.setInputAssetIds(List.of(40L));
        response.setAggregationMethod("EQUAL_FEDAVG");
        response.setOutputWeightsPath(weights.toString());
        response.setOutputManifestPath(directory.resolve("aggregation-manifest.json").toString());
        response.setOutputDescriptorPath(directory.resolve("descriptor.json").toString());
        response.setOutputInspectionPath(directory.resolve("inspection-report.json").toString());
        response.setOutputReportPath(directory.resolve("aggregation-report.json").toString());
        response.setOutputSha256(sha256(weights));
        response.setInspection(new ObjectMapper().createObjectNode().put("passed", true));
        return response;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void writeIdentitySidecars(
            String manifestDefinitionId,
            String manifestDefinitionSha,
            String descriptorDefinitionId,
            String descriptorDefinitionSha,
            String manifestArchitecture,
            String descriptorArchitecture
    ) throws Exception {
        Path assetDirectory = tempDir.resolve("weights-assets/10/20");
        Files.createDirectories(assetDirectory);
        ObjectMapper mapper = new ObjectMapper();
        var manifest = mapper.createObjectNode();
        manifest.putObject("modelDefinition")
                .put("definitionId", manifestDefinitionId)
                .put("definitionSha256", manifestDefinitionSha);
        manifest.put("architectureSignature", manifestArchitecture);
        Files.writeString(assetDirectory.resolve("manifest.json"), mapper.writeValueAsString(manifest));

        var descriptor = mapper.createObjectNode();
        descriptor.putObject("modelDefinition")
                .put("definitionId", descriptorDefinitionId)
                .put("definitionSha256", descriptorDefinitionSha);
        descriptor.putObject("extensions").put("architectureSignature", descriptorArchitecture);
        Files.writeString(assetDirectory.resolve("descriptor.json"), mapper.writeValueAsString(descriptor));
    }

    private void assertIdentityRejected() {
        when(workflowMapper.claimWeightsFedAvgV1(eq(10L), any())).thenReturn(1);

        assertFalse(service.triggerIfReady(10L, 30L, "test"));

        verify(pythonClient, never()).aggregate(any());
        verify(workflowMapper).failWeightsFedAvgV1(
                eq(10L), eq(WorkflowCurrentStepSupport.FEDERATED_FAILED), any());
    }
}
