package com.workflow.dto.workflow;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 客户端绑定本地模型资产到工作流的请求
 */
@Data
public class BindLocalModelRequest {

    @NotNull(message = "workflowId 不能为空")
    private Long workflowId;

    @NotNull(message = "modelAssetId 不能为空")
    private Long modelAssetId;
}
