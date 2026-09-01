package com.workflow.dto.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UploadModelAssetRequest {

    @NotBlank(message = "模型名称不能为空")
    private String assetName;

    @NotBlank(message = "模型类型不能为空")
    private String modelType;

    @NotBlank(message = "模型版本不能为空")
    private String modelVersion;

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    private String yoloVersion;

    private Integer isPublic;

    private String description;

    @NotNull(message = "请先选择需要上传的模型文件")
    private MultipartFile file;
}
