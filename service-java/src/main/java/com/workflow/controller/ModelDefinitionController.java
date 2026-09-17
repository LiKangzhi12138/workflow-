package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.modeldefinition.AvailableModelDefinitionVO;
import com.workflow.service.ModelDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-definitions")
@RequiredArgsConstructor
public class ModelDefinitionController {

    private final ModelDefinitionService modelDefinitionService;

    @GetMapping("/available")
    public ApiResponse<List<AvailableModelDefinitionVO>> listAvailableDefinitions() {
        return ApiResponse.success(modelDefinitionService.listAvailableDefinitions());
    }

    @GetMapping("/registry-status")
    public ApiResponse<Map<String, Boolean>> getRegistryStatus() {
        return ApiResponse.success(Map.of("enabled", modelDefinitionService.isRegistryEnabled()));
    }
}
