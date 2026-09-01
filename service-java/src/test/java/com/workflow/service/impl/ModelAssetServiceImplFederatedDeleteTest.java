package com.workflow.service.impl;

import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelAssetServiceImplFederatedDeleteTest {

    private ModelAssetMapper modelAssetMapper;
    private WorkflowMapper workflowMapper;
    private LoginUserContext loginUserContext;
    private AssetStorageService assetStorageService;
    private FederatedGlobalModelDeletionService deletionService;
    private ModelAssetServiceImpl service;
    private SysUser server;

    @BeforeEach
    void setUp() {
        modelAssetMapper = mock(ModelAssetMapper.class);
        workflowMapper = mock(WorkflowMapper.class);
        loginUserContext = mock(LoginUserContext.class);
        assetStorageService = mock(AssetStorageService.class);
        deletionService = mock(FederatedGlobalModelDeletionService.class);
        service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                deletionService
        );
        server = user(20L, "SERVER");
        when(loginUserContext.getCurrentUser()).thenReturn(server);
    }

    @Test
    void shouldSoftDeleteFederatedOutputAndKeepWorkflowHistoryUntouched() {
        ModelAsset asset = asset(90L, 20L, AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
        Workflow historicalWorkflow = new Workflow();
        historicalWorkflow.setId(1L);
        historicalWorkflow.setFederatedStatus("COMPLETED");
        historicalWorkflow.setFederatedModelAssetId(asset.getId());
        historicalWorkflow.setMetricsJson("{\"accuracy\":0.91}");
        historicalWorkflow.setResultFilePath("results/job-result.json");
        historicalWorkflow.setResultRetentionStatus("SAVED");
        var plan = new FederatedGlobalModelDeletionService.DeletionPlan(
                asset.getId(),
                asset.getFilePath(),
                Path.of("federated-models").toAbsolutePath().normalize(),
                ControlledArtifactDeletionService.InspectionStatus.VALID
        );
        when(modelAssetMapper.selectOne(any())).thenReturn(asset);
        when(deletionService.prepareDeletion(asset, server)).thenReturn(plan);
        when(modelAssetMapper.softDeleteOwnedFederatedOutputAsset(asset.getId(), server.getId())).thenReturn(1);

        service.deleteModelAsset(asset.getId());

        verify(deletionService).schedulePhysicalDeletionAfterCommit(plan);
        verify(workflowMapper, never()).updateById(any(Workflow.class));
        assertEquals("COMPLETED", historicalWorkflow.getFederatedStatus());
        assertEquals(asset.getId(), historicalWorkflow.getFederatedModelAssetId());
        assertEquals("{\"accuracy\":0.91}", historicalWorkflow.getMetricsJson());
        assertEquals("results/job-result.json", historicalWorkflow.getResultFilePath());
        assertEquals("SAVED", historicalWorkflow.getResultRetentionStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BROWSER_UPLOAD", "SERVER_IMPORT", "WORKFLOW_DECRYPT"})
    void shouldKeepOrdinaryAssetDeleteSemantics(String sourceType) {
        ModelAsset asset = asset(91L, 20L, sourceType);
        when(modelAssetMapper.selectOne(any())).thenReturn(asset);
        when(workflowMapper.selectCount(any())).thenReturn(0L);

        service.deleteModelAsset(asset.getId());

        assertEquals(1, asset.getIsDeleted());
        verify(modelAssetMapper).updateById(asset);
        verify(deletionService, never()).prepareDeletion(any(), any());
        verify(deletionService, never()).schedulePhysicalDeletionAfterCommit(any());
    }

    @Test
    void shouldRejectClientOrDifferentServerThroughOwnedAssetLookup() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(30L, "CLIENT"));
        when(modelAssetMapper.selectOne(any())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteModelAsset(90L));

        assertEquals("MODEL_ASSET_NOT_FOUND", error.getCode());
        verify(deletionService, never()).prepareDeletion(any(), any());
    }

    @Test
    void shouldRejectDifferentServerThroughOwnedAssetLookup() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(21L, "SERVER"));
        when(modelAssetMapper.selectOne(any())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteModelAsset(90L));

        assertEquals("MODEL_ASSET_NOT_FOUND", error.getCode());
        verify(deletionService, never()).prepareDeletion(any(), any());
    }

    @Test
    void shouldNotSchedulePhysicalDeletionWhenSoftDeleteFails() {
        ModelAsset asset = asset(90L, 20L, AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
        var plan = new FederatedGlobalModelDeletionService.DeletionPlan(
                asset.getId(),
                asset.getFilePath(),
                Path.of("federated-models").toAbsolutePath().normalize(),
                ControlledArtifactDeletionService.InspectionStatus.VALID
        );
        when(modelAssetMapper.selectOne(any())).thenReturn(asset);
        when(deletionService.prepareDeletion(asset, server)).thenReturn(plan);
        when(modelAssetMapper.softDeleteOwnedFederatedOutputAsset(asset.getId(), server.getId())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteModelAsset(asset.getId()));

        assertEquals("FEDERATED_MODEL_SOFT_DELETE_FAILED", error.getCode());
        verify(deletionService, never()).schedulePhysicalDeletionAfterCommit(any());
    }

    private ModelAsset asset(Long id, Long ownerId, String sourceType) {
        ModelAsset value = new ModelAsset();
        value.setId(id);
        value.setOwnerUserId(ownerId);
        value.setOwnerRoleCode("SERVER");
        value.setSourceType(sourceType);
        value.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        value.setFilePath(Path.of("federated-models", "WF001", "global_model.pt").toAbsolutePath().normalize().toString());
        value.setIsDeleted(0);
        return value;
    }

    private SysUser user(Long id, String roleCode) {
        SysUser value = new SysUser();
        value.setId(id);
        value.setRoleCode(roleCode);
        return value;
    }
}
