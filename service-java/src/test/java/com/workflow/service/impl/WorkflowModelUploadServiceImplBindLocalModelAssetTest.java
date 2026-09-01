package com.workflow.service.impl;

import com.workflow.config.WorkflowStorageProperties;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowModelUpload;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.service.ModelAssetService;
import com.workflow.service.WorkflowStepService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowModelUploadServiceImplBindLocalModelAssetTest {

    @Mock
    private WorkflowModelUploadMapper uploadMapper;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private ModelAssetMapper modelAssetMapper;

    @Mock
    private WorkflowStepService workflowStepService;

    @Mock
    private ModelAssetService modelAssetService;

    @Mock
    private WorkflowModelUploadStateService workflowModelUploadStateService;

    @Mock
    private WorkflowModelUploadSummaryService workflowModelUploadSummaryService;

    @Mock
    private WorkflowAutoManageStatusService workflowAutoManageStatusService;

    @Mock
    private WorkflowFederatedAggregationService workflowFederatedAggregationService;

    private WorkflowModelUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        WorkflowStorageProperties workflowStorageProperties = new WorkflowStorageProperties();
        service = new WorkflowModelUploadServiceImpl(
                uploadMapper,
                workflowMapper,
                sysUserMapper,
                modelAssetMapper,
                workflowStepService,
                modelAssetService,
                workflowStorageProperties,
                workflowModelUploadStateService,
                workflowModelUploadSummaryService,
                workflowAutoManageStatusService,
                workflowFederatedAggregationService
        );
    }

    @Test
    void shouldRejectBindLocalModelBecauseRealFileUploadIsRequired() {
        Workflow workflow = new Workflow();
        workflow.setId(9L);
        workflow.setWorkflowCode("WF202604101200001234");
        workflow.setInitiatorUserId(11L);
        workflow.setStatus("ACCEPTED");
        workflow.setIsDeleted(0);
        when(workflowMapper.selectById(9L)).thenReturn(workflow);

        ModelAsset modelAsset = new ModelAsset();
        modelAsset.setId(31L);
        modelAsset.setOwnerUserId(11L);
        modelAsset.setOwnerRoleCode("CLIENT");
        modelAsset.setStatus("READY");
        modelAsset.setFilePathValidated(1);
        modelAsset.setFilePath("C:\\Users\\33284\\Desktop\\client_workspace\\model\\yolov8n.pt");
        when(modelAssetMapper.selectById(31L)).thenReturn(modelAsset);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.bindLocalModelAsset(9L, 31L, 11L)
        );

        assertEquals("REAL_FILE_UPLOAD_REQUIRED", error.getCode());
        verify(uploadMapper, never()).insert(org.mockito.ArgumentMatchers.<WorkflowModelUpload>any());
        verify(workflowMapper, never()).incrementCollectedModelCount(any());
        verify(workflowStepService, never()).appendWorkflowStep(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
