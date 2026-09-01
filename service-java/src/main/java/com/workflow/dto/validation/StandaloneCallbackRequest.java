package com.workflow.dto.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StandaloneCallbackRequest {

    @NotBlank(message = "jobId \u4E0D\u80FD\u4E3A\u7A7A")
    private String jobId;

    private String jobType;

    private Long workflowId;

    private Long standaloneValidationId;

    @NotBlank(message = "status \u4E0D\u80FD\u4E3A\u7A7A")
    private String status;

    private Integer progress;

    private String message;

    private Map<String, Object> metrics;

    private Object resultFile;

    private String errorMessage;

    @NotBlank(message = "sign \u4E0D\u80FD\u4E3A\u7A7A")
    private String sign;
}
