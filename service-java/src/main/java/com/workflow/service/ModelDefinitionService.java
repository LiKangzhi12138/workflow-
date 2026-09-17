package com.workflow.service;

import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.entity.ModelDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;
import java.util.List;

public interface ModelDefinitionService {

    List<AvailableModelDefinitionVO> listAvailableDefinitions();

    AvailableModelDefinitionVO requireAvailableDefinition(Long definitionId);

    Map<Long, ModelDefinition> findDefinitionsByIds(Set<Long> definitionIds);

    boolean isRegistryEnabled();

    ModelDefinition requireEnabledDefinition(Long definitionId);

    JsonNode readTrustedDefinitionJson(ModelDefinition definition);
}
