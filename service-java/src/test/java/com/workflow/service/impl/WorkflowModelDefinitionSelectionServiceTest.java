package com.workflow.service.impl;

import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.exception.BusinessException;
import com.workflow.service.ModelDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowModelDefinitionSelectionServiceTest {

    @Mock
    private ModelDefinitionService modelDefinitionService;

    private WorkflowModelDefinitionSelectionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowModelDefinitionSelectionService(modelDefinitionService);
    }

    @Test
    void shouldUseTrustedDefinitionAndIgnoreForgedLegacyVersion() {
        when(modelDefinitionService.isRegistryEnabled()).thenReturn(true);
        when(modelDefinitionService.requireAvailableDefinition(1L)).thenReturn(
                AvailableModelDefinitionVO.builder()
                        .id(1L)
                        .displayName("YOLOv8n 羊群检测")
                        .version("YOLOv8")
                        .build()
        );
        CreateWorkflowRequest request = request(1L, "YOLOv11");

        WorkflowModelDefinitionSelectionService.WorkflowModelSelection result = service.resolve(request);

        assertEquals(1L, result.modelDefinitionId());
        assertEquals("YOLOv8", result.yoloVersion());
        verify(modelDefinitionService).requireAvailableDefinition(1L);
    }

    @Test
    void shouldFailClosedWhenRegistryEnabledButDefinitionMissing() {
        when(modelDefinitionService.isRegistryEnabled()).thenReturn(true);
        when(modelDefinitionService.requireAvailableDefinition(99L))
                .thenThrow(new BusinessException("MODEL_DEFINITION_NOT_FOUND", "missing"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.resolve(request(99L, null))
        );

        assertEquals("MODEL_DEFINITION_NOT_FOUND", exception.getCode());
    }

    @Test
    void shouldPreserveLegacyCreateWhenRegistryDisabled() {
        when(modelDefinitionService.isRegistryEnabled()).thenReturn(false);

        WorkflowModelDefinitionSelectionService.WorkflowModelSelection result =
                service.resolve(request(null, "YOLOv10"));

        assertNull(result.modelDefinitionId());
        assertEquals("YOLOv10", result.yoloVersion());
    }

    @Test
    void shouldRejectDefinitionRequestWhenRegistryDisabled() {
        when(modelDefinitionService.isRegistryEnabled()).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.resolve(request(1L, null))
        );

        assertEquals("MODEL_DEFINITION_REGISTRY_DISABLED", exception.getCode());
    }

    private CreateWorkflowRequest request(Long definitionId, String yoloVersion) {
        CreateWorkflowRequest request = new CreateWorkflowRequest();
        request.setModelDefinitionId(definitionId);
        request.setYoloVersion(yoloVersion);
        return request;
    }
}
