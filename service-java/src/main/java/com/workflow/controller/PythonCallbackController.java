package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.python.PythonCallbackRequest;
import com.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/python")
@RequiredArgsConstructor
@Slf4j
public class PythonCallbackController {

    private final WorkflowService workflowService;

    @PostMapping("/jobs/callback")
    public ResponseEntity<ApiResponse<Void>> handleJobCallback(@Valid @RequestBody PythonCallbackRequest request) {
        try {
            workflowService.handlePythonCallback(request);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException e) {
            log.warn("Python callback business error, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}, message={}",
                    request.getJobType(), request.getWorkflowId(), request.getStandaloneValidationId(), request.getJobId(), request.getStatus(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("PY_CALLBACK_BIZ_ERROR", e.getMessage()));
        } catch (Exception e) {
            log.error("Python callback system error, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}",
                    request.getJobType(), request.getWorkflowId(), request.getStandaloneValidationId(), request.getJobId(), request.getStatus(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("PY_CALLBACK_SYSTEM_ERROR", e.getMessage()));
        }
    }
}
