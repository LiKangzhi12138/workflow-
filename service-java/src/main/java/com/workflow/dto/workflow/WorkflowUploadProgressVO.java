package com.workflow.dto.workflow;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkflowUploadProgressVO {

    private Long workflowId;

    private String workflowName;

    private String yoloVersion;

    private Integer requiredModelCount;

    private Integer activeUploadCount;

    private Integer receivedModelCount;

    private Integer collectedModelCount;

    private Boolean uploadLimitReached;

    private Boolean replaceAllowed;

    private String latestUploadStatus;

    private String status;

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

    private Boolean secureAggregationEnabled;

    private String secureAggregationMode;

    private String secureAggregationStatus;

    private String secureAggregationSummary;

    private List<UploadRecordVO> uploadRecords;

    @Data
    public static class UploadRecordVO {

        private Long uploadId;

        private String uploaderName;

        private String uploadStatus;

        private LocalDateTime uploadedAt;

        private String originalFilename;

        private String clientDisplayName;

        private Long modelAssetId;

        private Long serverModelAssetId;

        private Integer federatedRound;

        private Integer federatedWeight;

        private String aggregationStatus;

        private String errorMessage;
    }
}
