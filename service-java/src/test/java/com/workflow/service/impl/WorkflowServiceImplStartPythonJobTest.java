package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonCreateJobRequest;
import com.workflow.dto.python.PythonCreateJobResponse;
import com.workflow.dto.python.PythonCreateJobResponseData;
import com.workflow.dto.workflow.WorkflowDetailVO;
import com.workflow.entity.DatasetAsset;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplStartPythonJobTest {

    @TempDir
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

    private WorkflowServiceImpl workflowService;

    @BeforeEach
    void setUp() {
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
                resultArtifactCleanupService
        );

        SysUser currentServerUser = new SysUser();
        currentServerUser.setId(2L);
        currentServerUser.setRoleCode("SERVER");
        when(loginUserContext.getCurrentUser()).thenReturn(currentServerUser);

        lenient().when(workflowMapper.updateById(any(Workflow.class))).thenReturn(1);
        lenient().when(workflowStepMapper.selectCount(any())).thenReturn(0L);
        lenient().when(workflowStepMapper.insert(any(WorkflowStep.class))).thenReturn(1);
    }

    @Test
    void shouldUseFederatedOutputPathWhenStartingPythonJob() throws Exception {
        Workflow workflow = buildWorkflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        Path globalModelPath = tempDir.resolve("federated-models")
                .resolve(workflow.getWorkflowCode())
                .resolve("global_model.pt");
        Files.createDirectories(globalModelPath.getParent());
        Files.writeString(globalModelPath, "global-model");
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.available(globalModelPath));

        Path datasetDir = tempDir.resolve("dataset");
        Files.createDirectories(datasetDir);
        DatasetAsset datasetAsset = new DatasetAsset();
        datasetAsset.setId(20L);
        datasetAsset.setFilePath(datasetDir.toString());
        datasetAsset.setIsDeleted(0);
        when(datasetAssetMapper.selectOne(any())).thenReturn(datasetAsset);

        PythonCreateJobResponse response = new PythonCreateJobResponse();
        response.setCode("OK");
        PythonCreateJobResponseData responseData = new PythonCreateJobResponseData();
        responseData.setJobId("py-job-1");
        responseData.setStatus("ACCEPTED");
        response.setData(responseData);
        when(pythonJobClient.createJob(any(PythonCreateJobRequest.class))).thenReturn(response);

        workflowService.startPythonJob(9L);

        ArgumentCaptor<PythonCreateJobRequest> requestCaptor = ArgumentCaptor.forClass(PythonCreateJobRequest.class);
        verify(pythonJobClient).createJob(requestCaptor.capture());
        assertEquals("WORKFLOW_VALIDATION", requestCaptor.getValue().getJobType());
        assertEquals(9L, requestCaptor.getValue().getWorkflowId());
        assertEquals(null, requestCaptor.getValue().getStandaloneValidationId());
        assertEquals(globalModelPath.toString(), requestCaptor.getValue().getModelPath());
        assertEquals(datasetDir.toString(), requestCaptor.getValue().getDatasetPath());
        verify(workflowModelUploadMapper, never()).selectList(any());
    }

    @Test
    void shouldNeverFallbackToWorkflowDecryptModelWhenGlobalModelIsUnavailable() {
        Workflow workflow = buildWorkflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        WorkflowModelUpload participantUpload = new WorkflowModelUpload();
        participantUpload.setId(15L);
        participantUpload.setWorkflowId(workflow.getId());
        participantUpload.setUploadStatus("COMPLETED");
        participantUpload.setServerModelAssetId(88L);
        lenient().when(workflowModelUploadMapper.selectList(any())).thenReturn(List.of(participantUpload));
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.unavailable(
                        "FEDERATED_MODEL_DELETED",
                        "联邦全局模型已删除，无法再次启动工作流验证。"
                ));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> workflowService.startPythonJob(9L)
        );

        assertEquals("FEDERATED_MODEL_DELETED", error.getCode());
        assertEquals("联邦全局模型已删除，无法再次启动工作流验证。", error.getMessage());
        verify(workflowModelUploadMapper, never()).selectList(any());
        verify(pythonJobClient, never()).createJob(any(PythonCreateJobRequest.class));
        verify(resultArtifactCleanupService, never())
                .cleanupReplaceableWorkflowResultsBeforeStart(any(), any(), any());
    }

    @Test
    void shouldKeepHistoricalMetricsReadableWhenFederatedModelIsUnavailable() {
        Workflow workflow = buildWorkflow();
        workflow.setStatus("COMPLETED");
        workflow.setMetricsJson("{\"accuracy\":0.88,\"mAP50\":0.81}");
        workflow.setResultFilePath("results/history.json");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(workflowStepMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(workflowModelUploadSummaryService.summarizeWorkflow(workflow.getId()))
                .thenReturn(new WorkflowModelUploadSummaryService.WorkflowUploadSummary());
        when(modelAssetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(datasetAssetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(sysUserMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.unavailable(
                        "FEDERATED_MODEL_DELETED",
                        "联邦全局模型已删除，无法再次启动工作流验证。"
                ));

        WorkflowDetailVO detail = workflowService.getWorkflowDetail(workflow.getId());

        assertEquals(workflow.getMetricsJson(), detail.getMetricsJson());
        assertEquals(workflow.getResultFilePath(), detail.getResultFilePath());
        assertFalse(detail.getFederatedModelAvailable());
        assertEquals("联邦全局模型已删除，无法再次启动工作流验证。", detail.getFederatedModelUnavailableReason());
    }

    private Workflow buildWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF202604101234001234");
        workflow.setServerUserId(2L);
        workflow.setServerDatasetAssetId(20L);
        workflow.setYoloVersion("YOLOv10");
        workflow.setStatus("ACCEPTED");
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(99L);
        workflow.setIsDeleted(0);
        return workflow;
    }
}
