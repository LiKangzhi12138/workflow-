package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.validation.StandaloneCallbackRequest;
import com.workflow.service.ValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Slf4j
public class StandaloneCallbackController {

    private final ValidationService validationService;

    @PostMapping("/standalone/callback")
    public ResponseEntity<ApiResponse<Void>> handleStandaloneCallback(
            @Valid @RequestBody StandaloneCallbackRequest request) {
        try {
            validationService.handleStandaloneCallback(request);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException e) {
            log.warn("Standalone callback business error, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}, message={}",
                    request.getJobType(), request.getWorkflowId(), request.getStandaloneValidationId(), request.getJobId(), request.getStatus(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("STANDALONE_CALLBACK_BIZ_ERROR", e.getMessage()));
        } catch (Exception e) {
            log.error("Standalone callback system error, jobType={}, workflowId={}, standaloneValidationId={}, jobId={}, status={}",
                    request.getJobType(), request.getWorkflowId(), request.getStandaloneValidationId(), request.getJobId(), request.getStatus(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("STANDALONE_CALLBACK_SYSTEM_ERROR", e.getMessage()));
        }
    }
}
