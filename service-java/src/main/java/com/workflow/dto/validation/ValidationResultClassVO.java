package com.workflow.dto.validation;

import lombok.Data;

@Data
public class ValidationResultClassVO {

    private String className;

    private String category;

    private Integer count;

    private Double avgConfidence;
}
