package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workflow.support.WorkflowCurrentStepSupport;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("workflow")
public class Workflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String workflowCode;

    private String workflowName;

    private Long initiatorUserId;

    private Long serverUserId;

    private Long clientModelAssetId;

    private Long serverDatasetAssetId;

    private Integer clientModelCount;

    private Integer expectedModelCount;

    private String yoloVersion;

    private Integer collectedModelCount;

    private String federatedStatus;

    private String federatedStrategy;

    private Long federatedModelAssetId;

    private LocalDateTime federatedStartedAt;

    private LocalDateTime federatedFinishedAt;

    private Integer dpEnabled;

    private BigDecimal dpEpsilon;

    private BigDecimal dpDelta;

    private BigDecimal dpClipNorm;

    private BigDecimal dpNoiseMultiplier;

    private String dpStatus;

    private String dpSummary;

    private Integer shuffleEnabled;

    private String shuffleBatchNo;

    private String shuffleStatus;

    private String shuffleOrderSummary;

    private LocalDateTime shuffleStartedAt;

    private LocalDateTime shuffleFinishedAt;

    private Integer secureAggregationEnabled;

    private String secureAggregationMode;

    private String secureAggregationStatus;

    private String secureAggregationSummary;

    private LocalDateTime secureAggregationStartedAt;

    private LocalDateTime secureAggregationFinishedAt;

    private Integer isPublic;

    private String status;

    private String currentStep;

    private Integer progress;

    private String pythonJobId;

    private String remark;

    private String metricsJson;

    private String resultFilePath;

    private String resultRetentionStatus;

    private LocalDateTime resultSavedAt;

    private LocalDateTime resultDeletedAt;

    private String errorMessage;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void setCurrentStep(String currentStep) {
        this.currentStep = WorkflowCurrentStepSupport.sanitizeCurrentStep(currentStep);
    }
}
