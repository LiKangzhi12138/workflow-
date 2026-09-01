package com.workflow.dto.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateModelAssetRequest {

    @NotBlank(message = "模型名称不能为空")
    private String assetName;

    @NotBlank(message = "模型类型不能为空")
    private String modelType;

    @NotBlank(message = "模型版本不能为空")
    private String modelVersion;

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    private String yoloVersion;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private Long fileSize;

    private Integer isPublic;

    private String description;
}
