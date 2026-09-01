package com.workflow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.common.ApiResponse;
import com.workflow.dto.workflow.BindServerDatasetRequest;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.dto.workflow.WorkflowDetailVO;
import com.workflow.dto.workflow.WorkflowListItemVO;
import com.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public ApiResponse<Long> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        return ApiResponse.success(workflowService.createWorkflow(request));
    }

    @GetMapping
    public ApiResponse<Page<WorkflowListItemVO>> pageWorkflows(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.success(workflowService.pageWorkflows(pageNum, pageSize, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkflowDetailVO> getWorkflowDetail(@PathVariable Long id) {
        return ApiResponse.success(workflowService.getWorkflowDetail(id));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdrawWorkflow(@PathVariable Long id) {
        workflowService.withdrawWorkflow(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/advance")
    public ApiResponse<Void> advanceWorkflow(@PathVariable Long id) {
        log.info("workflow advance request received: workflowId={}", id);
        workflowService.advanceWorkflow(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/bind-server-dataset")
    public ApiResponse<Void> bindServerDataset(@PathVariable Long id,
                                               @Valid @RequestBody BindServerDatasetRequest request) {
        log.info(
                "workflow bind dataset request received: workflowId={}, serverDatasetAssetId={}",
                id,
                request.getServerDatasetAssetId()
        );
        workflowService.bindServerDataset(id, request.getServerDatasetAssetId());
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/start-python-job")
    public ApiResponse<Void> startPythonJob(@PathVariable Long id) {
        log.info("workflow start python job request received: workflowId={}", id);
        workflowService.startPythonJob(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/results/save")
    public ApiResponse<Void> saveWorkflowResult(@PathVariable Long id) {
        log.info("workflow save result request received: workflowId={}", id);
        workflowService.saveWorkflowResult(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}/results")
    public ApiResponse<Void> deleteSavedWorkflowResult(@PathVariable Long id) {
        log.info("workflow delete saved result request received: workflowId={}", id);
        workflowService.deleteSavedWorkflowResult(id);
        return ApiResponse.success(null);
    }
}
