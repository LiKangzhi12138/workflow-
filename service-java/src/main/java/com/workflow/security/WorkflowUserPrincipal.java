package com.workflow.security;

import com.workflow.entity.SysUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class WorkflowUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final String email;
    private final String roleCode;
    private final boolean enabled;
    private final List<GrantedAuthority> authorities;

    private WorkflowUserPrincipal(Long id,
                                  String username,
                                  String passwordHash,
                                  String displayName,
                                  String email,
                                  String roleCode,
                                  boolean enabled,
                                  List<GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.email = email;
        this.roleCode = roleCode;
        this.enabled = enabled;
        this.authorities = authorities;
    }

    public static WorkflowUserPrincipal from(SysUser user) {
        String normalizedRoleCode = user.getRoleCode() == null
                ? ""
                : user.getRoleCode().trim().toUpperCase(Locale.ROOT);
        boolean active = "ACTIVE".equalsIgnoreCase(user.getStatus()) && Integer.valueOf(0).equals(user.getIsDeleted());
        List<GrantedAuthority> resolvedAuthorities = normalizedRoleCode.isBlank()
                ? List.of()
                : List.of(new SimpleGrantedAuthority(normalizedRoleCode));
        return new WorkflowUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                user.getEmail(),
                normalizedRoleCode,
                active,
                resolvedAuthorities
        );
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getRoleCode() {
        return roleCode;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
