package com.workflow.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeightsProtocolUploadServiceTest {

    Path tempDir;

    @Mock
    private ModelDefinitionService modelDefinitionService;
    @Mock
    private PythonWeightsProtocolClient pythonClient;
    @Mock
    private ModelAssetService modelAssetService;
    @Mock
    private WorkflowModelUploadStateService stateService;
    @Mock
    private WorkflowModelUploadMapper uploadMapper;
    @Mock
    private ControlledArtifactDeletionService deletionService;

    private WeightsProtocolProperties protocolProperties;
    private WorkflowStorageProperties storageProperties;
    private WeightsProtocolUploadService service;

    @BeforeEach
    void setUp() {
        tempDir = Path.of("target", "stage2a-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        protocolProperties = new WeightsProtocolProperties();
        protocolProperties.setEnabled(true);
        storageProperties = new WorkflowStorageProperties();
        storageProperties.setTempDir(tempDir.resolve("tmp").toString());
        storageProperties.setModelUploadDir(tempDir.resolve("model-uploads").toString());
        storageProperties.setWeightsAssetRootPath(tempDir.resolve("weights-assets").toString());
        service = new WeightsProtocolUploadService(
                protocolProperties,
                storageProperties,
                new ObjectMapper(),
                modelDefinitionService,
                pythonClient,
                modelAssetService,
                stateService,
                uploadMapper,
                deletionService
        );
        lenient().when(deletionService.deleteRegularFile(any(), any()))
                .thenReturn(ControlledArtifactDeletionService.DeleteResult.failed(null, "test cleanup deferred"));
    }

    @Test
    void acceptsValidatedDefinitionWeightsAndRegistersFormalAsset() throws Exception {
        Workflow workflow = workflow(1L);
        WorkflowModelUpload upload = upload();
        ModelDefinition definition = definition();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        when(modelDefinitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().readTree(descriptorJson()));
        when(pythonClient.validateUpload(any(), any(), any(), any())).thenReturn(passedResponse());
        when(modelAssetService.registerWeightsProtocolV1Asset(
                eq(workflow), eq(upload), eq(definition), any(Path.class), any())).thenReturn(99L);

        service.processDecryptedUpload(workflow, upload, new byte[]{1, 2, 3});

        Path finalWeights = tempDir.resolve("weights-assets/10/20/weights.pt");
        assertTrue(Files.isRegularFile(finalWeights));
        assertEquals(3L, Files.size(finalWeights));
        verify(stateService).markWeightsProtocolSucceeded(10L, 20L, finalWeights.toString(), 99L);
    }

    @Test
    void acceptsInternImageThroughTheSameGenericV1UploadOrchestration() throws Exception {
        Workflow workflow = workflow(2L);
        WorkflowModelUpload upload = upload();
        ModelDefinition definition = internImageDefinition();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(2L)).thenReturn(definition);
        when(modelDefinitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().readTree("""
                        {"definitionId":"INTERNIMAGE_T_UPERNET_WHEAT_V1",
                         "runtimeProfileId":"INTERNIMAGE_RUNTIME_V1"}
                        """));
        when(pythonClient.validateUpload(any(), any(), any(), any())).thenReturn(passedResponse());
        when(modelAssetService.registerWeightsProtocolV1Asset(
                eq(workflow), eq(upload), eq(definition), any(Path.class), any())).thenReturn(100L);

        service.processDecryptedUpload(workflow, upload, new byte[]{4, 5, 6});

        verify(modelDefinitionService).requireEnabledDefinition(2L);
        verify(modelAssetService).registerWeightsProtocolV1Asset(
                eq(workflow), eq(upload), eq(definition), any(Path.class), any());
        verify(stateService).markWeightsProtocolSucceeded(
                eq(10L), eq(20L), any(String.class), eq(100L));
    }

    @Test
    void rejectsMissingTrustedDefinitionBeforeInspection() {
        Workflow workflow = workflow(1L);
        WorkflowModelUpload upload = upload();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(1L))
                .thenThrow(new BusinessException("MODEL_DEFINITION_NOT_FOUND", "missing"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.processDecryptedUpload(workflow, upload, new byte[]{1}));

        assertEquals("MODEL_DEFINITION_NOT_FOUND", error.getCode());
        verify(pythonClient, never()).validateUpload(any(), any(), any(), any());
        verify(deletionService, atLeastOnce()).deleteRegularFile(
                argThat(path -> path != null && path.endsWith(".manifest.json")),
                eq(storageProperties.modelUploadDirPath()));
    }

    @Test
    void mapsInspectorAndCompatibilityFailuresAndNeverRegistersAsset() throws Exception {
        Workflow workflow = workflow(1L);
        WorkflowModelUpload upload = upload();
        ModelDefinition definition = definition();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        when(modelDefinitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().readTree(descriptorJson()));
        PythonWeightsValidationResponse response = new PythonWeightsValidationResponse();
        response.setPassed(false);
        response.setReasonCode("WEIGHTS_SHAPE_MISMATCH");
        when(pythonClient.validateUpload(any(), any(), any(), any())).thenReturn(response);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.processDecryptedUpload(workflow, upload, new byte[]{1}));

        assertEquals("WEIGHTS_SHAPE_MISMATCH", error.getCode());
        verify(modelAssetService, never()).registerWeightsProtocolV1Asset(any(), any(), any(), any(), any());
    }

    @Test
    void mapsShaFailureToStableClientError() throws Exception {
        Workflow workflow = workflow(1L);
        WorkflowModelUpload upload = upload();
        ModelDefinition definition = definition();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        when(modelDefinitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().readTree(descriptorJson()));
        PythonWeightsValidationResponse response = new PythonWeightsValidationResponse();
        response.setPassed(false);
        response.setReasonCode("WEIGHTS_SHA256_MISMATCH");
        when(pythonClient.validateUpload(any(), any(), any(), any())).thenReturn(response);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.processDecryptedUpload(workflow, upload, new byte[]{1}));

        assertEquals("WEIGHTS_SHA_MISMATCH", error.getCode());
    }

    @Test
    void keepsLegacyProtocolWhenDefinitionIsAbsentOrFlagIsDisabled() {
        Workflow legacy = workflow(null);
        assertEquals(WeightsProtocolUploadService.LEGACY_PROTOCOL, service.protocolFor(legacy));
        assertFalse(service.appliesTo(legacy));

        protocolProperties.setEnabled(false);
        assertEquals(WeightsProtocolUploadService.LEGACY_PROTOCOL, service.protocolFor(workflow(1L)));
    }

    @Test
    void usesWorkflowDefinitionInsteadOfUploadModelAssetAsIdentity() throws Exception {
        Workflow workflow = workflow(1L);
        WorkflowModelUpload upload = upload();
        upload.setModelAssetId(777L);
        ModelDefinition definition = definition();
        service.persistMetadata(workflow.getId(), upload.getId(), manifestJson(), descriptorJson());
        when(modelDefinitionService.requireEnabledDefinition(1L)).thenReturn(definition);
        when(modelDefinitionService.readTrustedDefinitionJson(definition))
                .thenReturn(new ObjectMapper().readTree(descriptorJson()));
        when(pythonClient.validateUpload(any(), any(), any(), any())).thenReturn(passedResponse());
        when(modelAssetService.registerWeightsProtocolV1Asset(
                eq(workflow), eq(upload), eq(definition), any(Path.class), any())).thenReturn(99L);

        service.processDecryptedUpload(workflow, upload, new byte[]{1, 2, 3});

        verify(modelDefinitionService).requireEnabledDefinition(1L);
        verify(modelDefinitionService, never()).requireEnabledDefinition(777L);
    }

    private Workflow workflow(Long modelDefinitionId) {
        Workflow workflow = new Workflow();
        workflow.setId(10L);
        workflow.setServerUserId(30L);
        workflow.setModelDefinitionId(modelDefinitionId);
        return workflow;
    }

    private WorkflowModelUpload upload() {
        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setId(20L);
        upload.setWorkflowId(10L);
        upload.setStoredFilename("v1_20_weights.pt.enc");
        upload.setEncryptedFilePath(tempDir.resolve("model-uploads/10/encrypted/v1_20_weights.pt.enc").toString());
        upload.setFileSha256("a".repeat(64));
        return upload;
    }

    private ModelDefinition definition() {
        ModelDefinition definition = new ModelDefinition();
        definition.setId(1L);
        definition.setCode("YOLOV8N_SHEEP_V1");
        definition.setDisplayName("YOLOv8n sheep detection");
        definition.setModelFamily("YOLO");
        definition.setModelVersion("YOLOv8");
        definition.setTaskType("DETECTION");
        return definition;
    }

    private ModelDefinition internImageDefinition() {
        ModelDefinition definition = new ModelDefinition();
        definition.setId(2L);
        definition.setCode("INTERNIMAGE_T_UPERNET_WHEAT_V1");
        definition.setDisplayName("InternImage-T wheat lodging segmentation");
        definition.setModelFamily("INTERNIMAGE");
        definition.setModelVersion("master@31c962dc");
        definition.setVariant("InternImage-T + UPerNet");
        definition.setTaskType("SEMANTIC_SEGMENTATION");
        definition.setRuntimeProfileId("INTERNIMAGE_RUNTIME_V1");
        return definition;
    }

    private PythonWeightsValidationResponse passedResponse() {
        PythonWeightsValidationResponse response = new PythonWeightsValidationResponse();
        response.setPassed(true);
        response.setReasonCode("AVAILABLE");
        response.setMessage("passed");
        return response;
    }

    private String manifestJson() {
        return "{\"schemaVersion\":\"1.0\",\"artifactType\":\"CLIENT_WEIGHTS\"}";
    }

    private String descriptorJson() {
        return "{\"schemaVersion\":\"1.0\",\"modelFamily\":\"YOLO\"}";
    }
}
