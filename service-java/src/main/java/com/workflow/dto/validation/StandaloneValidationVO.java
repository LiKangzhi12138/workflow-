package com.workflow.dto.validation;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StandaloneValidationVO {

    private Long id;

    private String validationCode;

    private String validationMode;

    private Long userId;

    private Long modelAssetId;

    private String modelAssetName;

    private String modelPath;

    private String modelYoloVersion;

    private String modelStatus;

    private Long datasetAssetId;

    private String datasetAssetName;

    private String datasetPath;

    private String datasetStatus;

    private Integer datasetSampleCount;

    private Integer datasetImageCount;

    private String algorithmType;

    private String inputMode;

    private Integer retainInput;

    private String inputCleanupStatus;

    private LocalDateTime inputCleanedAt;

    private String status;

    private Integer progress;

    private String pythonJobId;

    private String metricsJson;

    private String resultFilePath;

    private String qualityLabel;

    private String errorMessage;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
