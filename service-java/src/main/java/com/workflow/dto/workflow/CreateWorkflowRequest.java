package com.workflow.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateWorkflowRequest {

    @NotBlank(message = "工作流名称不能为空")
    private String workflowName;

    @NotNull(message = "服务端用户ID不能为空")
    private Long serverUserId;

    @NotNull(message = "客户端模型ID不能为空")
    private Long clientModelAssetId;

    @NotNull(message = "客户端模型数量不能为空")
    private Integer clientModelCount;

    @NotBlank(message = "请选择YOLO模型版本")
    private String yoloVersion = "YOLOv10";

    private Integer isPublic;

    private Boolean dpEnabled;

    private BigDecimal dpEpsilon;

    private BigDecimal dpDelta;

    private BigDecimal dpClipNorm;

    private BigDecimal dpNoiseMultiplier;

    private Boolean shuffleEnabled;

    private Boolean secureAggregationEnabled;

    private String secureAggregationMode;

    private String remark;
}
