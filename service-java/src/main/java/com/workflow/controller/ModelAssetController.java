package com.workflow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.common.ApiResponse;
import com.workflow.dto.model.CreateModelAssetRequest;
import com.workflow.dto.model.ModelAssetDetailVO;
import com.workflow.dto.model.ModelAssetListItemVO;
import com.workflow.dto.model.UpdateModelAssetRequest;
import com.workflow.dto.model.UploadModelAssetRequest;
import com.workflow.service.ModelAssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelAssetController {

    private final ModelAssetService modelAssetService;

    @PostMapping
    public ApiResponse<Long> createModelAsset(@Valid @RequestBody CreateModelAssetRequest request) {
        return ApiResponse.success("模型路径登记成功", modelAssetService.createModelAsset(request));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> uploadModelAsset(@Valid @ModelAttribute UploadModelAssetRequest request) {
        return ApiResponse.success("模型正式上传成功", modelAssetService.uploadModelAsset(request));
    }

    @PostMapping("/server-import")
    @PreAuthorize("hasAuthority('SERVER')")
    public ApiResponse<Long> importModelAssetFromServerPath(@Valid @RequestBody CreateModelAssetRequest request) {
        return ApiResponse.success("服务器模型已导入正式资产库", modelAssetService.importModelAssetFromServerPath(request));
    }

    @GetMapping
    public ApiResponse<Page<ModelAssetListItemVO>> pageModelAssets(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean validated,
            @RequestParam(required = false) String recordMode
    ) {
        return ApiResponse.success(
                modelAssetService.pageModelAssets(pageNum, pageSize, keyword, validated, recordMode)
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelAssetDetailVO> getModelAssetDetail(@PathVariable Long id) {
        return ApiResponse.success(modelAssetService.getModelAssetDetail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateModelAsset(@PathVariable Long id,
                                              @RequestBody UpdateModelAssetRequest request) {
        modelAssetService.updateModelAsset(id, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/check")
    public ApiResponse<Void> checkModelAsset(@PathVariable Long id) {
        modelAssetService.checkModelAsset(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteModelAsset(@PathVariable Long id) {
        modelAssetService.deleteModelAsset(id);
        return ApiResponse.success(null);
    }
}
