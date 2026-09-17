package com.workflow.service.impl;

import com.workflow.config.WeightsProtocolProperties;
import com.workflow.entity.Workflow;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.support.WorkflowCurrentStepSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowAutoManageStatusServiceWeightsV1Test {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowModelUploadMapper uploadMapper;
    @Mock WorkflowModelUploadSummaryService summaryService;

    @Test
    void inspectedWeightsWithoutActualTriggerRemainWaiting() {
        WeightsProtocolProperties properties = new WeightsProtocolProperties();
        properties.setEnabled(true);
        WorkflowAutoManageStatusService service = new WorkflowAutoManageStatusService(
                workflowMapper, uploadMapper, summaryService, properties);
        Workflow workflow = new Workflow();
        workflow.setId(10L);
        workflow.setStatus("ACCEPTED");
        workflow.setModelDefinitionId(1L);
        workflow.setExpectedModelCount(1);
        workflow.setFederatedStatus("PENDING");
        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary =
                new WorkflowModelUploadSummaryService.WorkflowUploadSummary();
        summary.setCompletedModelCount(1);
        when(workflowMapper.selectById(10L)).thenReturn(workflow);
        when(summaryService.summarizeWorkflow(10L)).thenReturn(summary);
        when(uploadMapper.selectList(any())).thenReturn(List.of());

        var result = service.refreshWorkflowAutoManageStatus(10L, "test");

        assertEquals(WorkflowCurrentStepSupport.WEIGHTS_INSPECTED_WAITING_FEDERATED, result.getCurrentStep());
        assertEquals(WorkflowCurrentStepSupport.WEIGHTS_INSPECTED_WAITING_FEDERATED, workflow.getCurrentStep());
    }

    @Test
    void onlyRunningStatusDisplaysAggregating() {
        WeightsProtocolProperties properties = new WeightsProtocolProperties();
        properties.setEnabled(true);
        WorkflowAutoManageStatusService service = new WorkflowAutoManageStatusService(
                workflowMapper, uploadMapper, summaryService, properties);
        Workflow workflow = new Workflow();
        workflow.setId(10L);
        workflow.setStatus("ACCEPTED");
        workflow.setModelDefinitionId(1L);
        workflow.setExpectedModelCount(1);
        workflow.setFederatedStatus("RUNNING");
        WorkflowModelUploadSummaryService.WorkflowUploadSummary summary =
                new WorkflowModelUploadSummaryService.WorkflowUploadSummary();
        summary.setCompletedModelCount(1);
        when(workflowMapper.selectById(10L)).thenReturn(workflow);
        when(summaryService.summarizeWorkflow(10L)).thenReturn(summary);
        when(uploadMapper.selectList(any())).thenReturn(List.of());

        var result = service.refreshWorkflowAutoManageStatus(10L, "test");

        assertEquals(WorkflowCurrentStepSupport.FEDERATED_AGGREGATING, result.getCurrentStep());
    }
}
