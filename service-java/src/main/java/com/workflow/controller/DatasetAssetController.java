package com.workflow.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.common.ApiResponse;
import com.workflow.dto.dataset.CreateDatasetAssetRequest;
import com.workflow.dto.dataset.DatasetAssetDetailVO;
import com.workflow.dto.dataset.DatasetAssetListItemVO;
import com.workflow.dto.dataset.UpdateDatasetAssetRequest;
import com.workflow.dto.dataset.UploadDatasetAssetRequest;
import com.workflow.service.DatasetAssetService;
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
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetAssetController {

    private final DatasetAssetService datasetAssetService;

    @PostMapping
    public ApiResponse<Long> createDatasetAsset(@Valid @RequestBody CreateDatasetAssetRequest request) {
        return ApiResponse.success("数据集路径登记成功", datasetAssetService.createDatasetAsset(request));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> uploadDatasetAsset(@Valid @ModelAttribute UploadDatasetAssetRequest request) {
        return ApiResponse.success("数据集正式上传成功", datasetAssetService.uploadDatasetAsset(request));
    }

    @PostMapping("/server-import")
    @PreAuthorize("hasAuthority('SERVER')")
    public ApiResponse<Long> importDatasetAssetFromServerPath(@Valid @RequestBody CreateDatasetAssetRequest request) {
        return ApiResponse.success("服务器数据集已导入正式资产库", datasetAssetService.importDatasetAssetFromServerPath(request));
    }

    @GetMapping
    public ApiResponse<Page<DatasetAssetListItemVO>> pageDatasetAssets(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean validated,
            @RequestParam(required = false) String recordMode
    ) {
        return ApiResponse.success(
                datasetAssetService.pageDatasetAssets(pageNum, pageSize, keyword, validated, recordMode)
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasetAssetDetailVO> getDatasetAssetDetail(@PathVariable Long id) {
        return ApiResponse.success(datasetAssetService.getDatasetAssetDetail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateDatasetAsset(@PathVariable Long id,
                                                @RequestBody UpdateDatasetAssetRequest request) {
        datasetAssetService.updateDatasetAsset(id, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/check")
    public ApiResponse<Void> checkDatasetAsset(@PathVariable Long id) {
        datasetAssetService.checkDatasetAsset(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDatasetAsset(@PathVariable Long id) {
        datasetAssetService.deleteDatasetAsset(id);
        return ApiResponse.success(null);
    }
}
