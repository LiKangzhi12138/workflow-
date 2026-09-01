package com.workflow.dto.dataset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UploadDatasetAssetRequest {

    @NotBlank(message = "数据集名称不能为空")
    private String assetName;

    @NotBlank(message = "请选择数据集类型")
    private String datasetType;

    private Integer sampleCount;

    private Integer isPublic;

    private String description;

    @NotNull(message = "请先选择需要上传的数据集压缩包")
    private MultipartFile file;
}
