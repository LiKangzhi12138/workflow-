package com.workflow.service;

import com.workflow.dto.workflow.ModelUploadInitRequest;
import com.workflow.dto.workflow.ModelUploadInitResponse;
import com.workflow.dto.workflow.WorkflowUploadProgressVO;
import org.springframework.web.multipart.MultipartFile;

public interface WorkflowModelUploadService {

    ModelUploadInitResponse initUpload(ModelUploadInitRequest request, Long currentUserId);

    void receiveEncryptedFile(Long uploadId,
                              String uploadToken,
                              MultipartFile file,
                              String clientCryptoMode,
                              Long currentUserId);

    WorkflowUploadProgressVO getUploadProgress(Long workflowId, Long currentUserId);

    void decryptModelFile(Long uploadId, Long currentServerUserId);

    ModelUploadInitResponse bindLocalModelAsset(Long workflowId, Long modelAssetId, Long currentUserId);
}
