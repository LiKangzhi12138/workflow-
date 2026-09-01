package com.workflow.dto.python;

import lombok.Data;

@Data
public class PythonFederatedAggregateResponse {

    private String code;

    private String message;

    private DataPayload data;

    @Data
    public static class DataPayload {
        private String status;
        private String strategy;
        private Integer sourceModelCount;
        private String globalModelPath;
        private String summary;
        private String errorMessage;
        private String detailMessage;
        private String dpSummary;
        private String shuffleSummary;
        private String secureAggregationSummary;
    }
}
