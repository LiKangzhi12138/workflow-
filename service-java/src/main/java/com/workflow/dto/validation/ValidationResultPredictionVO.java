package com.workflow.dto.validation;

import lombok.Data;

import java.util.List;

@Data
public class ValidationResultPredictionVO {

    private String label;

    private Double confidence;

    private String category;

    private List<Double> bbox;
}
