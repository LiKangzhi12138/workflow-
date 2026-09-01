package com.workflow.dto.workflow;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindServerDatasetRequest {

    @NotNull(message = "服务器端数据集ID不能为空")
    private Long serverDatasetAssetId;
}