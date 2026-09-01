package com.workflow.dto.dataset;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasetAssetListItemVO {

    private Long id;
    private String assetCode;
    private String assetName;
    private String datasetType;
    private String dataFormat;
    private String taskType;
    private Integer sampleCount;
    private String fileName;
    private String filePath;
    private String sourcePath;
    private Long fileSize;
    private Integer imageCount;
    private Integer filePathValidated;
    private String sourceType;
    private String importMode;
    private String recordMode;
    private LocalDateTime lastCheckAt;
    private String lastCheckStatus;
    private String lastCheckMessage;
    private Integer isPublic;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
