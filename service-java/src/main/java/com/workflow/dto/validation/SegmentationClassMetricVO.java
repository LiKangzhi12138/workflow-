package com.workflow.dto.validation;

import lombok.Data;

@Data
public class SegmentationClassMetricVO {
    private Integer classId;
    private String className;
    private Double iou;
    private Double accuracy;
}
