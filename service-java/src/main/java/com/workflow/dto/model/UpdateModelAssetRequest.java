package com.workflow.dto.model;

import lombok.Data;

@Data
public class UpdateModelAssetRequest {

    private String assetName;

    private String modelType;

    private String modelVersion;

    private String taskType;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private Long fileSize;

    private String yoloVersion;

    private Integer isPublic;

    private String description;

    private String status;
}
