package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class FederatedGlobalModelDeletionServiceTest {

    @TempDir
    private Path tempDir;

    private WorkflowMapper workflowMapper;
    private StandaloneValidationMapper standaloneValidationMapper;
    private ControlledArtifactDeletionService deletionService;
    private FederatedGlobalModelDeletionService service;
    private Path federatedRoot;
    private Path globalModelPath;
    private ModelAsset asset;
    private SysUser server;
    private Workflow workflow;

    @BeforeEach
    void setUp() throws Exception {
        workflowMapper = mock(WorkflowMapper.class);
        standaloneValidationMapper = mock(StandaloneValidationMapper.class);
        deletionService = spy(new ControlledArtifactDeletionService());

        federatedRoot = tempDir.resolve("federated-models");
        globalModelPath = federatedRoot.resolve("WF001").resolve("global_model.pt");
        Files.createDirectories(globalModelPath.getParent());
        Files.writeString(globalModelPath, "global-model");

        WorkflowStorageProperties properties = new WorkflowStorageProperties();
        properties.setFederatedModelRootPath(federatedRoot.toString());
        service = new FederatedGlobalModelDeletionService(
                workflowMapper,
                standaloneValidationMapper,
                properties,
                deletionService
        );

        server = user(20L, "SERVER");
        asset = federatedAsset(90L, 20L, globalModelPath);
        workflow = workflow(1L, "COMPLETED");
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow));
        when(standaloneValidationMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void shouldDeletePhysicalFileOnlyAfterTransactionCommit() {
        var plan = service.prepareDeletion(asset, server);
        initTransactionSynchronization();
        try {
            service.schedulePhysicalDeletionAfterCommit(plan);
            assertTrue(Files.exists(globalModelPath));

            commitRegisteredSynchronizations();

            assertFalse(Files.exists(globalModelPath));
        } finally {
            clearTransactionSynchronization();
        }
    }

    @Test
    void shouldAllowCompletedFailedAndWithdrawnWorkflowStates() {
        for (String status : List.of("COMPLETED", "FAILED", "WITHDRAWN")) {
            workflow.setStatus(status);
            var plan = service.prepareDeletion(asset, server);
            assertEquals(asset.getId(), plan.modelAssetId());
        }
    }

    @Test
    void shouldRejectWorkflowValidationInProgress() {
        workflow.setStatus("VALIDATING");

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_WORKFLOW_VALIDATION_RUNNING", error.getCode());
        assertTrue(Files.exists(globalModelPath));
    }

    @Test
    void shouldRejectNonTerminalWorkflowState() {
        workflow.setStatus("ACCEPTED");

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_WORKFLOW_ACTIVE", error.getCode());
    }

    @Test
    void shouldRejectActiveStandaloneValidation() {
        when(standaloneValidationMapper.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_STANDALONE_VALIDATION_RUNNING", error.getCode());
        assertTrue(Files.exists(globalModelPath));
    }

    @Test
    void shouldRejectClientAndDifferentServerOwner() {
        BusinessException clientError = assertThrows(
                BusinessException.class,
                () -> service.prepareDeletion(asset, user(20L, "CLIENT"))
        );
        BusinessException ownerError = assertThrows(
                BusinessException.class,
                () -> service.prepareDeletion(asset, user(21L, "SERVER"))
        );

        assertEquals("FEDERATED_MODEL_DELETE_FORBIDDEN", clientError.getCode());
        assertEquals("FEDERATED_MODEL_DELETE_FORBIDDEN", ownerError.getCode());
    }

    @Test
    void shouldRejectSharedFederatedAssetReference() {
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow, workflow(2L, "COMPLETED")));

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_SHARED_REFERENCE", error.getCode());
        assertTrue(Files.exists(globalModelPath));
    }

    @Test
    void shouldAllowSoftDeleteWhenPhysicalFileIsAlreadyMissing() throws Exception {
        Files.delete(globalModelPath);

        var plan = service.prepareDeletion(asset, server);

        assertEquals(ControlledArtifactDeletionService.InspectionStatus.MISSING, plan.initialFileStatus());
    }

    @Test
    void shouldKeepSoftDeleteOutcomeWhenPhysicalDeletionFails() {
        var plan = service.prepareDeletion(asset, server);
        doReturn(ControlledArtifactDeletionService.DeleteResult.failed(globalModelPath, "file locked"))
                .when(deletionService)
                .deleteRegularFile(eq(globalModelPath.toString()), eq(federatedRoot.toAbsolutePath().normalize()));
        initTransactionSynchronization();
        try {
            service.schedulePhysicalDeletionAfterCommit(plan);

            commitRegisteredSynchronizations();

            assertTrue(Files.exists(globalModelPath));
        } finally {
            clearTransactionSynchronization();
        }
    }

    @Test
    void shouldRejectPathOutsideFederatedRoot() throws Exception {
        Path outside = tempDir.resolve("outside-global.pt");
        Files.writeString(outside, "outside");
        asset.setFilePath(outside.toString());

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_PATH_UNSAFE", error.getCode());
        assertTrue(Files.exists(outside));
    }

    @Test
    void shouldRejectSymbolicLinkWhenEnvironmentSupportsIt() throws Exception {
        Path target = tempDir.resolve("target-global.pt");
        Path link = federatedRoot.resolve("WF002").resolve("global_model.pt");
        Files.writeString(target, "target");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            assumeTrue(false, "当前 Windows 权限或文件系统不支持创建符号链接");
        }
        asset.setFilePath(link.toString());

        BusinessException error = assertThrows(BusinessException.class, () -> service.prepareDeletion(asset, server));

        assertEquals("FEDERATED_MODEL_PATH_UNSAFE", error.getCode());
        assertTrue(Files.exists(target));
    }

    private void initTransactionSynchronization() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void commitRegisteredSynchronizations() {
        org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
    }

    private void clearTransactionSynchronization() {
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private ModelAsset federatedAsset(Long id, Long ownerId, Path path) {
        ModelAsset value = new ModelAsset();
        value.setId(id);
        value.setOwnerUserId(ownerId);
        value.setOwnerRoleCode("SERVER");
        value.setSourceType(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
        value.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        value.setFilePath(path.toString());
        value.setIsDeleted(0);
        return value;
    }

    private Workflow workflow(Long id, String status) {
        Workflow value = new Workflow();
        value.setId(id);
        value.setFederatedModelAssetId(asset == null ? 90L : asset.getId());
        value.setFederatedStatus("COMPLETED");
        value.setStatus(status);
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
