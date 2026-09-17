package com.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.ModelDefinitionRegistryProperties;
import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.dto.python.PythonModelDefinitionCheckResponse;
import com.workflow.entity.ModelDefinition;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelDefinitionMapper;
import com.workflow.service.python.PythonModelDefinitionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelDefinitionServiceImplTest {

    @Mock
    private ModelDefinitionMapper modelDefinitionMapper;
    @Mock
    private PythonModelDefinitionClient pythonModelDefinitionClient;

    private ModelDefinitionRegistryProperties properties;
    private ModelDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ModelDefinitionRegistryProperties();
        properties.setEnabled(true);
        service = new ModelDefinitionServiceImpl(
                modelDefinitionMapper,
                pythonModelDefinitionClient,
                properties,
                new ObjectMapper()
        );
    }

    @Test
    void shouldReturnOnlyDefinitionThatPythonProvesAvailable() {
        ModelDefinition definition = definition();
        when(modelDefinitionMapper.selectList(any())).thenReturn(List.of(definition));
        when(pythonModelDefinitionClient.check(any(), eq("YOLO_RUNTIME_V1")))
                .thenReturn(availableResponse());

        List<AvailableModelDefinitionVO> result = service.listAvailableDefinitions();

        assertEquals(1, result.size());
        assertEquals(1L, result.getFirst().getId());
        assertEquals("YOLOV8N_SHEEP_V1", result.getFirst().getCode());
        assertEquals(List.of("sheep"), result.getFirst().getClasses());
        assertTrue(result.getFirst().getAvailability().getAvailable());
        assertTrue(result.getFirst().getWorkflowSelectable());
    }

    @Test
    void shouldExposeAvailableInternImageAsWorkflowSelectableInStage5B() {
        ModelDefinition yolo = definition();
        ModelDefinition internImage = internImageDefinition();
        when(modelDefinitionMapper.selectList(any())).thenReturn(List.of(yolo, internImage));
        when(pythonModelDefinitionClient.check(any(), any())).thenReturn(availableResponse());

        List<AvailableModelDefinitionVO> result = service.listAvailableDefinitions();

        assertEquals(2, result.size());
        assertTrue(result.getFirst().getWorkflowSelectable());
        assertTrue(result.get(1).getWorkflowSelectable());
        assertEquals("INTERNIMAGE_T_UPERNET_WHEAT_V1", result.get(1).getCode());
    }

    @Test
    void shouldResolveAvailableInternImageAtCreateTimeInStage5B() {
        ModelDefinition internImage = internImageDefinition();
        when(modelDefinitionMapper.selectById(2L)).thenReturn(internImage);
        when(pythonModelDefinitionClient.check(any(), eq("INTERNIMAGE_RUNTIME_V1")))
                .thenReturn(availableResponse());

        AvailableModelDefinitionVO result = service.requireAvailableDefinition(2L);

        assertEquals(2L, result.getId());
        assertEquals("INTERNIMAGE_T_UPERNET_WHEAT_V1", result.getCode());
        assertTrue(result.getWorkflowSelectable());
    }

    @Test
    void shouldExcludeEnabledDefinitionWhenPythonSaysUnavailable() {
        when(modelDefinitionMapper.selectList(any())).thenReturn(List.of(definition()));
        PythonModelDefinitionCheckResponse response = availableResponse();
        response.setAvailable(false);
        response.setAvailabilityState("UNAVAILABLE");
        response.setReasonCode("CUDA_NOT_AVAILABLE");
        when(pythonModelDefinitionClient.check(any(), any())).thenReturn(response);

        assertTrue(service.listAvailableDefinitions().isEmpty());
    }

    @Test
    void shouldFailClosedWhenPythonAvailabilityCallFails() {
        when(modelDefinitionMapper.selectList(any())).thenReturn(List.of(definition()));
        when(pythonModelDefinitionClient.check(any(), any()))
                .thenThrow(new BusinessException("RUNTIME_CHECK_FAILED", "runtime unavailable"));

        assertTrue(service.listAvailableDefinitions().isEmpty());
    }

    @Test
    void shouldNotQueryRegistryWhenFeatureFlagIsDisabled() {
        properties.setEnabled(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                service::listAvailableDefinitions
        );

        assertEquals("MODEL_DEFINITION_REGISTRY_DISABLED", exception.getCode());
    }

    @Test
    void shouldResolveAvailableDefinitionAgainAtCreateTime() {
        when(modelDefinitionMapper.selectById(1L)).thenReturn(definition());
        when(pythonModelDefinitionClient.check(any(), eq("YOLO_RUNTIME_V1")))
                .thenReturn(availableResponse());

        AvailableModelDefinitionVO result = service.requireAvailableDefinition(1L);

        assertEquals("YOLOv8", result.getVersion());
        assertEquals("YOLOv8n 羊群检测", result.getDisplayName());
    }

    @Test
    void shouldRejectMissingDefinitionAtCreateTime() {
        when(modelDefinitionMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireAvailableDefinition(99L)
        );

        assertEquals("MODEL_DEFINITION_NOT_FOUND", exception.getCode());
    }

    @Test
    void shouldRejectDisabledDefinitionAtCreateTime() {
        ModelDefinition definition = definition();
        definition.setStatus("DISABLED");
        when(modelDefinitionMapper.selectById(1L)).thenReturn(definition);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireAvailableDefinition(1L)
        );

        assertEquals("MODEL_DEFINITION_DISABLED", exception.getCode());
    }

    @Test
    void shouldRejectUnavailableDefinitionAtCreateTime() {
        when(modelDefinitionMapper.selectById(1L)).thenReturn(definition());
        PythonModelDefinitionCheckResponse response = availableResponse();
        response.setAvailable(false);
        response.setReasonCode("CUDA_NOT_AVAILABLE");
        when(pythonModelDefinitionClient.check(any(), any())).thenReturn(response);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireAvailableDefinition(1L)
        );

        assertEquals("MODEL_DEFINITION_NOT_AVAILABLE", exception.getCode());
    }

    @Test
    void shouldTranslateRuntimeFailureAtCreateTime() {
        when(modelDefinitionMapper.selectById(1L)).thenReturn(definition());
        when(pythonModelDefinitionClient.check(any(), any()))
                .thenThrow(new BusinessException("RUNTIME_CHECK_FAILED", "runtime unavailable"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireAvailableDefinition(1L)
        );

        assertEquals("MODEL_RUNTIME_CHECK_FAILED", exception.getCode());
        assertFalse(exception.getMessage().contains("runtime unavailable"));
    }

    private ModelDefinition definition() {
        ModelDefinition definition = new ModelDefinition();
        String displayName = "YOLOv8n \u7f8a\u7fa4\u68c0\u6d4b";
        definition.setId(1L);
        definition.setCode("YOLOV8N_SHEEP_V1");
        definition.setDisplayName(displayName);
        definition.setModelFamily("YOLO");
        definition.setModelVersion("YOLOv8");
        definition.setVariant("yolov8n");
        definition.setTaskType("DETECTION");
        definition.setFramework("Ultralytics");
        definition.setFrameworkVersion("8.4.41");
        definition.setDefinitionType("ULTRALYTICS_DETECTION_MODEL");
        definition.setDefinitionVersion("1.0");
        definition.setArchitectureSignature("a".repeat(64));
        definition.setDefinitionSha256("b".repeat(64));
        definition.setRuntimeProfileId("YOLO_RUNTIME_V1");
        definition.setClassCount(1);
        definition.setClassOrderJson("[\"sheep\"]");
        definition.setStatus("ENABLED");
        definition.setDefinitionJson("""
                {
                  "definitionId":"YOLOV8N_SHEEP_V1",
                  "code":"YOLOV8N_SHEEP_V1",
                  "displayName":"%s",
                  "modelFamily":"YOLO",
                  "version":"YOLOv8",
                  "variant":"yolov8n",
                  "taskType":"DETECTION",
                  "framework":"Ultralytics",
                  "frameworkVersion":"8.4.41",
                  "architectureSignature":"%s",
                  "definitionSha256":"%s",
                  "runtimeProfileId":"YOLO_RUNTIME_V1",
                  "classCount":1,
                  "classOrder":["sheep"]
                }
                """.formatted(
                displayName,
                definition.getArchitectureSignature(),
                definition.getDefinitionSha256()
        ));
        return definition;
    }

    private ModelDefinition internImageDefinition() {
        ModelDefinition definition = new ModelDefinition();
        String displayName = "InternImage-T \u5c0f\u9ea6\u5012\u4f0f\u5206\u5272";
        definition.setId(2L);
        definition.setCode("INTERNIMAGE_T_UPERNET_WHEAT_V1");
        definition.setDisplayName(displayName);
        definition.setModelFamily("INTERNIMAGE");
        definition.setModelVersion("master@31c962dc");
        definition.setVariant("InternImage-T + UPerNet");
        definition.setTaskType("SEMANTIC_SEGMENTATION");
        definition.setFramework("MMSegmentation");
        definition.setFrameworkVersion("0.27.0");
        definition.setDefinitionType("MMSEG_TRUSTED_CONFIG_TEMPLATE");
        definition.setDefinitionVersion("1.0");
        definition.setArchitectureSignature("c".repeat(64));
        definition.setDefinitionSha256("d".repeat(64));
        definition.setRuntimeProfileId("INTERNIMAGE_RUNTIME_V1");
        definition.setClassCount(3);
        definition.setClassOrderJson("[\"background\",\"healthy_wheat\",\"lodged_wheat\"]");
        definition.setIgnoreIndex(255);
        definition.setStatus("ENABLED");
        definition.setDefinitionJson("""
                {
                  "definitionId":"INTERNIMAGE_T_UPERNET_WHEAT_V1",
                  "code":"INTERNIMAGE_T_UPERNET_WHEAT_V1",
                  "displayName":"%s",
                  "modelFamily":"INTERNIMAGE",
                  "version":"master@31c962dc",
                  "variant":"InternImage-T + UPerNet",
                  "taskType":"SEMANTIC_SEGMENTATION",
                  "framework":"MMSegmentation",
                  "frameworkVersion":"0.27.0",
                  "architectureSignature":"%s",
                  "definitionSha256":"%s",
                  "runtimeProfileId":"INTERNIMAGE_RUNTIME_V1",
                  "classCount":3,
                  "classOrder":["background","healthy_wheat","lodged_wheat"]
                }
                """.formatted(displayName, definition.getArchitectureSignature(), definition.getDefinitionSha256()));
        return definition;
    }

    private PythonModelDefinitionCheckResponse availableResponse() {
        PythonModelDefinitionCheckResponse response = new PythonModelDefinitionCheckResponse();
        response.setAvailable(true);
        response.setAvailabilityState("AVAILABLE");
        response.setReasonCode("AVAILABLE");
        response.setMessage("all checks passed");
        response.setDefinitionValid(true);
        response.setAdapterAvailable(true);
        response.setRuntimeEvaluated(true);
        response.setRuntimeAvailable(true);
        response.setSelfCheckPassed(true);
        response.setProbeContext("CONTAINER_RUNTIME");
        return response;
    }
}
