package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_model_upload")
public class WorkflowModelUpload {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("workflow_id")
    private Long workflowId;

    @TableField("uploader_id")
    private Long uploaderId;

    @TableField("model_asset_id")
    private Long modelAssetId;

    @TableField("original_filename")
    private String originalFilename;

    @TableField("stored_filename")
    private String storedFilename;

    @TableField("encrypted_file_path")
    private String encryptedFilePath;

    @TableField("decrypted_file_path")
    private String decryptedFilePath;

    @TableField("server_model_asset_id")
    private Long serverModelAssetId;

    @TableField("federated_round")
    private Integer federatedRound;

    @TableField("federated_weight")
    private Integer federatedWeight;

    @TableField("aggregation_status")
    private String aggregationStatus;

    @TableField("client_display_name")
    private String clientDisplayName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("file_sha256")
    private String fileSha256;

    @TableField("aes_key_base64")
    private String aesKeyBase64;

    @TableField("aes_iv_base64")
    private String aesIvBase64;

    @TableField("upload_token")
    private String uploadToken;

    @TableField("upload_status")
    private String uploadStatus;

    @TableField("error_message")
    private String errorMessage;

    @TableField("token_expire_at")
    private LocalDateTime tokenExpireAt;

    @TableField("uploaded_at")
    private LocalDateTime uploadedAt;

    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
