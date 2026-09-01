package com.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.python.PythonCallbackRequest;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.dto.workflow.WorkflowDetailVO;
import com.workflow.dto.workflow.WorkflowListItemVO;

public interface WorkflowService {

    Long createWorkflow(CreateWorkflowRequest request);

    Page<WorkflowListItemVO> pageWorkflows(long pageNum, long pageSize, String status);

    WorkflowDetailVO getWorkflowDetail(Long id);

    void withdrawWorkflow(Long id);

    void advanceWorkflow(Long id);

    void bindServerDataset(Long workflowId, Long serverDatasetAssetId);

    void startPythonJob(Long workflowId);

    void handlePythonCallback(PythonCallbackRequest request);

    void saveWorkflowResult(Long workflowId);

    void deleteSavedWorkflowResult(Long workflowId);
}
