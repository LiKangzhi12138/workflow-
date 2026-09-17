package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.workflow.BindLocalModelRequest;
import com.workflow.dto.workflow.ModelUploadInitRequest;
import com.workflow.dto.workflow.ModelUploadInitResponse;
import com.workflow.dto.workflow.WorkflowUploadProgressVO;
import com.workflow.dto.workflow.WorkflowUploadContractVO;
import com.workflow.security.LoginUserContext;
import com.workflow.service.WorkflowModelUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workflow-uploads")
@RequiredArgsConstructor
@Slf4j
public class WorkflowModelUploadController {

    private final WorkflowModelUploadService uploadService;
    private final LoginUserContext loginUserContext;

    @PostMapping("/init")
    @PreAuthorize("hasAuthority('CLIENT')")
    public ApiResponse<ModelUploadInitResponse> initUpload(@Valid @RequestBody ModelUploadInitRequest request) {
        Long currentUserId = loginUserContext.getCurrentUser().getId();
        log.info(
                "workflow upload init request received: workflowId={}, modelAssetId={}, uploaderId={}, originalFilename={}, fileSize={}",
                request.getWorkflowId(),
                request.getModelAssetId(),
                currentUserId,
                request.getOriginalFilename(),
                request.getFileSize()
        );
        return ApiResponse.success(uploadService.initUpload(request, currentUserId));
    }

    @PostMapping("/{uploadId}/file")
    @PreAuthorize("hasAuthority('CLIENT')")
    public ApiResponse<Void> uploadFile(
            @PathVariable Long uploadId,
            @RequestParam String uploadToken,
            @RequestParam(required = false) String clientCryptoMode,
            @RequestParam(required = false) String manifest,
            @RequestParam(required = false) String descriptor,
            @RequestParam("file") MultipartFile file) {
        Long currentUserId = loginUserContext.getCurrentUser().getId();
        log.info(
                "workflow model file upload request received: uploadId={}, uploaderId={}, multipartFilename={}, bytes={}, clientCryptoMode={}",
                uploadId,
                currentUserId,
                file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : null,
                clientCryptoMode
        );
        uploadService.receiveEncryptedFile(
                uploadId,
                uploadToken,
                file,
                manifest,
                descriptor,
                clientCryptoMode,
                currentUserId
        );
        return ApiResponse.success(null);
    }

    @GetMapping("/contract/{workflowId}")
    @PreAuthorize("hasAuthority('CLIENT')")
    public ApiResponse<WorkflowUploadContractVO> getUploadContract(@PathVariable Long workflowId) {
        Long currentUserId = loginUserContext.getCurrentUser().getId();
        return ApiResponse.success(uploadService.getUploadContract(workflowId, currentUserId));
    }

    @PostMapping("/{uploadId}/decrypt")
    @PreAuthorize("hasAuthority('SERVER')")
    public ApiResponse<Void> decryptModel(@PathVariable Long uploadId) {
        Long currentServerUserId = loginUserContext.getCurrentUser().getId();
        uploadService.decryptModelFile(uploadId, currentServerUserId);
        return ApiResponse.success(null);
    }

    @GetMapping("/progress/{workflowId}")
    public ApiResponse<WorkflowUploadProgressVO> getUploadProgress(@PathVariable Long workflowId) {
        Long currentUserId = loginUserContext.getCurrentUser().getId();
        log.info("workflow upload progress requested: workflowId={}, currentUserId={}", workflowId, currentUserId);
        return ApiResponse.success(uploadService.getUploadProgress(workflowId, currentUserId));
    }

    @PostMapping("/bind-local")
    @PreAuthorize("hasAuthority('CLIENT')")
    public ApiResponse<ModelUploadInitResponse> bindLocalModel(@Valid @RequestBody BindLocalModelRequest request) {
        Long currentUserId = loginUserContext.getCurrentUser().getId();
        log.info(
                "workflow bind-local request received: workflowId={}, modelAssetId={}, currentUserId={}",
                request.getWorkflowId(),
                request.getModelAssetId(),
                currentUserId
        );
        return ApiResponse.success(uploadService.bindLocalModelAsset(
                request.getWorkflowId(),
                request.getModelAssetId(),
                currentUserId
        ));
    }
}
