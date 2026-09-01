package com.workflow.dto.validation;

import lombok.Data;

import java.util.List;

@Data
public class ValidationResultSampleVO {

    private Integer sampleIndex;

    private String imageName;

    private String imageUrl;

    private Boolean isAnnotated;

    private Boolean annotatedImageAvailable;

    private Integer predictionCount;

    private List<ValidationResultPredictionVO> predictions;
}
