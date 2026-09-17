package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.entity.WorkflowStep;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplAdvanceWorkflowTest {

    private Path tempDir;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private WorkflowStepMapper workflowStepMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private DatasetAssetMapper datasetAssetMapper;

    @Mock
    private LoginUserContext loginUserContext;

    @Mock
    private PythonJobClient pythonJobClient;

    @Mock
    private WorkflowModelUploadMapper workflowModelUploadMapper;

    @Mock
    private WorkflowModelUploadSummaryService workflowModelUploadSummaryService;

    @Mock
    private WorkflowAcceptAutoManageAsyncService workflowAcceptAutoManageAsyncService;

    @Mock
    private WorkflowFederatedAggregationService workflowFederatedAggregationService;

    @Mock
    private ValidationImageCacheService validationImageCacheService;

    @Mock
    private ValidationResultService validationResultService;

    @Mock
    private ResultArtifactCleanupService resultArtifactCleanupService;

    @Mock
    private WorkflowModelDefinitionSelectionService workflowModelDefinitionSelectionService;
    @Mock
    private WorkflowWeightsValidationPreparationService weightsValidationPreparationService;

    private WorkflowServiceImpl workflowService;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("workflow-service-accept-");

        PythonIntegrationProperties pythonProperties = new PythonIntegrationProperties();
        pythonProperties.setCallbackSecret("dev-secret");
        pythonProperties.setJavaBaseUrl("http://127.0.0.1:8080");
        pythonProperties.setPythonBaseUrl("http://127.0.0.1:8000");

        WorkflowStorageProperties workflowStorageProperties = new WorkflowStorageProperties();
        workflowStorageProperties.setModelRootPath(tempDir.resolve("models").toString());

        workflowService = new WorkflowServiceImpl(
                workflowMapper,
                workflowStepMapper,
                sysUserMapper,
                modelAssetMapper,
                datasetAssetMapper,
                loginUserContext,
                pythonJobClient,
                pythonProperties,
                new ObjectMapper(),
                workflowModelUploadMapper,
                workflowStorageProperties,
                workflowModelUploadSummaryService,
                workflowAcceptAutoManageAsyncService,
                workflowFederatedAggregationService,
                validationImageCacheService,
                validationResultService,
                resultArtifactCleanupService,
                workflowModelDefinitionSelectionService,
                weightsValidationPreparationService
        );

        SysUser currentServerUser = new SysUser();
        currentServerUser.setId(2L);
        currentServerUser.setRoleCode("SERVER");
        when(loginUserContext.getCurrentUser()).thenReturn(currentServerUser);

        lenient().when(workflowMapper.updateById(any(Workflow.class))).thenReturn(1);
        lenient().when(workflowStepMapper.selectCount(any())).thenReturn(0L);
        lenient().when(workflowStepMapper.insert(any(WorkflowStep.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void shouldAcceptWorkflowQuicklyAndTriggerBackgroundAutoManage() throws Exception {
        Workflow workflow = buildCreatedWorkflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        Path encryptedFilePath = tempDir.resolve("incoming").resolve("15_yolov8n.pt.enc");
        Files.createDirectories(encryptedFilePath.getParent());
        Files.writeString(encryptedFilePath, "encrypted-model");

        WorkflowModelUpload receivedUpload = new WorkflowModelUpload();
        receivedUpload.setId(15L);
        receivedUpload.setWorkflowId(9L);
        receivedUpload.setUploadStatus("ENCRYPTED_STORED");
        receivedUpload.setEncryptedFilePath(encryptedFilePath.toString());

        when(workflowModelUploadMapper.selectList(any())).thenReturn(
                List.of(receivedUpload),
                List.of(receivedUpload)
        );

        assertDoesNotThrow(() -> workflowService.advanceWorkflow(9L));

        verify(workflowAcceptAutoManageAsyncService).processAcceptedWorkflowAsync(9L, 2L, 1, 1);

        ArgumentCaptor<Workflow> workflowCaptor = ArgumentCaptor.forClass(Workflow.class);
        verify(workflowMapper).updateById(workflowCaptor.capture());
        assertEquals("ACCEPTED", workflowCaptor.getValue().getStatus());
        assertEquals(
                "服务端已接收",
                workflowCaptor.getValue().getCurrentStep()
        );

        ArgumentCaptor<WorkflowStep> stepCaptor = ArgumentCaptor.forClass(WorkflowStep.class);
        verify(workflowStepMapper).insert(stepCaptor.capture());
        assertEquals("ACCEPTED", stepCaptor.getValue().getToStatus());
    }

    @Test
    void shouldRejectAcceptWhenUploadedModelCountIsNotReady() throws Exception {
        Workflow workflow = buildCreatedWorkflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        when(workflowModelUploadMapper.selectList(any())).thenReturn(List.of());

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> workflowService.advanceWorkflow(9L)
        );

        assertEquals("WORKFLOW_ACCEPT_UPLOAD_NOT_READY", error.getCode());
    }

    private Workflow buildCreatedWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF202604111200009999");
        workflow.setServerUserId(2L);
        workflow.setStatus("CREATED");
        workflow.setClientModelCount(1);
        workflow.setIsDeleted(0);
        return workflow;
    }
}
