package com.workflow.service.impl;

import com.workflow.dto.dashboard.DashboardOverviewVO;
import com.workflow.dto.validation.ValidationResultViewVO;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.StandaloneValidationMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplValidationReadinessTest {

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowStepMapper workflowStepMapper;
    @Mock
    private StandaloneValidationMapper standaloneValidationMapper;
    @Mock
    private ModelAssetMapper modelAssetMapper;
    @Mock
    private DatasetAssetMapper datasetAssetMapper;
    @Mock
    private WorkflowModelUploadSummaryService workflowModelUploadSummaryService;
    @Mock
    private WorkflowFederatedAggregationService workflowFederatedAggregationService;
    @Mock
    private ValidationResultService validationResultService;
    @Mock
    private LoginUserContext loginUserContext;

    private DashboardServiceImpl dashboardService;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardServiceImpl(
                workflowMapper,
                workflowStepMapper,
                standaloneValidationMapper,
                modelAssetMapper,
                datasetAssetMapper,
                workflowModelUploadSummaryService,
                workflowFederatedAggregationService,
                validationResultService,
                loginUserContext
        );

        SysUser server = new SysUser();
        server.setId(2L);
        server.setRoleCode("SERVER");
        when(loginUserContext.getCurrentUser()).thenReturn(server);

        workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF-DASHBOARD-1");
        workflow.setWorkflowName("联邦验证工作流");
        workflow.setServerUserId(2L);
        workflow.setStatus("ACCEPTED");
        workflow.setServerDatasetAssetId(20L);
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(99L);
        workflow.setExpectedModelCount(2);

        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary =
                new WorkflowModelUploadSummaryService.WorkflowUploadSummary();
        summary.setWorkflowId(workflow.getId());
        summary.setReceivedModelCount(2);
        summary.setCompletedModelCount(2);

        DatasetAsset dataset = new DatasetAsset();
        dataset.setId(20L);
        dataset.setIsDeleted(0);
        dataset.setFilePathValidated(1);
        dataset.setImageCount(10);
        dataset.setFilePath("/opt/workflow-platform/storage/datasets/demo");

        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow));
        when(standaloneValidationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(modelAssetMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(datasetAssetMapper.selectById(20L)).thenReturn(dataset);
        when(workflowModelUploadSummaryService.summarizeWorkflows(any())).thenReturn(Map.of(workflow.getId(), summary));
    }

    @Test
    void shouldMarkValidationReadyWhenFederatedModelIsAvailable() {
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.available(
                        java.nio.file.Path.of("/opt/workflow-platform/storage/federated-models/WF-DASHBOARD-1/global_model.pt")
                ));

        DashboardOverviewVO overview = dashboardService.getOverview();

        assertTrue(overview.getLatestWorkflow().getValidationReady());
        assertTrue(overview.getLatestWorkflow().getFederatedModelAvailable());
        assertEquals(1L, overview.getMetrics().getPendingValidationCount());
    }

    @Test
    void shouldExposeExactReasonWhenFederatedModelIsUnavailable() {
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.unavailable(
                        "FEDERATED_MODEL_DELETED",
                        "联邦全局模型已删除，无法再次启动工作流验证。"
                ));

        DashboardOverviewVO overview = dashboardService.getOverview();

        assertFalse(overview.getLatestWorkflow().getValidationReady());
        assertFalse(overview.getLatestWorkflow().getFederatedModelAvailable());
        assertEquals(
                "联邦全局模型已删除，无法再次启动工作流验证。",
                overview.getLatestWorkflow().getValidationReadyReason()
        );
        assertEquals(0L, overview.getMetrics().getPendingValidationCount());
    }

    @Test
    void shouldKeepHistoricalDashboardResultWhenFederatedModelIsUnavailable() {
        workflow.setStatus("COMPLETED");
        workflow.setPythonJobId("python-history-job");
        workflow.setResultRetentionStatus("TEMPORARY");
        workflow.setUpdatedAt(LocalDateTime.now());
        when(workflowFederatedAggregationService.resolveFederatedModelAvailability(workflow))
                .thenReturn(WorkflowFederatedAggregationService.FederatedModelAvailability.unavailable(
                        "FEDERATED_MODEL_DELETED",
                        "联邦全局模型已删除，无法再次启动工作流验证。"
                ));
        ValidationResultViewVO resultView = new ValidationResultViewVO();
        resultView.setStatus("COMPLETED");
        resultView.setAccuracy(0.91);
        resultView.setModelName("历史联邦全局模型");
        resultView.setDatasetName("历史验证数据集");
        when(validationResultService.getWorkflowResultView(workflow.getId())).thenReturn(resultView);

        DashboardOverviewVO overview = dashboardService.getOverview();

        assertFalse(overview.getLatestWorkflow().getFederatedModelAvailable());
        assertEquals(0.91, overview.getRecentValidation().getAccuracy());
        assertEquals("WORKFLOW", overview.getRecentValidation().getSourceType());
    }
}
