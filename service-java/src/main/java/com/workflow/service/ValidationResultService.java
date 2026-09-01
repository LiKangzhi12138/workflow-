package com.workflow.service;

import com.workflow.dto.validation.ValidationResultViewVO;

import java.nio.file.Path;

public interface ValidationResultService {

    ValidationResultViewVO getStandaloneResultView(Long validationId);

    ValidationResultViewVO getWorkflowResultView(Long workflowId);

    void warmStandaloneResultCache(Long validationId);

    void warmWorkflowResultCache(Long workflowId);

    Path getStandaloneSampleImagePath(Long validationId, int sampleIndex);

    Path getWorkflowSampleImagePath(Long workflowId, int sampleIndex);
}
