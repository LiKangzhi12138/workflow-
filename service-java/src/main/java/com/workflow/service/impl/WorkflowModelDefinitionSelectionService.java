package com.workflow.service.impl;

import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.dto.workflow.CreateWorkflowRequest;
import com.workflow.entity.ModelDefinition;
import com.workflow.exception.BusinessException;
import com.workflow.service.ModelDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowModelDefinitionSelectionService {

    private static final Set<String> LEGACY_YOLO_VERSIONS = Set.of("YOLOv8", "YOLOv10", "YOLOv11");

    private final ModelDefinitionService modelDefinitionService;

    public WorkflowModelSelection resolve(CreateWorkflowRequest request) {
        if (modelDefinitionService.isRegistryEnabled()) {
            AvailableModelDefinitionVO definition =
                    modelDefinitionService.requireAvailableDefinition(request.getModelDefinitionId());
            return new WorkflowModelSelection(definition.getId(), definition.getVersion(), definition.getDisplayName());
        }

        if (request.getModelDefinitionId() != null) {
            throw new BusinessException(
                    "MODEL_DEFINITION_REGISTRY_DISABLED",
                    "模型定义注册表功能尚未启用，请使用旧版模型选择方式。"
            );
        }
        if (!StringUtils.hasText(request.getYoloVersion())
                || !LEGACY_YOLO_VERSIONS.contains(request.getYoloVersion())) {
            throw new BusinessException(
                    "LEGACY_MODEL_VERSION_INVALID",
                    "不支持的 YOLO 版本，请选择 YOLOv8、YOLOv10 或 YOLOv11。"
            );
        }
        return new WorkflowModelSelection(null, request.getYoloVersion(), request.getYoloVersion());
    }

    public Map<Long, ModelDefinition> findDefinitionsByIds(Set<Long> definitionIds) {
        return modelDefinitionService.findDefinitionsByIds(definitionIds);
    }

    public record WorkflowModelSelection(
            Long modelDefinitionId,
            String yoloVersion,
            String displayName
    ) {
    }
}
