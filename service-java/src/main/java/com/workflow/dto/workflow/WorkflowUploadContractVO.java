package com.workflow.dto.workflow;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorkflowUploadContractVO {

    private Long workflowId;
    private String uploadProtocol;
    private boolean manifestRequired;
    private boolean descriptorRequired;
    private String acceptedArtifactType;
}
