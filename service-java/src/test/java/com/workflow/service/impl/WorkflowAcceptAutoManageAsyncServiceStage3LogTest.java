package com.workflow.service.impl;

import com.workflow.entity.Workflow;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.WorkflowModelUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class WorkflowAcceptAutoManageAsyncServiceStage3LogTest {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowModelUploadMapper uploadMapper;
    @Mock WorkflowModelUploadService uploadService;
    @Mock WorkflowAutoManageStatusService statusService;
    @Mock WorkflowFederatedAggregationService legacyAggregationService;

    @Test
    void reportsV1AggregationTriggeredOnlyAfterFormalGlobalAssetCompletion() {
        WorkflowAcceptAutoManageAsyncService service = new WorkflowAcceptAutoManageAsyncService(
                workflowMapper, uploadMapper, uploadService, statusService, legacyAggregationService
        );
        Workflow workflow = new Workflow();
        workflow.setFederatedStrategy(WorkflowWeightsFederatedAggregationService.STRATEGY);
        workflow.setFederatedStatus("COMPLETED");
        workflow.setFederatedModelAssetId(77L);

        assertTrue(service.isCompletedWeightsAggregation(workflow));

        workflow.setFederatedModelAssetId(null);
        assertFalse(service.isCompletedWeightsAggregation(workflow));
    }
}
