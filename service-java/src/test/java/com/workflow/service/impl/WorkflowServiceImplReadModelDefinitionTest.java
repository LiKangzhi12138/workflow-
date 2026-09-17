package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PythonIntegrationProperties;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.workflow.WorkflowDetailVO;
import com.workflow.dto.workflow.WorkflowListItemVO;
import com.workflow.entity.ModelDefinition;
import com.workflow.entity.SysUser;
import com.workflow.entity.Workflow;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplReadModelDefinitionTest {

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
        workflowService = new WorkflowServiceImpl(
                workflowMapper,
                workflowStepMapper,
                sysUserMapper,
                modelAssetMapper,
                datasetAssetMapper,
                loginUserContext,
                pythonJobClient,
                new PythonIntegrationProperties(),
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
        lenient().when(workflowModelUploadSummaryService.summarizeWorkflows(any())).thenReturn(Map.of());
        lenient().when(workflowModelUploadSummaryService.summarizeWorkflow(any())).thenReturn(
                new WorkflowModelUploadSummaryService.WorkflowUploadSummary()
        );
        lenient().when(workflowFederatedAggregationService.resolveFederatedModelAvailability(any())).thenReturn(
                new WorkflowFederatedAggregationService.FederatedModelAvailability(
                        false,
                        "FEDERATED_MODEL_NOT_READY",
                        "not ready",
                        null
                )
        );
        lenient().when(workflowStepMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of(clientUser(), serverUser()));
    }

    @Test
    void clientWorkflowListSupportsLegacyNullDefinitionId() {
        Workflow workflow = workflow(null);
        stubPage("CLIENT", workflow);

        WorkflowListItemVO item = assertDoesNotThrow(
                () -> workflowService.pageWorkflows(1, 10, null).getRecords().get(0)
        );

        assertNull(item.getModelDefinitionId());
        assertNull(item.getModelDefinitionDisplayName());
        assertNull(item.getModelDefinitionFamily());
        assertNull(item.getModelDefinitionVersion());
        assertEquals("YOLOv8", item.getYoloVersion());
        verify(workflowModelDefinitionSelectionService, never()).findDefinitionsByIds(any());
    }

    @Test
    void serverWorkflowListSupportsDefinitionMetadata() {
        Workflow workflow = workflow(1L);
        stubPage("SERVER", workflow);
        when(workflowModelDefinitionSelectionService.findDefinitionsByIds(Set.of(1L))).thenReturn(
                Map.of(1L, definition())
        );

        WorkflowListItemVO item = workflowService.pageWorkflows(1, 10, null).getRecords().get(0);

        assertEquals(1L, item.getModelDefinitionId());
        assertEquals("YOLOV8N_SHEEP_V1", item.getModelDefinitionCode());
        assertEquals("YOLOv8n 羊群检测", item.getModelDefinitionDisplayName());
        assertEquals("YOLO", item.getModelDefinitionFamily());
        assertEquals("YOLOv8", item.getModelDefinitionVersion());
        assertEquals("YOLOv8", item.getYoloVersion());
    }

    @Test
    void clientWorkflowDetailSupportsLegacyNullDefinitionId() {
        Workflow workflow = workflow(null);
        stubDetail("CLIENT", workflow);

        WorkflowDetailVO detail = assertDoesNotThrow(() -> workflowService.getWorkflowDetail(workflow.getId()));

        assertNull(detail.getModelDefinitionId());
        assertNull(detail.getModelDefinitionDisplayName());
        assertNull(detail.getModelDefinitionFamily());
        assertNull(detail.getModelDefinitionVersion());
        assertEquals("YOLOv8", detail.getYoloVersion());
    }

    @Test
    void serverWorkflowDetailSupportsDefinitionMetadata() {
        Workflow workflow = workflow(1L);
        stubDetail("SERVER", workflow);
        when(workflowModelDefinitionSelectionService.findDefinitionsByIds(Set.of(1L))).thenReturn(
                Map.of(1L, definition())
        );

        WorkflowDetailVO detail = workflowService.getWorkflowDetail(workflow.getId());

        assertEquals(1L, detail.getModelDefinitionId());
        assertEquals("YOLOv8n 羊群检测", detail.getModelDefinitionDisplayName());
        assertEquals("YOLO", detail.getModelDefinitionFamily());
        assertEquals("YOLOv8", detail.getModelDefinitionVersion());
    }

    @Test
    void workflowListFallsBackWhenReferencedDefinitionIsMissing() {
        Workflow workflow = workflow(999L);
        stubPage("CLIENT", workflow);
        when(workflowModelDefinitionSelectionService.findDefinitionsByIds(Set.of(999L))).thenReturn(Map.of());

        WorkflowListItemVO item = assertDoesNotThrow(
                () -> workflowService.pageWorkflows(1, 10, null).getRecords().get(0)
        );

        assertEquals(999L, item.getModelDefinitionId());
        assertNull(item.getModelDefinitionDisplayName());
        assertNull(item.getModelDefinitionFamily());
        assertNull(item.getModelDefinitionVersion());
        assertEquals("YOLOv8", item.getYoloVersion());
    }

    @Test
    void workflowDetailFallsBackWhenReferencedDefinitionIsMissing() {
        Workflow workflow = workflow(999L);
        stubDetail("SERVER", workflow);
        when(workflowModelDefinitionSelectionService.findDefinitionsByIds(Set.of(999L))).thenReturn(Map.of());

        WorkflowDetailVO detail = assertDoesNotThrow(() -> workflowService.getWorkflowDetail(workflow.getId()));

        assertEquals(999L, detail.getModelDefinitionId());
        assertNull(detail.getModelDefinitionDisplayName());
        assertNull(detail.getModelDefinitionFamily());
        assertNull(detail.getModelDefinitionVersion());
        assertEquals("YOLOv8", detail.getYoloVersion());
    }

    private void stubPage(String roleCode, Workflow workflow) {
        when(loginUserContext.getCurrentUser()).thenReturn(
                "CLIENT".equals(roleCode) ? clientUser() : serverUser()
        );
        Page<Workflow> page = new Page<>(1, 10);
        page.setRecords(List.of(workflow));
        page.setTotal(1);
        when(workflowMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    }

    private void stubDetail(String roleCode, Workflow workflow) {
        when(loginUserContext.getCurrentUser()).thenReturn(
                "CLIENT".equals(roleCode) ? clientUser() : serverUser()
        );
        when(workflowMapper.selectOne(any(Wrapper.class))).thenReturn(workflow);
    }

    private Workflow workflow(Long modelDefinitionId) {
        Workflow workflow = new Workflow();
        workflow.setId(28L);
        workflow.setWorkflowCode("WF-STAGE1C-READ");
        workflow.setWorkflowName("Stage 1C read regression");
        workflow.setInitiatorUserId(10L);
        workflow.setServerUserId(20L);
        workflow.setModelDefinitionId(modelDefinitionId);
        workflow.setYoloVersion("YOLOv8");
        workflow.setClientModelCount(1);
        workflow.setExpectedModelCount(1);
        workflow.setIsDeleted(0);
        workflow.setIsPublic(0);
        workflow.setStatus("COMPLETED");
        workflow.setProgress(100);
        return workflow;
    }

    private ModelDefinition definition() {
        ModelDefinition definition = new ModelDefinition();
        definition.setId(1L);
        definition.setCode("YOLOV8N_SHEEP_V1");
        definition.setDisplayName("YOLOv8n 羊群检测");
        definition.setModelFamily("YOLO");
        definition.setModelVersion("YOLOv8");
        return definition;
    }

    private SysUser clientUser() {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername("client");
        user.setRoleCode("CLIENT");
        user.setIsDeleted(0);
        return user;
    }

    private SysUser serverUser() {
        SysUser user = new SysUser();
        user.setId(20L);
        user.setUsername("server");
        user.setRoleCode("SERVER");
        user.setIsDeleted(0);
        return user;
    }
}
