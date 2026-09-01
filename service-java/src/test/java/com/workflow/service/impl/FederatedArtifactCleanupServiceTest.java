package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class FederatedArtifactCleanupServiceTest {

    private static final long WORKFLOW_ID = 1L;
    private static final long SERVER_USER_ID = 100L;
    private static final long GLOBAL_ASSET_ID = 900L;

    @TempDir
    private Path tempDir;

    private WorkflowMapper workflowMapper;
    private WorkflowModelUploadMapper uploadMapper;
    private ModelAssetMapper modelAssetMapper;
    private StandaloneValidationMapper standaloneValidationMapper;
    private PlatformTransactionManager transactionManager;
    private ControlledArtifactDeletionService deletionService;
    private FederatedArtifactCleanupService cleanupService;
    private WorkflowStorageProperties properties;
    private Workflow workflow;
    private Path globalModelPath;
    private final Map<Long, WorkflowModelUpload> uploads = new LinkedHashMap<>();
    private final Map<Long, ModelAsset> assets = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        workflowMapper = mock(WorkflowMapper.class);
        uploadMapper = mock(WorkflowModelUploadMapper.class);
        modelAssetMapper = mock(ModelAssetMapper.class);
        standaloneValidationMapper = mock(StandaloneValidationMapper.class);
        transactionManager = mock(PlatformTransactionManager.class);
        deletionService = spy(new ControlledArtifactDeletionService());

        properties = new WorkflowStorageProperties();
        properties.setRootPath(tempDir.resolve("storage").toString());
        properties.setModelUploadDir(tempDir.resolve("storage/model-uploads").toString());
        properties.setModelRootPath(tempDir.resolve("storage/models").toString());
        properties.setFederatedModelRootPath(tempDir.resolve("storage/federated-models").toString());

        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(workflowMapper.selectById(WORKFLOW_ID)).thenAnswer(invocation -> workflow);
        when(uploadMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(uploads.values()));
        when(uploadMapper.selectById(anyLong())).thenAnswer(invocation -> uploads.get(invocation.getArgument(0)));
        when(modelAssetMapper.selectById(anyLong())).thenAnswer(invocation -> assets.get(invocation.getArgument(0)));
        when(standaloneValidationMapper.selectCount(any())).thenReturn(0L);
        when(uploadMapper.clearEncryptedFilePath(anyLong(), eq(WORKFLOW_ID))).thenAnswer(invocation -> {
            WorkflowModelUpload upload = uploads.get(invocation.getArgument(0));
            if (upload == null) {
                return 0;
            }
            upload.setEncryptedFilePath(null);
            return 1;
        });
        when(uploadMapper.clearDecryptedFilePath(anyLong(), eq(WORKFLOW_ID))).thenAnswer(invocation -> {
            WorkflowModelUpload upload = uploads.get(invocation.getArgument(0));
            if (upload == null) {
                return 0;
            }
            upload.setDecryptedFilePath(null);
            return 1;
        });
        when(modelAssetMapper.softDeleteWorkflowDecryptAsset(anyLong(), eq(SERVER_USER_ID))).thenAnswer(invocation -> {
            ModelAsset asset = assets.get(invocation.getArgument(0));
            if (asset == null || !AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT.equals(asset.getSourceType())) {
                return 0;
            }
            asset.setIsDeleted(1);
            return 1;
        });

        cleanupService = new FederatedArtifactCleanupService(
                workflowMapper,
                uploadMapper,
                modelAssetMapper,
                standaloneValidationMapper,
                properties,
                deletionService,
                transactionManager
        );
    }

    @Test
    void shouldCleanupTwoCompletedUploadsAndKeepGlobalModelAndHistory() throws Exception {
        prepareCompletedWorkflow(2);

        var summary = cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertEquals(2, summary.encryptedCleaned());
        assertEquals(2, summary.decryptedCleaned());
        assertTrue(Files.isRegularFile(globalModelPath));
        assertEquals(0, assets.get(GLOBAL_ASSET_ID).getIsDeleted());
        for (WorkflowModelUpload upload : uploads.values()) {
            assertFalse(Files.exists(encryptedPath(upload.getId())));
            assertFalse(Files.exists(decryptedPath(upload.getId())));
            assertEquals(null, upload.getEncryptedFilePath());
            assertEquals(null, upload.getDecryptedFilePath());
            assertEquals("COMPLETED", upload.getUploadStatus());
            assertEquals("COMPLETED", upload.getAggregationStatus());
            assertNotNull(upload.getServerModelAssetId());
            assertEquals(1, assets.get(upload.getServerModelAssetId()).getIsDeleted());
        }
    }

    @Test
    void shouldCleanupSingleModelAggregation() throws Exception {
        prepareCompletedWorkflow(1);

        var summary = cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertEquals(1, summary.encryptedCleaned());
        assertEquals(1, summary.decryptedCleaned());
        assertTrue(Files.exists(globalModelPath));
    }

    @Test
    void shouldKeepArtifactsWhenFederatedAggregationFailed() throws Exception {
        prepareCompletedWorkflow(1);
        workflow.setFederatedStatus("FAILED");

        var summary = cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(summary.workflowSkipped());
        assertTrue(Files.exists(encryptedPath(1L)));
        assertTrue(Files.exists(decryptedPath(1L)));
        assertEquals(0, assets.get(1001L).getIsDeleted());
    }

    @Test
    void shouldKeepArtifactsWhenGlobalAssetRegistrationDidNotComplete() throws Exception {
        prepareCompletedWorkflow(1);
        workflow.setFederatedModelAssetId(null);

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(Files.exists(encryptedPath(1L)));
        assertTrue(Files.exists(decryptedPath(1L)));
    }

    @Test
    void shouldKeepFailedDeletionPathAndCompletedFederatedStatus() throws Exception {
        prepareCompletedWorkflow(1);
        Path decrypted = decryptedPath(1L);
        doReturn(ControlledArtifactDeletionService.DeleteResult.failed(decrypted, "file locked"))
                .when(deletionService)
                .deleteRegularFile(eq(decrypted.toString()), eq(properties.modelRootDirPath()));

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertEquals("COMPLETED", workflow.getFederatedStatus());
        assertTrue(Files.exists(decrypted));
        assertEquals(decrypted.toString(), uploads.get(1L).getDecryptedFilePath());
        assertEquals(0, assets.get(1001L).getIsDeleted());
    }

    @Test
    void shouldClearEncryptedPathWhenControlledFileIsAlreadyMissing() throws Exception {
        prepareCompletedWorkflow(1);
        Files.delete(encryptedPath(1L));

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertEquals(null, uploads.get(1L).getEncryptedFilePath());
        assertEquals(1, assets.get(1001L).getIsDeleted());
    }

    @Test
    void shouldSkipAlreadySoftDeletedWorkflowDecryptAssetIdempotently() throws Exception {
        prepareCompletedWorkflow(1);
        assets.get(1001L).setIsDeleted(1);

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(Files.exists(decryptedPath(1L)));
        assertNotNull(uploads.get(1L).getDecryptedFilePath());
    }

    @Test
    void shouldNeverDeleteFederatedOutputAsParticipantArtifact() throws Exception {
        assertProtectedParticipantSource(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
    }

    @Test
    void shouldNeverDeleteBrowserUploadAsParticipantArtifact() throws Exception {
        assertProtectedParticipantSource(AssetSourceCatalog.SOURCE_BROWSER_UPLOAD);
    }

    @Test
    void shouldNeverDeleteServerImportAsParticipantArtifact() throws Exception {
        assertProtectedParticipantSource(AssetSourceCatalog.SOURCE_SERVER_IMPORT);
    }

    @Test
    void shouldRejectDecryptedPathOutsideControlledModelRoot() throws Exception {
        prepareCompletedWorkflow(1);
        Path outside = tempDir.resolve("outside-participant.pt");
        Files.writeString(outside, "outside");
        uploads.get(1L).setDecryptedFilePath(outside.toString());
        assets.get(1001L).setFilePath(outside.toString());

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(Files.exists(outside));
        assertEquals(0, assets.get(1001L).getIsDeleted());
        assertEquals(outside.toString(), uploads.get(1L).getDecryptedFilePath());
    }

    @Test
    void shouldPostponeWorkflowDecryptCleanupDuringActiveStandaloneValidation() throws Exception {
        prepareCompletedWorkflow(1);
        when(standaloneValidationMapper.selectCount(any())).thenReturn(1L);

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(Files.exists(decryptedPath(1L)));
        assertEquals(0, assets.get(1001L).getIsDeleted());
        assertNotNull(uploads.get(1L).getDecryptedFilePath());
    }

    @Test
    void shouldUseSameIdempotentCleanupDuringStartupCompensation() throws Exception {
        prepareCompletedWorkflow(1);
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow));

        cleanupService.cleanupEligibleCompletedWorkflowsOnStartup();

        assertFalse(Files.exists(encryptedPath(1L)));
        assertFalse(Files.exists(decryptedPath(1L)));
        assertTrue(Files.exists(globalModelPath));
    }

    private void assertProtectedParticipantSource(String sourceType) throws Exception {
        prepareCompletedWorkflow(1);
        ModelAsset participant = assets.get(1001L);
        participant.setSourceType(sourceType);

        cleanupService.cleanupCompletedWorkflowArtifacts(WORKFLOW_ID);

        assertTrue(Files.exists(decryptedPath(1L)));
        assertEquals(0, participant.getIsDeleted());
        assertNotNull(uploads.get(1L).getDecryptedFilePath());
        assertTrue(Files.exists(globalModelPath));
    }

    private void prepareCompletedWorkflow(int count) throws Exception {
        workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setWorkflowCode("WF001");
        workflow.setServerUserId(SERVER_USER_ID);
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(GLOBAL_ASSET_ID);
        workflow.setIsDeleted(0);

        globalModelPath = properties.federatedModelRootDirPath().resolve("WF001/global_model.pt");
        Files.createDirectories(globalModelPath.getParent());
        Files.writeString(globalModelPath, "global-model");
        ModelAsset globalAsset = baseAsset(GLOBAL_ASSET_ID, globalModelPath, AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT);
        assets.put(globalAsset.getId(), globalAsset);

        for (long id = 1; id <= count; id++) {
            Path encrypted = encryptedPath(id);
            Path decrypted = decryptedPath(id);
            Files.createDirectories(encrypted.getParent());
            Files.createDirectories(decrypted.getParent());
            Files.writeString(encrypted, "encrypted-" + id);
            Files.writeString(decrypted, "decrypted-" + id);

            WorkflowModelUpload upload = new WorkflowModelUpload();
            upload.setId(id);
            upload.setWorkflowId(WORKFLOW_ID);
            upload.setEncryptedFilePath(encrypted.toString());
            upload.setDecryptedFilePath(decrypted.toString());
            upload.setServerModelAssetId(1000L + id);
            upload.setUploadStatus("COMPLETED");
            upload.setAggregationStatus("COMPLETED");
            upload.setIsDeleted(0);
            uploads.put(id, upload);

            ModelAsset participant = baseAsset(
                    upload.getServerModelAssetId(),
                    decrypted,
                    AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT
            );
            assets.put(participant.getId(), participant);
        }
    }

    private ModelAsset baseAsset(Long id, Path path, String sourceType) {
        ModelAsset asset = new ModelAsset();
        asset.setId(id);
        asset.setOwnerUserId(SERVER_USER_ID);
        asset.setOwnerRoleCode("SERVER");
        asset.setSourceType(sourceType);
        asset.setRecordMode(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET);
        asset.setFilePath(path.toString());
        asset.setIsDeleted(0);
        return asset;
    }

    private Path encryptedPath(Long uploadId) {
        return properties.modelUploadDirPath()
                .resolve(String.valueOf(WORKFLOW_ID))
                .resolve("encrypted")
                .resolve(uploadId + "_model.pt.enc");
    }

    private Path decryptedPath(Long uploadId) {
        return properties.modelRootDirPath()
                .resolve("WF001_" + uploadId)
                .resolve("model.pt");
    }
}
