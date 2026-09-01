package com.workflow.dto.dataset;

import lombok.Data;

@Data
public class UpdateDatasetAssetRequest {

    private String assetName;

    private String datasetType;

    private String dataFormat;

    private String taskType;

    private Integer sampleCount;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private Long fileSize;

    private Integer isPublic;

    private String description;

    private String status;
}
