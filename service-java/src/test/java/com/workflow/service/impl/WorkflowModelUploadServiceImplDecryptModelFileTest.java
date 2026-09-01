package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.entity.WorkflowStep;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.WorkflowStepService;
import com.workflow.utils.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowModelUploadServiceImplDecryptModelFileTest {

    private Path tempDir;

    @Mock
    private WorkflowModelUploadMapper uploadMapper;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private WorkflowStepService workflowStepService;

    @Mock
    private ModelAssetService modelAssetService;

    @Mock
    private WorkflowModelUploadStateService workflowModelUploadStateService;

    @Mock
    private WorkflowModelUploadSummaryService workflowModelUploadSummaryService;

    @Mock
    private WorkflowAutoManageStatusService workflowAutoManageStatusService;

    @Mock
    private WorkflowFederatedAggregationService workflowFederatedAggregationService;

    private WorkflowStorageProperties workflowStorageProperties;
    private WorkflowModelUploadServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("workflow-upload-test-");
        workflowStorageProperties = new WorkflowStorageProperties();
        workflowStorageProperties.setModelUploadDir(tempDir.resolve("uploads").toString());
        workflowStorageProperties.setModelRootPath(tempDir.resolve("models").toString());

        service = new WorkflowModelUploadServiceImpl(
                uploadMapper,
                workflowMapper,
                sysUserMapper,
                modelAssetMapper,
                workflowStepService,
                modelAssetService,
                workflowStorageProperties,
                workflowModelUploadStateService,
                workflowModelUploadSummaryService,
                workflowAutoManageStatusService,
                workflowFederatedAggregationService
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    void shouldDecryptModelFileIntoConfiguredStorageAndRegisterServerAsset() throws Exception {
        Workflow workflow = buildWorkflow();
        Workflow refreshedWorkflow = buildWorkflow();
        refreshedWorkflow.setCollectedModelCount(1);

        byte[] decryptedBytes = "decrypted-model".getBytes(StandardCharsets.UTF_8);
        WorkflowModelUpload upload = buildEncryptedUpload(decryptedBytes);
        Path expectedModelPath = tempDir.resolve("models")
                .resolve("WF202604071419493281_15")
                .resolve("yolov8n.pt");

        when(uploadMapper.selectTrackedById(15L)).thenReturn(upload);
        AtomicInteger workflowSelectCount = new AtomicInteger();
        when(workflowMapper.selectById(9L)).thenAnswer(invocation ->
                workflowSelectCount.getAndIncrement() == 0 ? workflow : refreshedWorkflow
        );
        when(modelAssetService.registerDecryptedServerModel(eq(workflow), eq(upload), eq(expectedModelPath)))
                .thenReturn(88L);
        when(workflowStepService.appendWorkflowStep(
                eq(9L),
                eq("MODEL_DECRYPT"),
                anyString(),
                anyString(),
                anyString(),
                eq(22L),
                eq("SERVER"),
                anyString()
        )).thenReturn(new WorkflowStep());

        service.decryptModelFile(15L, 22L);

        assertTrue(Files.exists(expectedModelPath));
        assertArrayEquals(decryptedBytes, Files.readAllBytes(expectedModelPath));

        verify(workflowModelUploadStateService).markDecrypting(15L);
        verify(workflowModelUploadStateService).markDecryptSucceeded(
                9L,
                15L,
                expectedModelPath.toString(),
                88L
        );
        verify(modelAssetService).registerDecryptedServerModel(workflow, upload, expectedModelPath);
        verify(workflowModelUploadStateService, never()).markDecryptFailed(anyLong(), anyString(), anyString());
        verify(workflowAutoManageStatusService).refreshWorkflowAutoManageStatus(9L, "decrypt-success");
    }

    @Test
    void shouldKeepFileOnDiskAndMarkUploadFailedWhenServerAssetRegistrationFails() throws Exception {
        Workflow workflow = buildWorkflow();
        byte[] decryptedBytes = "decrypted-model".getBytes(StandardCharsets.UTF_8);
        WorkflowModelUpload upload = buildEncryptedUpload(decryptedBytes);
        Path expectedModelPath = tempDir.resolve("models")
                .resolve("WF202604071419493281_15")
                .resolve("yolov8n.pt");

        when(uploadMapper.selectTrackedById(15L)).thenReturn(upload);
        when(workflowMapper.selectById(9L)).thenReturn(workflow);
        when(modelAssetService.registerDecryptedServerModel(eq(workflow), eq(upload), eq(expectedModelPath)))
                .thenThrow(new BusinessException("MODEL_ASSET_REGISTER_FAILED", "register failed"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.decryptModelFile(15L, 22L)
        );

        assertEquals("MODEL_ASSET_REGISTER_FAILED", error.getCode());
        assertTrue(Files.exists(expectedModelPath));
        assertArrayEquals(decryptedBytes, Files.readAllBytes(expectedModelPath));

        verify(workflowModelUploadStateService).markDecryptFailed(
                15L,
                expectedModelPath.toString(),
                "register failed"
        );
        verify(workflowModelUploadStateService).markDecrypting(15L);
        verify(workflowModelUploadStateService, never()).markDecryptSucceeded(anyLong(), anyLong(), anyString(), anyLong());
        verify(workflowAutoManageStatusService).refreshWorkflowAutoManageStatus(9L, "decrypt-failed");
    }

    private Workflow buildWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF202604071419493281");
        workflow.setWorkflowName("workflow-0407");
        workflow.setServerUserId(22L);
        workflow.setYoloVersion("YOLOv8");
        workflow.setClientModelCount(1);
        workflow.setCollectedModelCount(0);
        workflow.setStatus("ACCEPTED");
        return workflow;
    }

    private WorkflowModelUpload buildEncryptedUpload(byte[] decryptedBytes) throws Exception {
        String keyBase64 = AesEncryptionUtil.generateKeyBase64();
        String ivBase64 = AesEncryptionUtil.generateIvBase64();
        byte[] encryptedBytes = encrypt(decryptedBytes, keyBase64, ivBase64);

        Path encryptedFilePath = tempDir.resolve("incoming").resolve("model-upload.enc");
        Files.createDirectories(encryptedFilePath.getParent());
        Files.write(encryptedFilePath, encryptedBytes);

        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setId(15L);
        upload.setWorkflowId(9L);
        upload.setUploaderId(11L);
        upload.setOriginalFilename("yolov8n.pt");
        upload.setStoredFilename("15_yolov8n.pt.enc");
        upload.setEncryptedFilePath(encryptedFilePath.toString());
        upload.setFileSha256(AesEncryptionUtil.sha256Hex(decryptedBytes));
        upload.setAesKeyBase64(keyBase64);
        upload.setAesIvBase64(ivBase64);
        upload.setUploadStatus("ENCRYPTED_STORED");
        upload.setIsDeleted(0);
        return upload;
    }

    private byte[] encrypt(byte[] plainBytes, String keyBase64, String ivBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        byte[] ivBytes = Base64.getDecoder().decode(ivBase64);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(ivBytes)
        );
        return cipher.doFinal(plainBytes);
    }
}
