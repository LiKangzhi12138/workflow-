package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dataset_asset")
public class DatasetAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String assetCode;

    private String assetName;

    private Long ownerUserId;

    private String ownerRoleCode;

    private String datasetType;

    private String dataFormat;

    private String taskType;

    private Integer sampleCount;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private String sourceType;

    private String importMode;

    private String recordMode;

    private Long fileSize;

    private Integer imageCount;

    private Integer filePathValidated;

    private LocalDateTime lastCheckAt;

    private String lastCheckStatus;

    private String lastCheckMessage;

    private Integer isPublic;

    private String status;

    private String description;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
