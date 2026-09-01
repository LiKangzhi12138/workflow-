package com.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.dataset.CreateDatasetAssetRequest;
import com.workflow.dto.dataset.DatasetAssetDetailVO;
import com.workflow.dto.dataset.DatasetAssetListItemVO;
import com.workflow.dto.dataset.UpdateDatasetAssetRequest;
import com.workflow.dto.dataset.UploadDatasetAssetRequest;

public interface DatasetAssetService {

    Long createDatasetAsset(CreateDatasetAssetRequest request);

    Long uploadDatasetAsset(UploadDatasetAssetRequest request);

    Long importDatasetAssetFromServerPath(CreateDatasetAssetRequest request);

    Page<DatasetAssetListItemVO> pageDatasetAssets(long pageNum, long pageSize, String keyword, Boolean validated, String recordMode);

    DatasetAssetDetailVO getDatasetAssetDetail(Long id);

    void updateDatasetAsset(Long id, UpdateDatasetAssetRequest request);

    void checkDatasetAsset(Long id);

    void deleteDatasetAsset(Long id);
}
