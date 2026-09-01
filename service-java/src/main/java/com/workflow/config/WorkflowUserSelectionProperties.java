package com.workflow.config;

import com.workflow.entity.SysUser;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.user-selection")
public class WorkflowUserSelectionProperties {

    /**
     * Reserved placeholder domains used by smoke/e2e/lan test users.
     * These accounts should not appear in the workflow server assignment dropdown.
     */
    private String excludedServerEmailDomains = "example.com";

    public boolean isWorkflowAssignableServerUser(SysUser user) {
        if (user == null) {
            return false;
        }
        if (!StringUtils.hasText(user.getUsername())) {
            return false;
        }
        if (!"SERVER".equalsIgnoreCase(trim(user.getRoleCode()))) {
            return false;
        }
        if (user.getIsDeleted() != null && user.getIsDeleted() != 0) {
            return false;
        }
        if (!"ACTIVE".equalsIgnoreCase(trim(user.getStatus()))) {
            return false;
        }
        return !matchesExcludedServerEmailDomain(user.getEmail());
    }

    public List<String> resolvedExcludedServerEmailDomains() {
        return Arrays.stream((excludedServerEmailDomains == null ? "" : excludedServerEmailDomains).split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private boolean matchesExcludedServerEmailDomain(String email) {
        if (!StringUtils.hasText(email)) {
            return false;
        }
        int atIndex = email.lastIndexOf('@');
        if (atIndex < 0 || atIndex == email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).trim().toLowerCase(Locale.ROOT);
        return resolvedExcludedServerEmailDomains().stream().anyMatch(domain::equals);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
