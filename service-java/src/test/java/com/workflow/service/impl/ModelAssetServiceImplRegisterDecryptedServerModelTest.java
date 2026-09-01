package com.workflow.service.impl;

import com.workflow.entity.ModelAsset;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModelAssetServiceImplRegisterDecryptedServerModelTest {

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private LoginUserContext loginUserContext;

    @Mock
    private AssetStorageService assetStorageService;

    @Mock
    private FederatedGlobalModelDeletionService federatedGlobalModelDeletionService;

    @Test
    void shouldRegisterServerSideModelAssetFromDecryptedFile() throws Exception {
        ModelAssetServiceImpl service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                federatedGlobalModelDeletionService
        );

        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF202604071419493281");
        workflow.setWorkflowName("工作流0407");
        workflow.setServerUserId(22L);
        workflow.setYoloVersion("YOLOv8");

        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setId(15L);
        upload.setOriginalFilename("yolov8n.pt");

        Path tempFile = Files.createTempFile("workflow-model-", ".pt");
        try {
            Files.writeString(tempFile, "decrypted-model");

            doAnswer(invocation -> {
                ModelAsset asset = invocation.getArgument(0);
                asset.setId(101L);
                return 1;
            }).when(modelAssetMapper).insert(any(ModelAsset.class));

            Long assetId = service.registerDecryptedServerModel(workflow, upload, tempFile);

            assertEquals(101L, assetId);

            ArgumentCaptor<ModelAsset> assetCaptor = ArgumentCaptor.forClass(ModelAsset.class);
            verify(modelAssetMapper).insert(assetCaptor.capture());

            ModelAsset savedAsset = assetCaptor.getValue();
            assertNotNull(savedAsset.getAssetCode());
            assertEquals("工作流模型_yolov8n.pt", savedAsset.getAssetName());
            assertEquals(22L, savedAsset.getOwnerUserId());
            assertEquals("SERVER", savedAsset.getOwnerRoleCode());
            assertEquals("YOLO", savedAsset.getModelType());
            assertEquals("YOLO8", savedAsset.getModelVersion());
            assertEquals("DETECTION", savedAsset.getTaskType());
            assertEquals(tempFile.getFileName().toString(), savedAsset.getFileName());
            assertEquals(tempFile.toString(), savedAsset.getFilePath());
            assertEquals(Files.size(tempFile), savedAsset.getFileSize());
            assertEquals(1, savedAsset.getFilePathValidated());
            assertEquals("YOLOv8", savedAsset.getYoloVersion());
            assertEquals(0, savedAsset.getIsPublic());
            assertEquals("READY", savedAsset.getStatus());
            assertEquals(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET, savedAsset.getRecordMode());
            assertEquals(AssetSourceCatalog.SOURCE_WORKFLOW_DECRYPT, savedAsset.getSourceType());
            assertEquals("工作流 WF202604071419493281 接收并解密后的正式模型资产，uploadId=15", savedAsset.getDescription());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void shouldRejectMissingDecryptedFile() {
        ModelAssetServiceImpl service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                federatedGlobalModelDeletionService
        );

        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setServerUserId(22L);

        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setId(15L);

        Path missingFile = Path.of("D:\\workspace\\workflow-platform\\storage\\dev\\models\\missing\\model.pt");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.registerDecryptedServerModel(workflow, upload, missingFile)
        );

        assertEquals("MODEL_ASSET_FILE_MISSING", error.getCode());
    }

    @Test
    void shouldRegisterFederatedOutputAsFormalServerAsset() throws Exception {
        ModelAssetServiceImpl service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                federatedGlobalModelDeletionService
        );

        Workflow workflow = new Workflow();
        workflow.setId(12L);
        workflow.setWorkflowCode("WF-FED-001");
        workflow.setWorkflowName("联邦聚合工作流");
        workflow.setServerUserId(22L);
        workflow.setYoloVersion("YOLOv10");

        Path tempFile = Files.createTempFile("global-model-", ".pt");
        try {
            Files.writeString(tempFile, "federated-model");
            doAnswer(invocation -> {
                ModelAsset asset = invocation.getArgument(0);
                asset.setId(202L);
                return 1;
            }).when(modelAssetMapper).insert(any(ModelAsset.class));

            Long assetId = service.registerFederatedServerModel(workflow, tempFile, 3);

            assertEquals(202L, assetId);
            ArgumentCaptor<ModelAsset> assetCaptor = ArgumentCaptor.forClass(ModelAsset.class);
            verify(modelAssetMapper).insert(assetCaptor.capture());
            ModelAsset savedAsset = assetCaptor.getValue();
            assertEquals(22L, savedAsset.getOwnerUserId());
            assertEquals("SERVER", savedAsset.getOwnerRoleCode());
            assertEquals(AssetSourceCatalog.RECORD_MODE_FORMAL_ASSET, savedAsset.getRecordMode());
            assertEquals(AssetSourceCatalog.SOURCE_FEDERATED_OUTPUT, savedAsset.getSourceType());
            assertEquals("READY", savedAsset.getStatus());
            assertEquals(tempFile.toAbsolutePath().normalize().toString(), savedAsset.getFilePath());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
