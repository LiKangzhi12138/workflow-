package com.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.validation.CreateStandaloneValidationRequest;
import com.workflow.dto.validation.StandaloneCallbackRequest;
import com.workflow.dto.validation.StandaloneValidationVO;
import com.workflow.dto.validation.UploadStandaloneValidationRequest;

import java.util.List;

public interface ValidationService {

    Long createStandaloneValidation(CreateStandaloneValidationRequest request);

    StandaloneValidationVO submitStandaloneValidation(CreateStandaloneValidationRequest request);

    StandaloneValidationVO submitTemporaryStandaloneValidation(UploadStandaloneValidationRequest request);

    void startStandaloneValidation(Long validationId);

    void handleStandaloneCallback(StandaloneCallbackRequest request);

    StandaloneValidationVO getValidationDetail(Long validationId);

    Page<StandaloneValidationVO> pageValidations(long pageNum, long pageSize);

    List<StandaloneValidationVO> listMyStandaloneValidations(int limit);
}
