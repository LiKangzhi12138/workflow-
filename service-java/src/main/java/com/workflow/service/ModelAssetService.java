package com.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workflow.dto.model.CreateModelAssetRequest;
import com.workflow.dto.model.ModelAssetDetailVO;
import com.workflow.dto.model.ModelAssetListItemVO;
import com.workflow.dto.model.UpdateModelAssetRequest;
import com.workflow.dto.model.UploadModelAssetRequest;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.entity.ModelDefinition;

import java.nio.file.Path;

public interface ModelAssetService {

    Long createModelAsset(CreateModelAssetRequest request);

    Long uploadModelAsset(UploadModelAssetRequest request);

    Long importModelAssetFromServerPath(CreateModelAssetRequest request);

    Long registerDecryptedServerModel(Workflow workflow, WorkflowModelUpload upload, Path decryptedFilePath);

    Long registerWeightsProtocolV1Asset(
            Workflow workflow,
            WorkflowModelUpload upload,
            ModelDefinition definition,
            Path weightsFilePath,
            String validationSummary
    );

    Long registerFederatedServerModel(Workflow workflow, Path federatedModelPath, int sourceModelCount);

    Long registerFederatedWeightsV1Asset(
            Workflow workflow,
            ModelDefinition definition,
            Path weightsPath,
            int sourceModelCount,
            String outputSha256
    );

    Page<ModelAssetListItemVO> pageModelAssets(long pageNum, long pageSize, String keyword, Boolean validated, String recordMode);

    ModelAssetDetailVO getModelAssetDetail(Long id);

    void updateModelAsset(Long id, UpdateModelAssetRequest request);

    void checkModelAsset(Long id);

    void deleteModelAsset(Long id);
}
