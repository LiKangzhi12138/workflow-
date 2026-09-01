package com.workflow.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ModelUploadInitRequest {

    @NotNull(message = "workflowId 不能为空")
    private Long workflowId;

    private Long modelAssetId;

    @NotBlank(message = "originalFilename 不能为空")
    private String originalFilename;

    @NotNull(message = "fileSize 不能为空")
    private Long fileSize;

    /**
     * Optional in compatibility mode. When the browser cannot calculate SHA-256
     * locally, the server will compute it after receiving the plaintext upload.
     */
    private String fileSha256;

    private Boolean replaceExisting;
}
