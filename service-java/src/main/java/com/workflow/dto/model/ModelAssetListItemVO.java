package com.workflow.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModelAssetListItemVO {

    private Long id;
    private String assetCode;
    private String assetName;
    private String modelType;
    private String modelVersion;
    private String taskType;
    private String fileName;
    private String filePath;
    private String sourcePath;
    private Long fileSize;
    private Integer filePathValidated;
    private String sourceType;
    private String importMode;
    private String recordMode;
    private LocalDateTime lastCheckAt;
    private String lastCheckStatus;
    private String lastCheckMessage;
    private String yoloVersion;
    private Integer isPublic;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
