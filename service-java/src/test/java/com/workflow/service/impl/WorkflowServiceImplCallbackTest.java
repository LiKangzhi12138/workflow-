package com.workflow.service.impl;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.python.PythonCallbackRequest;
import com.workflow.dto.python.PythonCallbackResultFile;
import com.workflow.dto.python.PythonJobTypes;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowStep;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.WorkflowModelUploadService;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplCallbackTest {

    private static final Long WORKFLOW_ID = 7L;
    private static final String JOB_ID = "job-123";
    private static final String SECRET = "dev-secret";
    private static final String KNOWN_COMPLETED_SIGNATURE = "735c7d3f4758acca1a626853b4f0815bbb1a9b5794dafca49602e7e7a6feb765";

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
    private WorkflowModelUploadService workflowModelUploadService;

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
    void setUp() {
        PythonIntegrationProperties properties = new PythonIntegrationProperties();
        properties.setCallbackSecret(SECRET);
        properties.setAlgorithmType("MOCK_FL");
        properties.setJavaBaseUrl("http://127.0.0.1:8080");
        properties.setPythonBaseUrl("http://127.0.0.1:8000");
        WorkflowStorageProperties workflowStorageProperties = new WorkflowStorageProperties();

        workflowService = new WorkflowServiceImpl(
                workflowMapper,
                workflowStepMapper,
                sysUserMapper,
                modelAssetMapper,
                datasetAssetMapper,
                loginUserContext,
                pythonJobClient,
                properties,
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

        lenient().when(workflowStepMapper.selectCount(any())).thenReturn(0L);
        lenient().when(workflowMapper.updateById(any(Workflow.class))).thenReturn(1);
        lenient().when(workflowStepMapper.insert(any(WorkflowStep.class))).thenReturn(1);
    }

    @Test
    void shouldMatchKnownPythonSignature() {
        PythonCallbackRequest request = buildCompletedRequest();
        assertEquals(KNOWN_COMPLETED_SIGNATURE, sign(request));
    }

    @Test
    void shouldAdvanceToCompletedAndInsertStep() {
        Workflow workflow = buildWorkflow("VALIDATING");
        lenient().when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildCompletedRequest();

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        assertEquals("COMPLETED", workflow.getStatus());
        assertEquals(100, workflow.getProgress());
        assertEquals("D:/results/job-123_result.json", workflow.getResultFilePath());
        assertNotNull(workflow.getMetricsJson());

        ArgumentCaptor<WorkflowStep> stepCaptor = ArgumentCaptor.forClass(WorkflowStep.class);
        verify(workflowStepMapper).insert(stepCaptor.capture());
        assertEquals("PY_CALLBACK_COMPLETED", stepCaptor.getValue().getStepCode());
        assertEquals("COMPLETED", stepCaptor.getValue().getToStatus());
    }

    @Test
    void shouldAdvanceToFailedAndInsertStep() {
        Workflow workflow = buildWorkflow("VALIDATING");
        lenient().when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildFailedRequest();

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        assertEquals("FAILED", workflow.getStatus());
        assertEquals(0, workflow.getProgress());
        assertEquals("mock failure", workflow.getErrorMessage());

        ArgumentCaptor<WorkflowStep> stepCaptor = ArgumentCaptor.forClass(WorkflowStep.class);
        verify(workflowStepMapper).insert(stepCaptor.capture());
        assertEquals("PY_CALLBACK_FAILED", stepCaptor.getValue().getStepCode());
    }

    @Test
    void shouldHandleDuplicateCallbackIdempotentlyWithoutInsertingStep() {
        Workflow workflow = buildWorkflow("PREPARING");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildPreparingRequest(30);

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        assertEquals("PREPARING", workflow.getStatus());
        assertEquals(30, workflow.getProgress());
        verify(workflowMapper).updateById(workflow);
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void shouldIgnoreOutdatedCallbackAfterCompleted() {
        Workflow workflow = buildWorkflow("COMPLETED");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildValidatingRequest();

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void shouldIgnoreOutdatedCallbackDuringRunningStates() {
        Workflow workflow = buildWorkflow("VALIDATING");
        workflow.setProgress(85);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildPreparingRequest(25);

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        assertEquals("VALIDATING", workflow.getStatus());
        assertEquals(85, workflow.getProgress());
        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void shouldIgnoreOtherTerminalCallbackAfterCompleted() {
        Workflow workflow = buildWorkflow("COMPLETED");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildFailedRequest();

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void shouldRefreshDuplicateCompletedCallbackWithoutInsertingStep() {
        Workflow workflow = buildWorkflow("COMPLETED");
        workflow.setProgress(95);
        workflow.setResultFilePath(null);
        workflow.setMetricsJson(null);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildCompletedRequest();

        assertDoesNotThrow(() -> workflowService.handlePythonCallback(request));

        assertEquals("COMPLETED", workflow.getStatus());
        assertEquals(100, workflow.getProgress());
        assertEquals("D:/results/job-123_result.json", workflow.getResultFilePath());
        assertNotNull(workflow.getMetricsJson());
        verify(workflowMapper).updateById(workflow);
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    @Test
    void shouldRejectInvalidSignature() {
        Workflow workflow = buildWorkflow("VALIDATING");
        lenient().when(workflowMapper.selectOne(any())).thenReturn(workflow);

        PythonCallbackRequest request = buildCompletedRequest();
        request.setSign("bad-sign");

        RuntimeException error = assertThrows(RuntimeException.class, () -> workflowService.handlePythonCallback(request));
        assertEquals("Python 回调签名校验失败", error.getMessage());
        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(workflowStepMapper, never()).insert(any(WorkflowStep.class));
    }

    private Workflow buildWorkflow(String status) {
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setPythonJobId(JOB_ID);
        workflow.setServerUserId(2L);
        workflow.setStatus(status);
        workflow.setProgress(0);
        return workflow;
    }

    private PythonCallbackRequest buildCompletedRequest() {
        PythonCallbackRequest request = new PythonCallbackRequest();
        request.setJobType(PythonJobTypes.WORKFLOW_VALIDATION);
        request.setJobId(JOB_ID);
        request.setWorkflowId(WORKFLOW_ID);
        request.setStatus("COMPLETED");
        request.setProgress(100);
        request.setMessage("completed");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mAP", 0.88);
        metrics.put("precision", 0.91);
        metrics.put("recall", 0.86);
        request.setMetrics(metrics);

        PythonCallbackResultFile resultFile = new PythonCallbackResultFile();
        resultFile.setFileName("job-123_result.json");
        resultFile.setFilePath("D:/results/job-123_result.json");
        request.setResultFile(resultFile);
        request.setErrorMessage(null);
        request.setSign(sign(request));
        return request;
    }

    private PythonCallbackRequest buildFailedRequest() {
        PythonCallbackRequest request = new PythonCallbackRequest();
        request.setJobType(PythonJobTypes.WORKFLOW_VALIDATION);
        request.setJobId(JOB_ID);
        request.setWorkflowId(WORKFLOW_ID);
        request.setStatus("FAILED");
        request.setProgress(0);
        request.setMessage("failed");
        request.setMetrics(null);
        request.setResultFile(null);
        request.setErrorMessage("mock failure");
        request.setSign(sign(request));
        return request;
    }

    private PythonCallbackRequest buildPreparingRequest(int progress) {
        PythonCallbackRequest request = new PythonCallbackRequest();
        request.setJobType(PythonJobTypes.WORKFLOW_VALIDATION);
        request.setJobId(JOB_ID);
        request.setWorkflowId(WORKFLOW_ID);
        request.setStatus("PREPARING");
        request.setProgress(progress);
        request.setMessage("preparing");
        request.setMetrics(null);
        request.setResultFile(null);
        request.setErrorMessage(null);
        request.setSign(sign(request));
        return request;
    }

    private PythonCallbackRequest buildValidatingRequest() {
        PythonCallbackRequest request = new PythonCallbackRequest();
        request.setJobType(PythonJobTypes.WORKFLOW_VALIDATION);
        request.setJobId(JOB_ID);
        request.setWorkflowId(WORKFLOW_ID);
        request.setStatus("VALIDATING");
        request.setProgress(85);
        request.setMessage("validating");
        request.setMetrics(null);
        request.setResultFile(null);
        request.setErrorMessage(null);
        request.setSign(sign(request));
        return request;
    }

    private String sign(PythonCallbackRequest request) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("errorMessage", request.getErrorMessage());
            payload.put("jobId", request.getJobId());
            payload.put("jobType", request.getJobType());
            payload.put("message", request.getMessage());
            payload.put("metrics", request.getMetrics());
            payload.put("progress", request.getProgress());

            Map<String, Object> resultFile = null;
            if (request.getResultFile() != null) {
                resultFile = new LinkedHashMap<>();
                resultFile.put("fileName", request.getResultFile().getFileName());
                resultFile.put("filePath", request.getResultFile().getFilePath());
            }
            payload.put("resultFile", resultFile);
            payload.put("standaloneValidationId", request.getStandaloneValidationId());
            payload.put("status", request.getStatus());
            payload.put("workflowId", request.getWorkflowId());

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            String json = mapper.writeValueAsString(payload);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((json + SECRET).getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
