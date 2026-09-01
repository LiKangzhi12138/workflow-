package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.config.WorkflowStorageProperties;
import com.workflow.dto.system.RuntimeProfileVO;
import com.workflow.entity.SysUser;
import com.workflow.security.LoginUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemRuntimeController {

    private final WorkflowStorageProperties workflowStorageProperties;
    private final LoginUserContext loginUserContext;

    @GetMapping("/runtime")
    public ApiResponse<RuntimeProfileVO> getRuntimeProfile() {
        SysUser currentUser = loginUserContext.getCurrentUser();
        boolean importAllowed = workflowStorageProperties.isServerPathImportAllowedForRole(currentUser.getRoleCode());
        return ApiResponse.success(
                RuntimeProfileVO.builder()
                        .runtimeMode(workflowStorageProperties.getRuntimeMode())
                        .serverPathImportEnabled(workflowStorageProperties.isServerPathImportEnabled())
                        .serverPathImportAllowed(importAllowed)
                        .serverPathImportRoots(
                                importAllowed
                                        ? workflowStorageProperties.resolvedServerImportRoots().stream().map(java.nio.file.Path::toString).toList()
                                        : java.util.List.of()
                        )
                        .build()
        );
    }
}
