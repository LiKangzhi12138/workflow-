package com.workflow.dto.dashboard;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DashboardWorkflowCardVO {

    private Long id;

    private String workflowCode;

    private String workflowName;

    private String clientModelAssetName;

    private String serverDatasetAssetName;

    private String status;

    private String currentStep;

    private LocalDateTime updatedAt;

    private Boolean receivedByServer;

    private Boolean decryptCompleted;

    private Boolean validationReady;

    private String validationReadyReason;

    private Boolean federatedModelAvailable;

    private String federatedModelUnavailableReason;

    private Integer requiredModelCount;

    private Integer receivedModelCount;

    private Integer collectedModelCount;

    private String latestUploadStatus;
}
