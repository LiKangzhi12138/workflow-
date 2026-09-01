package com.workflow.dto.validation;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UploadStandaloneValidationRequest {

    private MultipartFile modelFile;

    private MultipartFile datasetArchive;

    private String algorithmType;
}
