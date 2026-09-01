package com.workflow.dto.dataset;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDatasetAssetRequest {

    @NotBlank(message = "数据集名称不能为空")
    private String assetName;

    @NotBlank(message = "请选择数据集类型（庄稼/畜牧/通用）")
    private String datasetType;

    private Integer sampleCount;

    private String fileName;

    private String filePath;

    private String sourcePath;

    private Long fileSize;

    private Integer isPublic;

    private String description;
}
