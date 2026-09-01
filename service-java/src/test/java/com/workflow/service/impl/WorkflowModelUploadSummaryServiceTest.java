package com.workflow.service.impl;

import com.workflow.entity.WorkflowModelUpload;
import com.workflow.mapper.WorkflowModelUploadMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowModelUploadSummaryServiceTest {

    @Test
    void shouldKeepHistoricalProgressCountsAfterIntermediatePathsAreCleared() {
        WorkflowModelUploadMapper mapper = mock(WorkflowModelUploadMapper.class);
        WorkflowModelUpload upload = new WorkflowModelUpload();
        upload.setId(10L);
        upload.setWorkflowId(1L);
        upload.setUploadStatus("COMPLETED");
        upload.setAggregationStatus("COMPLETED");
        upload.setEncryptedFilePath(null);
        upload.setDecryptedFilePath(null);
        upload.setServerModelAssetId(20L);
        upload.setIsDeleted(0);
        when(mapper.selectList(any())).thenReturn(List.of(upload));

        var summary = new WorkflowModelUploadSummaryService(mapper).summarizeWorkflow(1L);

        assertEquals(1, summary.getReceivedModelCount());
        assertEquals(1, summary.getCompletedModelCount());
        assertEquals(1, summary.getActiveUploadCount());
        assertEquals(1, summary.getTotalUploadCount());
    }
}
