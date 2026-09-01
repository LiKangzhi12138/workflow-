package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.Workflow;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.WorkflowStepService;
import com.workflow.service.python.PythonFederatedClient;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowFederatedAggregationServiceAvailabilityTest {

    @TempDir
    private Path tempDir;

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowModelUploadMapper workflowModelUploadMapper;
    @Mock
    private ModelAssetMapper modelAssetMapper;
    @Mock
    private ModelAssetService modelAssetService;
    @Mock
    private WorkflowStepService workflowStepService;
    @Mock
    private PythonFederatedClient pythonFederatedClient;
    @Mock
    private FederatedArtifactCleanupService federatedArtifactCleanupService;

    private WorkflowFederatedAggregationService service;
    private Path federatedRoot;

    @BeforeEach
    void setUp() {
        federatedRoot = tempDir.resolve("federated-models");
        WorkflowStorageProperties properties = new WorkflowStorageProperties();
        properties.setFederatedModelRootPath(federatedRoot.toString());
        service = new WorkflowFederatedAggregationService(
                workflowMapper,
                workflowModelUploadMapper,
                modelAssetMapper,
                modelAssetService,
                workflowStepService,
                properties,
                pythonFederatedClient,
                federatedArtifactCleanupService
        );
    }

    @Test
    void shouldRunIntermediateCleanupOnlyAfterTransactionCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.scheduleIntermediateCleanupAfterCommit(1L);

            verify(federatedArtifactCleanupService, never()).cleanupCompletedWorkflowArtifacts(1L);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(federatedArtifactCleanupService).cleanupCompletedWorkflowArtifacts(1L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldReportAvailableForReadableFederatedOutputFile() throws Exception {
        Workflow workflow = completedWorkflow(10L);
        Path modelPath = federatedRoot.resolve("WF-1").resolve("global_model.pt");
        Files.createDirectories(modelPath.getParent());
        Files.writeString(modelPath, "global-model");
        when(modelAssetMapper.selectById(10L)).thenReturn(federatedAsset(10L, modelPath, 0));

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertTrue(availability.available());
        assertEquals(modelPath.toAbsolutePath().normalize(), availability.modelPath());
        assertTrue(service.isFederatedModelReady(workflow));
    }

    @Test
    void shouldRejectWhenFederatedAggregationIsNotCompleted() {
        Workflow workflow = completedWorkflow(10L);
        workflow.setFederatedStatus("RUNNING");

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_NOT_COMPLETED", availability.code());
        assertEquals("联邦聚合尚未完成，暂时无法启动工作流验证。", availability.reason());
    }

    @Test
    void shouldRejectWhenFederatedModelAssetIdIsMissing() {
        Workflow workflow = completedWorkflow(null);

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_MODEL_NOT_GENERATED", availability.code());
        assertEquals("尚未生成联邦全局模型，无法启动工作流验证。", availability.reason());
    }

    @Test
    void shouldRejectSoftDeletedFederatedModelAsset() {
        Workflow workflow = completedWorkflow(10L);
        when(modelAssetMapper.selectById(10L)).thenReturn(federatedAsset(10L, federatedRoot.resolve("global_model.pt"), 1));

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_MODEL_DELETED", availability.code());
        assertEquals("联邦全局模型已删除，无法再次启动工作流验证。", availability.reason());
    }

    @Test
    void shouldRejectMissingFederatedModelFile() {
        Workflow workflow = completedWorkflow(10L);
        when(modelAssetMapper.selectById(10L)).thenReturn(federatedAsset(10L, federatedRoot.resolve("missing.pt"), 0));

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_MODEL_FILE_MISSING", availability.code());
        assertEquals("联邦全局模型文件不存在，请检查模型资产状态。", availability.reason());
    }

    @Test
    void shouldRejectNonFederatedOutputAssetEvenWhenFileExists() throws Exception {
        Workflow workflow = completedWorkflow(10L);
        Path modelPath = federatedRoot.resolve("participant.pt");
        Files.createDirectories(modelPath.getParent());
        Files.writeString(modelPath, "participant-model");
        ModelAsset asset = federatedAsset(10L, modelPath, 0);
        asset.setSourceType(AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT);
        when(modelAssetMapper.selectById(10L)).thenReturn(asset);

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_MODEL_SOURCE_INVALID", availability.code());
        assertEquals("工作流关联的模型不是联邦聚合全局模型，无法启动工作流验证。", availability.reason());
    }

    @Test
    void shouldRejectFederatedOutputOutsideControlledRoot() throws Exception {
        Workflow workflow = completedWorkflow(10L);
        Path outsidePath = tempDir.resolve("outside-global.pt");
        Files.writeString(outsidePath, "outside-model");
        when(modelAssetMapper.selectById(10L)).thenReturn(federatedAsset(10L, outsidePath, 0));

        var availability = service.resolveFederatedModelAvailability(workflow);

        assertFalse(availability.available());
        assertEquals("FEDERATED_MODEL_PATH_INVALID", availability.code());
    }

    private Workflow completedWorkflow(Long assetId) {
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(assetId);
        return workflow;
    }

    private ModelAsset federatedAsset(Long id, Path filePath, int isDeleted) {
        ModelAsset asset = new ModelAsset();
        asset.setId(id);
        asset.setSourceType(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
        asset.setFilePath(filePath.toString());
        asset.setIsDeleted(isDeleted);
        return asset;
    }
}
