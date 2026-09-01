package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.enums.WorkflowModelUploadStatusEnum;
import com.workflow.mapper.WorkflowModelUploadMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowModelUploadSummaryService {

    private final WorkflowModelUploadMapper workflowModelUploadMapper;

    public WorkflowUploadSummary summarizeWorkflow(Long workflowId) {
        if (workflowId == null) {
            return new WorkflowUploadSummary();
        }
        return summarizeWorkflows(Collections.singleton(workflowId))
                .getOrDefault(workflowId, new WorkflowUploadSummary());
    }

    public Map<Long, WorkflowUploadSummary> summarizeWorkflows(Collection<Long> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<WorkflowModelUpload> uploads = workflowModelUploadMapper.selectList(
                new LambdaQueryWrapper<WorkflowModelUpload>()
                        .in(WorkflowModelUpload::getWorkflowId, workflowIds)
                        .eq(WorkflowModelUpload::getIsDeleted, 0)
                        .orderByDesc(WorkflowModelUpload::getCreatedAt)
                        .orderByDesc(WorkflowModelUpload::getId)
        );

        Map<Long, WorkflowUploadSummary> summaryMap = new HashMap<>();
        for (WorkflowModelUpload upload : uploads) {
            WorkflowUploadSummary summary = summaryMap.computeIfAbsent(
                    upload.getWorkflowId(),
                    ignored -> {
                        WorkflowUploadSummary value = new WorkflowUploadSummary();
                        value.setWorkflowId(upload.getWorkflowId());
                        return value;
                    }
            );
            accumulate(summary, upload);
        }
        return summaryMap;
    }

    private void accumulate(WorkflowUploadSummary summary, WorkflowModelUpload upload) {
        summary.setTotalUploadCount(summary.getTotalUploadCount() + 1);
        if (WorkflowModelUploadStatusEnum.isActiveSlotStatus(upload.getUploadStatus())) {
            summary.setActiveUploadCount(summary.getActiveUploadCount() + 1);
        }
        if (WorkflowModelUploadStatusEnum.isReceivedFileStatus(upload.getUploadStatus())) {
            summary.setReceivedModelCount(summary.getReceivedModelCount() + 1);
        }
        if (WorkflowModelUploadStatusEnum.isValidationReadyStatus(upload.getUploadStatus())
                && upload.getServerModelAssetId() != null) {
            summary.setCompletedModelCount(summary.getCompletedModelCount() + 1);
        }
        if (summary.getLatestUploadId() == null) {
            summary.setLatestUploadId(upload.getId());
            summary.setLatestUploadStatus(upload.getUploadStatus());
        }
    }

    @Data
    public static class WorkflowUploadSummary {
        private Long workflowId;
        private int totalUploadCount;
        private int activeUploadCount;
        private int receivedModelCount;
        private int completedModelCount;
        private Long latestUploadId;
        private String latestUploadStatus;
    }
}
