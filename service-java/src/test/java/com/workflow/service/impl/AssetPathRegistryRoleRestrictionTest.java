package com.workflow.service.impl;

import com.workflow.dto.dataset.CreateDatasetAssetRequest;
import com.workflow.dto.model.CreateModelAssetRequest;
import com.workflow.entity.DatasetAsset;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.support.AssetSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetPathRegistryRoleRestrictionTest {

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private DatasetAssetMapper datasetAssetMapper;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private LoginUserContext loginUserContext;

    @Mock
    private AssetStorageService assetStorageService;

    @Mock
    private FederatedGlobalModelDeletionService federatedGlobalModelDeletionService;

    @Test
    void shouldRejectServerModelPathRegistryCreation() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(20L, "SERVER"));
        ModelAssetServiceImpl service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                federatedGlobalModelDeletionService
        );

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.createModelAsset(modelRequest("/mnt/models/best.pt"))
        );

        assertEquals("SERVER_MODEL_PATH_REGISTRY_UNSUPPORTED", error.getCode());
        assertEquals("服务端用户不支持本地模型路径登记，请使用模型上传或服务端路径导入。", error.getMessage());
        verifyNoInteractions(modelAssetMapper, assetStorageService);
    }

    @Test
    void shouldAllowClientModelPathRegistryCreation() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(10L, "CLIENT"));
        doAnswer(invocation -> {
            ModelAsset asset = invocation.getArgument(0);
            asset.setId(101L);
            return 1;
        }).when(modelAssetMapper).insert(any(ModelAsset.class));
        ModelAssetServiceImpl service = new ModelAssetServiceImpl(
                modelAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService,
                federatedGlobalModelDeletionService
        );

        Long assetId = service.createModelAsset(modelRequest("D:/models/best.pt"));

        assertEquals(101L, assetId);
        ArgumentCaptor<ModelAsset> captor = ArgumentCaptor.forClass(ModelAsset.class);
        verify(modelAssetMapper).insert(captor.capture());
        assertEquals(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY, captor.getValue().getRecordMode());
        assertEquals(AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY, captor.getValue().getSourceType());
    }

    @Test
    void shouldRejectServerDatasetPathRegistryCreation() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(20L, "SERVER"));
        DatasetAssetServiceImpl service = new DatasetAssetServiceImpl(
                datasetAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService
        );

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.createDatasetAsset(datasetRequest("/mnt/datasets/crop"))
        );

        assertEquals("SERVER_DATASET_PATH_REGISTRY_UNSUPPORTED", error.getCode());
        assertEquals("服务端用户不支持本地数据集路径登记，请使用数据集上传或服务端路径导入。", error.getMessage());
        verifyNoInteractions(datasetAssetMapper, assetStorageService);
    }

    @Test
    void shouldAllowClientDatasetPathRegistryCreation() {
        when(loginUserContext.getCurrentUser()).thenReturn(user(10L, "CLIENT"));
        doAnswer(invocation -> {
            DatasetAsset asset = invocation.getArgument(0);
            asset.setId(201L);
            return 1;
        }).when(datasetAssetMapper).insert(any(DatasetAsset.class));
        DatasetAssetServiceImpl service = new DatasetAssetServiceImpl(
                datasetAssetMapper,
                workflowMapper,
                loginUserContext,
                assetStorageService
        );

        Long assetId = service.createDatasetAsset(datasetRequest("D:/datasets/crop.zip"));

        assertEquals(201L, assetId);
        ArgumentCaptor<DatasetAsset> captor = ArgumentCaptor.forClass(DatasetAsset.class);
        verify(datasetAssetMapper).insert(captor.capture());
        assertEquals(AssetSourceCatalog.RECORD_MODE_PATH_REGISTRY, captor.getValue().getRecordMode());
        assertEquals(AssetSourceCatalog.SOURCE_CLIENT_PATH_REGISTRY, captor.getValue().getSourceType());
    }

    private SysUser user(Long id, String roleCode) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRoleCode(roleCode);
        return user;
    }

    private CreateModelAssetRequest modelRequest(String filePath) {
        CreateModelAssetRequest request = new CreateModelAssetRequest();
        request.setAssetName("测试模型");
        request.setModelType("YOLO");
        request.setModelVersion("YOLO10");
        request.setTaskType("DETECTION");
        request.setYoloVersion("YOLOv10");
        request.setFilePath(filePath);
        return request;
    }

    private CreateDatasetAssetRequest datasetRequest(String filePath) {
        CreateDatasetAssetRequest request = new CreateDatasetAssetRequest();
        request.setAssetName("测试数据集");
        request.setDatasetType("CROP");
        request.setFilePath(filePath);
        return request;
    }
}
