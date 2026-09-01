package com.workflow.dto.workflow;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkflowDetailVO {

    private Long id;
    private String workflowCode;
    private String workflowName;
    private Long initiatorUserId;
    private String initiatorUsername;
    private Long serverUserId;
    private String serverUsername;

    private Long clientModelAssetId;
    private String clientModelAssetName;
    private String clientModelVersion;
    private Integer clientModelCount;
    private Integer expectedModelCount;
    private String yoloVersion;
    private Integer activeUploadCount;
    private Integer receivedModelCount;
    private Integer collectedModelCount;
    private Boolean uploadLimitReached;
    private String latestUploadStatus;
    private String federatedStatus;
    private String federatedStrategy;
    private Long federatedModelAssetId;
    private String federatedModelAssetName;
    private Boolean federatedModelAvailable;
    private String federatedModelUnavailableReason;
    private LocalDateTime federatedStartedAt;
    private LocalDateTime federatedFinishedAt;
    private Boolean dpEnabled;
    private BigDecimal dpEpsilon;
    private BigDecimal dpDelta;
    private BigDecimal dpClipNorm;
    private BigDecimal dpNoiseMultiplier;
    private String dpStatus;
    private String dpSummary;
    private Boolean shuffleEnabled;
    private String shuffleBatchNo;
    private String shuffleStatus;
    private String shuffleOrderSummary;
    private LocalDateTime shuffleStartedAt;
    private LocalDateTime shuffleFinishedAt;
    private Boolean secureAggregationEnabled;
    private String secureAggregationMode;
    private String secureAggregationStatus;
    private String secureAggregationSummary;
    private LocalDateTime secureAggregationStartedAt;
    private LocalDateTime secureAggregationFinishedAt;

    private Long serverDatasetAssetId;
    private String serverDatasetAssetName;
    private String serverDatasetDataFormat;
    private String serverDatasetTaskType;

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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<WorkflowStepVO> steps;
}
