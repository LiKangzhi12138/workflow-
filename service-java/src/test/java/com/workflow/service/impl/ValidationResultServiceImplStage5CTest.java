package com.workflow.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.dto.validation.ValidationResultViewVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ValidationResultServiceImplStage5CTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ValidationResultServiceImpl service = new ValidationResultServiceImpl(
            null, null, null, null, null, null, null, null, mapper, null, null
    );

    @Test
    void mapsSegmentationMetricsWithoutReusingDetectionFields() throws Exception {
        JsonNode metrics = mapper.readTree("""
                {"taskType":"SEMANTIC_SEGMENTATION","metricsMode":"GROUND_TRUTH",
                 "mIoU":0.7,"mAcc":0.8,"aAcc":0.9,
                 "perClass":[{"classId":0,"className":"background","iou":0.75,"accuracy":0.85}]}
                """);
        ValidationResultViewVO view = new ValidationResultViewVO();

        ReflectionTestUtils.invokeMethod(service, "setSegmentationMetrics", view, metrics, mapper.createObjectNode(), mapper.createObjectNode());

        assertEquals("SEMANTIC_SEGMENTATION", view.getTaskType());
        assertEquals("GROUND_TRUTH", view.getMetricsMode());
        assertEquals(0.7, view.getMIoU());
        assertEquals(0.8, view.getMAcc());
        assertEquals(0.9, view.getAAcc());
        assertEquals("background", view.getSegmentationPerClass().getFirst().getClassName());
        assertNull(view.getMap50());
        assertNull(view.getAccuracy());
    }

    @Test
    void keepsDetectionResultSeparate() throws Exception {
        JsonNode metrics = mapper.readTree("{\"taskType\":\"DETECTION\",\"map50\":0.91}");
        ValidationResultViewVO view = new ValidationResultViewVO();

        ReflectionTestUtils.invokeMethod(service, "setSegmentationMetrics", view, metrics, mapper.createObjectNode(), mapper.createObjectNode());

        assertEquals("DETECTION", view.getTaskType());
        assertNull(view.getSegmentationPerClass());
    }
}
