package com.workflow.dto.workflow;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ModelUploadInitResponse {

    private Long uploadId;

    private Long workflowId;

    private Long modelAssetId;

    private String aesKeyBase64;

    private String aesIvBase64;

    private String uploadToken;

    private LocalDateTime tokenExpireAt;
}
