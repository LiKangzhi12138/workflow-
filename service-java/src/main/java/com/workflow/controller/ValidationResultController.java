package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.validation.ValidationResultViewVO;
import com.workflow.service.ValidationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/validation-results")
@RequiredArgsConstructor
@Slf4j
public class ValidationResultController {

    private final ValidationResultService validationResultService;

    @GetMapping("/standalone/{id}")
    public ApiResponse<ValidationResultViewVO> getStandaloneValidationResult(@PathVariable Long id) {
        return ApiResponse.success(validationResultService.getStandaloneResultView(id));
    }

    @GetMapping("/workflows/{workflowId}")
    public ApiResponse<ValidationResultViewVO> getWorkflowValidationResult(@PathVariable Long workflowId) {
        return ApiResponse.success(validationResultService.getWorkflowResultView(workflowId));
    }

    @GetMapping("/standalone/{id}/samples/{sampleIndex}/image")
    public ResponseEntity<ByteArrayResource> getStandaloneValidationSampleImage(
            @PathVariable Long id,
            @PathVariable int sampleIndex
    ) throws Exception {
        return buildImageResponse(validationResultService.getStandaloneSampleImagePath(id, sampleIndex));
    }

    @GetMapping("/workflows/{workflowId}/samples/{sampleIndex}/image")
    public ResponseEntity<ByteArrayResource> getWorkflowValidationSampleImage(
            @PathVariable Long workflowId,
            @PathVariable int sampleIndex
    ) throws Exception {
        return buildImageResponse(validationResultService.getWorkflowSampleImagePath(workflowId, sampleIndex));
    }

    private ResponseEntity<ByteArrayResource> buildImageResponse(Path imagePath) throws Exception {
        log.info("Serving validation sample image: imagePath={}", imagePath);
        byte[] bytes = Files.readAllBytes(imagePath);
        MediaType mediaType = MediaTypeFactory
                .getMediaType(imagePath.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentLength(bytes.length)
                .contentType(mediaType)
                .body(new ByteArrayResource(bytes));
    }
}
