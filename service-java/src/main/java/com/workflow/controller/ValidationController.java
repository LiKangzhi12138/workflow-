package com.workflow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.common.ApiResponse;
import com.workflow.dto.validation.CreateStandaloneValidationRequest;
import com.workflow.dto.validation.StandaloneValidationVO;
import com.workflow.dto.validation.UploadStandaloneValidationRequest;
import com.workflow.service.ValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/validations")
@RequiredArgsConstructor
public class ValidationController {

    private final ValidationService validationService;

    @PostMapping("/standalone")
    public ApiResponse<StandaloneValidationVO> submitStandaloneValidation(
            @Valid @RequestBody CreateStandaloneValidationRequest request) {
        return ApiResponse.success(validationService.submitStandaloneValidation(request));
    }

    @PostMapping(value = "/standalone/temp-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<StandaloneValidationVO> submitTemporaryStandaloneValidation(
            @ModelAttribute UploadStandaloneValidationRequest request) {
        return ApiResponse.success(validationService.submitTemporaryStandaloneValidation(request));
    }

    @GetMapping("/standalone/{id}")
    public ApiResponse<StandaloneValidationVO> getStandaloneValidationDetail(@PathVariable Long id) {
        return ApiResponse.success(validationService.getValidationDetail(id));
    }

    @GetMapping("/standalone/my")
    public ApiResponse<List<StandaloneValidationVO>> listMyStandaloneValidations(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.success(validationService.listMyStandaloneValidations(limit));
    }

    @PostMapping
    public ApiResponse<Long> createStandaloneValidation(
            @Valid @RequestBody CreateStandaloneValidationRequest request) {
        Long validationId = validationService.createStandaloneValidation(request);
        return ApiResponse.success(validationId);
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Void> startStandaloneValidation(@PathVariable Long id) {
        validationService.startStandaloneValidation(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}")
    public ApiResponse<StandaloneValidationVO> getValidationDetail(@PathVariable Long id) {
        return ApiResponse.success(validationService.getValidationDetail(id));
    }

    @GetMapping
    public ApiResponse<Page<StandaloneValidationVO>> pageValidations(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize
    ) {
        return ApiResponse.success(validationService.pageValidations(pageNum, pageSize));
    }
}
