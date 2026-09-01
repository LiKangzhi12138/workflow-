package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_asset")
public class ModelAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String assetCode;

    private String assetName;

    private Long ownerUserId;

    private String ownerRoleCode;

    private String modelType;

    private String modelVersion;

    private String taskType;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private String sourceType;

    private String importMode;

    private String recordMode;

    private Long fileSize;

    private Integer filePathValidated;

    private LocalDateTime lastCheckAt;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private String yoloVersion;

    private Integer isPublic;

    private String status;

    private String description;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
