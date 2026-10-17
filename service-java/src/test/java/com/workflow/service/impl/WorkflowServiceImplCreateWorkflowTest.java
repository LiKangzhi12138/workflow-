package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.entity.ModelAsset;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
import com.workflow.entity.WorkflowStep;
import com.workflow.mapper.DatasetAssetMapper;
import com.workflow.mapper.ModelAssetMapper;
import com.workflow.mapper.SysUserMapper;
import com.workflow.mapper.WorkflowMapper;
import com.workflow.mapper.WorkflowModelUploadMapper;
import com.workflow.mapper.WorkflowStepMapper;
import com.workflow.security.LoginUserContext;
import com.workflow.service.ValidationResultService;
import com.workflow.service.python.PythonJobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplCreateWorkflowTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowStepMapper workflowStepMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private ModelAssetMapper modelAssetMapper;
    @Mock private DatasetAssetMapper datasetAssetMapper;
    @Mock private LoginUserContext loginUserContext;
    @Mock private PythonJobClient pythonJobClient;
    @Mock private WorkflowModelUploadMapper workflowModelUploadMapper;
    @Mock private WorkflowModelUploadSummaryService workflowModelUploadSummaryService;
    @Mock private WorkflowAcceptAutoManageAsyncService workflowAcceptAutoManageAsyncService;
    @Mock private WorkflowFederatedAggregationService workflowFederatedAggregationService;
    @Mock private ValidationImageCacheService validationImageCacheService;
    @Mock private ValidationResultService validationResultService;
    @Mock private ResultArtifactCleanupService resultArtifactCleanupService;
    @Mock private WorkflowModelDefinitionSelectionService workflowModelDefinitionSelectionService;
    @Mock private WorkflowWeightsValidationPreparationService weightsValidationPreparationService;

    private WorkflowServiceImpl workflowService;

    @BeforeEach
    void setUp() {
        PythonIntegrationProperties pythonProperties = new PythonIntegrationProperties();
        workflowService = new WorkflowServiceImpl(
                workflowMapper,
                workflowStepMapper,
                sysUserMapper,
                modelAssetMapper,
                datasetAssetMapper,
                loginUserContext,
                pythonJobClient,
                pythonProperties,
                new ObjectMapper(),
                workflowModelUploadMapper,
                new WorkflowStorageProperties(),
                workflowModelUploadSummaryService,
                workflowAcceptAutoManageAsyncService,
                workflowFederatedAggregationService,
                validationImageCacheService,
                validationResultService,
                resultArtifactCleanupService,
                workflowModelDefinitionSelectionService,
                weightsValidationPreparationService
        );
    }

    @Test
    void shouldPersistDefinitionBindingAndServerDerivedLegacyVersion() {
        SysUser client = new SysUser();
        client.setId(10L);
        client.setRoleCode("CLIENT");
        when(loginUserContext.getCurrentUser()).thenReturn(client);

        SysUser server = new SysUser();
        server.setId(20L);
        server.setRoleCode("SERVER");
        server.setIsDeleted(0);
        when(sysUserMapper.selectById(20L)).thenReturn(server);

        ModelAsset clientModel = new ModelAsset();
        clientModel.setId(30L);
        clientModel.setAssetName("client checkpoint");
        when(modelAssetMapper.selectOne(any())).thenReturn(clientModel);
        when(workflowModelDefinitionSelectionService.resolve(any())).thenReturn(
                new WorkflowModelDefinitionSelectionService.WorkflowModelSelection(
                        1L,
                        "YOLOv8",
                        "YOLOv8n 羊群检测"
                )
        );
        when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
            Workflow workflow = invocation.getArgument(0);
            workflow.setId(40L);
            return 1;
        });
        when(workflowStepMapper.insert(any(WorkflowStep.class))).thenReturn(1);

        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setWorkflowName("Stage 1C workflow");
        request.setServerUserId(20L);
        request.setClientModelAssetId(30L);
        request.setClientModelCount(1);
        request.setModelDefinitionId(1L);
        request.setYoloVersion("YOLOv11");

        assertEquals(40L, workflowService.createWorkflow(request));

        ArgumentCaptor<Workflow> workflowCaptor = ArgumentCaptor.forClass(Workflow.class);
        org.mockito.Mockito.verify(workflowMapper).insert(workflowCaptor.capture());
        Workflow inserted = workflowCaptor.getValue();
        assertEquals(1L, inserted.getModelDefinitionId());
        assertEquals("YOLOv8", inserted.getYoloVersion());
    }

    @Test
    void shouldCreateDefinitionWorkflowWithoutPreselectedClientAsset() {
        for (long definitionId : new long[] {1L, 2L}) {
            SysUser client = new SysUser();
            client.setId(10L);
            client.setRoleCode("CLIENT");
            when(loginUserContext.getCurrentUser()).thenReturn(client);

            SysUser server = new SysUser();
            server.setId(20L);
            server.setRoleCode("SERVER");
            server.setIsDeleted(0);
            when(sysUserMapper.selectById(20L)).thenReturn(server);
            when(workflowModelDefinitionSelectionService.resolve(any())).thenReturn(
                    new WorkflowModelDefinitionSelectionService.WorkflowModelSelection(
                            definitionId,
                            definitionId == 1L ? "YOLOv8" : "InternImage-T + UPerNet",
                            definitionId == 1L ? "YOLOv8n 羊群检测" : "InternImage-T 小麦倒伏分割"
                    )
            );
            when(workflowMapper.insert(any(Workflow.class))).thenAnswer(invocation -> {
                Workflow workflow = invocation.getArgument(0);
                workflow.setId(40L + definitionId);
                return 1;
            });
            when(workflowStepMapper.insert(any(WorkflowStep.class))).thenReturn(1);

            CreateWorkflowRequest request = new CreateWorkflowRequest();
            request.setWorkflowName("Definition workflow");
            request.setServerUserId(20L);
            request.setClientModelCount(2);
            request.setModelDefinitionId(definitionId);

            assertEquals(40L + definitionId, workflowService.createWorkflow(request));
            ArgumentCaptor<Workflow> workflowCaptor = ArgumentCaptor.forClass(Workflow.class);
            org.mockito.Mockito.verify(workflowMapper).insert(workflowCaptor.capture());
            assertEquals(definitionId, workflowCaptor.getValue().getModelDefinitionId());
            assertNull(workflowCaptor.getValue().getClientModelAssetId());
            verifyNoInteractions(modelAssetMapper);
            org.mockito.Mockito.clearInvocations(workflowMapper, workflowStepMapper, modelAssetMapper);
        }
    }

    @Test
    void shouldStillRequireClientAssetForLegacyWorkflow() {
        SysUser client = new SysUser();
        client.setId(10L);
        client.setRoleCode("CLIENT");
        when(loginUserContext.getCurrentUser()).thenReturn(client);
        SysUser server = new SysUser();
        server.setId(20L);
        server.setRoleCode("SERVER");
        server.setIsDeleted(0);
        when(sysUserMapper.selectById(20L)).thenReturn(server);
        when(workflowModelDefinitionSelectionService.resolve(any())).thenReturn(
                new WorkflowModelDefinitionSelectionService.WorkflowModelSelection(null, "YOLOv10", "YOLOv10")
        );

        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setWorkflowName("Legacy workflow");
        request.setServerUserId(20L);
        request.setClientModelCount(1);

        assertEquals("旧版工作流必须选择客户端模型",
                assertThrows(RuntimeException.class, () -> workflowService.createWorkflow(request)).getMessage());
        verifyNoInteractions(modelAssetMapper, workflowMapper);
    }
}
