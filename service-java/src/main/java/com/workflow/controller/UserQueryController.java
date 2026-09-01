package com.workflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.common.ApiResponse;
import com.workflow.config.WorkflowUserSelectionProperties;
import com.workflow.dto.workflow.ServerUserOptionVO;
import com.workflow.entity.SysUser;
import com.workflow.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserQueryController {

    private final SysUserMapper sysUserMapper;
    private final WorkflowUserSelectionProperties workflowUserSelectionProperties;

    @GetMapping("/servers")
    public ApiResponse<List<ServerUserOptionVO>> listServerUsers() {
        List<SysUser> rawServerUsers = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRoleCode, "SERVER")
                        .eq(SysUser::getStatus, "ACTIVE")
                        .eq(SysUser::getIsDeleted, 0)
                        .orderByAsc(SysUser::getId)
        );

        List<String> excludedUsernames = new ArrayList<>();
        List<ServerUserOptionVO> result = new ArrayList<>();
        for (SysUser item : rawServerUsers) {
            if (!workflowUserSelectionProperties.isWorkflowAssignableServerUser(item)) {
                excludedUsernames.add(item.getUsername());
                continue;
            }
            result.add(new ServerUserOptionVO(
                    item.getId(),
                    item.getUsername(),
                    item.getDisplayName(),
                    buildServerUserLabel(item)
            ));
        }

        if (!excludedUsernames.isEmpty()) {
            log.info(
                    "Filtered workflow server assignment candidates: excludedCount={}, excludedUsernames={}, excludedEmailDomains={}",
                    excludedUsernames.size(),
                    excludedUsernames,
                    workflowUserSelectionProperties.resolvedExcludedServerEmailDomains()
            );
        }

        return ApiResponse.success(result);
    }

    private String buildServerUserLabel(SysUser user) {
        String username = user.getUsername();
        String displayName = user.getDisplayName();
        if (!StringUtils.hasText(displayName) || username.equals(displayName)) {
            return username;
        }
        return displayName + " (" + username + ")";
    }
}
