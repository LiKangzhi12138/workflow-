package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.ModelDefinitionRegistryProperties;
import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.dto.modeldefinition.ModelDefinitionAvailabilityVO;
import com.workflow.dto.python.PythonModelDefinitionCheckResponse;
import com.workflow.entity.ModelDefinition;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.ModelDefinitionMapper;
import com.workflow.service.ModelDefinitionService;
import com.workflow.service.python.PythonModelDefinitionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelDefinitionServiceImpl implements ModelDefinitionService {

    private static final String STATUS_ENABLED = "ENABLED";

    private final ModelDefinitionMapper modelDefinitionMapper;
    private final PythonModelDefinitionClient pythonModelDefinitionClient;
    private final ModelDefinitionRegistryProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public List<AvailableModelDefinitionVO> listAvailableDefinitions() {
        requireRegistryEnabled();

        List<ModelDefinition> definitions = modelDefinitionMapper.selectList(
                new LambdaQueryWrapper<ModelDefinition>()
                        .eq(ModelDefinition::getStatus, STATUS_ENABLED)
                        .orderByAsc(ModelDefinition::getId)
        );
        return definitions.stream()
                .map(this::evaluateDefinition)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public AvailableModelDefinitionVO requireAvailableDefinition(Long definitionId) {
        requireRegistryEnabled();
        if (definitionId == null) {
            throw new BusinessException("MODEL_DEFINITION_NOT_FOUND", "请选择服务器当前可用的模型定义。");
        }

        ModelDefinition definition = modelDefinitionMapper.selectById(definitionId);
        if (definition == null) {
            throw new BusinessException("MODEL_DEFINITION_NOT_FOUND", "所选模型定义不存在。");
        }
        if (!STATUS_ENABLED.equalsIgnoreCase(definition.getStatus())) {
            throw new BusinessException("MODEL_DEFINITION_DISABLED", "所选模型定义已停用。");
        }

        try {
            JsonNode definitionJson = parseAndValidateDefinitionJson(definition);
            PythonModelDefinitionCheckResponse availability = pythonModelDefinitionClient.check(
                    definitionJson,
                    definition.getRuntimeProfileId()
            );
            if (availability == null || !Boolean.TRUE.equals(availability.getAvailable())) {
                log.warn(
                        "ModelDefinition rejected at workflow create time: code={}, reasonCode={}",
                        definition.getCode(),
                        availability == null ? "RUNTIME_CHECK_FAILED" : availability.getReasonCode()
                );
                throw new BusinessException(
                        "MODEL_DEFINITION_NOT_AVAILABLE",
                        "所选模型当前不可用，请刷新模型能力后重试。"
                );
            }
            if (!properties.isWorkflowSelectable(definition.getRuntimeProfileId())) {
                throw new BusinessException(
                        "MODEL_DEFINITION_WORKFLOW_NOT_SELECTABLE",
                        "该模型当前仅完成运行环境自检，工作流能力尚未开放。"
                );
            }
            return toAvailableVO(definition, availability);
        } catch (BusinessException ex) {
            if ("RUNTIME_CHECK_FAILED".equals(ex.getCode())) {
                throw new BusinessException(
                        "MODEL_RUNTIME_CHECK_FAILED",
                        "模型运行环境检查失败，请稍后重试。",
                        ex
                );
            }
            throw ex;
        } catch (Exception ex) {
            log.warn("ModelDefinition create-time validation failed: code={}", definition.getCode(), ex);
            throw new BusinessException(
                    "MODEL_DEFINITION_NOT_AVAILABLE",
                    "所选模型定义校验失败，暂时无法创建工作流。",
                    ex
            );
        }
    }

    @Override
    public Map<Long, ModelDefinition> findDefinitionsByIds(Set<Long> definitionIds) {
        if (definitionIds == null || definitionIds.isEmpty()) {
            return Map.of();
        }
        return modelDefinitionMapper.selectBatchIds(definitionIds).stream()
                .collect(Collectors.toMap(ModelDefinition::getId, Function.identity()));
    }

    @Override
    public boolean isRegistryEnabled() {
        return properties.isEnabled();
    }

    @Override
    public ModelDefinition requireEnabledDefinition(Long definitionId) {
        if (definitionId == null) {
            throw new BusinessException("WORKFLOW_MODEL_DEFINITION_REQUIRED", "当前工作流未绑定模型定义。");
        }
        ModelDefinition definition = modelDefinitionMapper.selectById(definitionId);
        if (definition == null) {
            throw new BusinessException("MODEL_DEFINITION_NOT_FOUND", "当前工作流绑定的模型定义不存在。");
        }
        if (!STATUS_ENABLED.equalsIgnoreCase(definition.getStatus())) {
            throw new BusinessException("MODEL_DEFINITION_DISABLED", "当前工作流绑定的模型定义已停用。");
        }
        return definition;
    }

    @Override
    public JsonNode readTrustedDefinitionJson(ModelDefinition definition) {
        try {
            return parseAndValidateDefinitionJson(definition);
        } catch (Exception ex) {
            throw new BusinessException(
                    "WEIGHTS_DEFINITION_MISMATCH",
                    "服务器模型定义完整性校验失败。",
                    ex
            );
        }
    }

    private AvailableModelDefinitionVO evaluateDefinition(ModelDefinition definition) {
        try {
            JsonNode definitionJson = parseAndValidateDefinitionJson(definition);
            PythonModelDefinitionCheckResponse availability = pythonModelDefinitionClient.check(
                    definitionJson,
                    definition.getRuntimeProfileId()
            );
            if (availability == null || !Boolean.TRUE.equals(availability.getAvailable())) {
                log.warn(
                        "ModelDefinition excluded from available list: code={}, reasonCode={}, message={}",
                        definition.getCode(),
                        availability == null ? "RUNTIME_CHECK_FAILED" : availability.getReasonCode(),
                        availability == null ? "empty Python response" : availability.getMessage()
                );
                return null;
            }
            return toAvailableVO(definition, availability);
        } catch (Exception ex) {
            log.warn(
                    "ModelDefinition availability evaluation failed closed: code={}, reasonCode=RUNTIME_CHECK_FAILED",
                    definition.getCode(),
                    ex
            );
            return null;
        }
    }

    private void requireRegistryEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(
                    "MODEL_DEFINITION_REGISTRY_DISABLED",
                    "\u6a21\u578b\u5b9a\u4e49\u6ce8\u518c\u8868\u529f\u80fd\u5c1a\u672a\u542f\u7528\u3002"
            );
        }
    }

    private JsonNode parseAndValidateDefinitionJson(ModelDefinition definition) throws Exception {
        if (!StringUtils.hasText(definition.getDefinitionJson())) {
            throw new IllegalArgumentException("definition_json is empty");
        }
        JsonNode root = objectMapper.readTree(definition.getDefinitionJson());
        requireEqual("code", definition.getCode(), root.path("code").asText(null));
        requireEqual("displayName", definition.getDisplayName(), root.path("displayName").asText(null));
        requireEqual("modelFamily", definition.getModelFamily(), root.path("modelFamily").asText(null));
        requireEqual("version", definition.getModelVersion(), root.path("version").asText(null));
        requireEqual("variant", definition.getVariant(), root.path("variant").asText(null));
        requireEqual("taskType", definition.getTaskType(), root.path("taskType").asText(null));
        requireEqual("framework", definition.getFramework(), root.path("framework").asText(null));
        requireEqual("frameworkVersion", definition.getFrameworkVersion(), root.path("frameworkVersion").asText(null));
        requireEqual(
                "architectureSignature",
                definition.getArchitectureSignature(),
                root.path("architectureSignature").asText(null)
        );
        requireEqual("definitionSha256", definition.getDefinitionSha256(), root.path("definitionSha256").asText(null));
        requireEqual(
                "runtimeProfileId",
                definition.getRuntimeProfileId(),
                root.path("runtimeProfileId").asText(null)
        );
        if (definition.getClassCount() == null || root.path("classCount").asInt(-1) != definition.getClassCount()) {
            throw new IllegalArgumentException("definition_json classCount does not match registry columns");
        }
        List<String> registeredClasses = objectMapper.readValue(
                definition.getClassOrderJson(),
                new TypeReference<List<String>>() {
                }
        );
        List<String> definitionClasses = objectMapper.convertValue(
                root.path("classOrder"),
                new TypeReference<List<String>>() {
                }
        );
        if (!registeredClasses.equals(definitionClasses)) {
            throw new IllegalArgumentException("definition_json classOrder does not match registry columns");
        }
        return root;
    }

    private AvailableModelDefinitionVO toAvailableVO(
            ModelDefinition definition,
            PythonModelDefinitionCheckResponse availability
    ) throws Exception {
        List<String> classes = objectMapper.readValue(
                definition.getClassOrderJson(),
                new TypeReference<List<String>>() {
                }
        );
        return AvailableModelDefinitionVO.builder()
                .id(definition.getId())
                .code(definition.getCode())
                .displayName(definition.getDisplayName())
                .modelFamily(definition.getModelFamily())
                .version(definition.getModelVersion())
                .variant(definition.getVariant())
                .taskType(definition.getTaskType())
                .framework(definition.getFramework())
                .frameworkVersion(definition.getFrameworkVersion())
                .classes(classes)
                .runtimeProfileId(definition.getRuntimeProfileId())
                .workflowSelectable(properties.isWorkflowSelectable(definition.getRuntimeProfileId()))
                .availability(ModelDefinitionAvailabilityVO.builder()
                        .available(availability.getAvailable())
                        .state(availability.getAvailabilityState())
                        .reasonCode(availability.getReasonCode())
                        .message(availability.getMessage())
                        .definitionValid(availability.getDefinitionValid())
                        .adapterAvailable(availability.getAdapterAvailable())
                        .runtimeEvaluated(availability.getRuntimeEvaluated())
                        .runtimeAvailable(availability.getRuntimeAvailable())
                        .selfCheckPassed(availability.getSelfCheckPassed())
                        .probeContext(availability.getProbeContext())
                        .build())
                .build();
    }

    private void requireEqual(String field, String expected, String actual) {
        if (!StringUtils.hasText(expected) || !Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("definition_json " + field + " does not match registry columns");
        }
    }
}
