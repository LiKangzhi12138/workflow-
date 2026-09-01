package com.workflow.dto.validation;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class SubmitTemporaryStandaloneValidationRequest {

    private MultipartFile modelFile;

    private MultipartFile datasetArchive;

    private String algorithmType;

    private Long preferredModelRegistryId;

    private Long preferredDatasetRegistryId;
}
