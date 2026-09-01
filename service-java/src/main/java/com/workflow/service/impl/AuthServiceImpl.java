package com.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.dto.auth.LoginRequest;
import com.workflow.dto.auth.RegisterRequest;
import com.workflow.entity.SysUser;
import com.workflow.exception.BusinessException;
import com.workflow.mapper.SysUserMapper;
import com.workflow.security.WorkflowUserPrincipal;
import com.workflow.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    public static final String SESSION_USER_ID = "LOGIN_USER_ID";
    public static final String SESSION_USERNAME = "LOGIN_USERNAME";
    public static final String SESSION_ROLE_CODE = "LOGIN_ROLE_CODE";

    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           BCryptPasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Map<String, Object> register(RegisterRequest request) {
        String normalizedUsername = request.getUsername().trim();
        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String normalizedRoleCode = request.getRoleCode().trim().toUpperCase(Locale.ROOT);

        SysUser existByUsername = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, normalizedUsername)
                        .eq(SysUser::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (existByUsername != null) {
            log.warn("Register rejected: username already exists, username={}", normalizedUsername);
            throw new BusinessException("REGISTER_USERNAME_EXISTS", "用户名已存在");
        }

        SysUser existByEmail = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getEmail, normalizedEmail)
                        .eq(SysUser::getIsDeleted, 0)
                        .last("limit 1")
        );
        if (existByEmail != null) {
            log.warn("Register rejected: email already exists, email={}", normalizedEmail);
            throw new BusinessException("REGISTER_EMAIL_EXISTS", "邮箱已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName().trim());
        user.setEmail(normalizedEmail);
        user.setRoleCode(normalizedRoleCode);
        user.setStatus("ACTIVE");
        user.setIsDeleted(0);

        sysUserMapper.insert(user);
        log.info("Register succeeded: userId={}, username={}, roleCode={}", user.getId(), user.getUsername(), user.getRoleCode());

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("email", user.getEmail());
        result.put("roleCode", user.getRoleCode());
        result.put("status", user.getStatus());

        return result;
    }

    @Override
    public Map<String, Object> login(LoginRequest request, HttpServletRequest httpServletRequest) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.getUsername().trim(),
                            request.getPassword()
                    )
            );
        } catch (UsernameNotFoundException ex) {
            log.warn("Login failed: user not found, username={}", request.getUsername());
            throw new BusinessException("LOGIN_USER_NOT_FOUND", "用户不存在", ex);
        } catch (DisabledException ex) {
            log.warn("Login failed: user disabled, username={}", request.getUsername());
            throw new BusinessException("LOGIN_USER_DISABLED", "账号未启用", ex);
        } catch (BadCredentialsException ex) {
            log.warn("Login failed: password mismatch, username={}", request.getUsername());
            throw new BusinessException("LOGIN_PASSWORD_ERROR", "密码错误", ex);
        }

        WorkflowUserPrincipal principal = (WorkflowUserPrincipal) authentication.getPrincipal();
        log.info("Login authenticated by workflow security: userId={}, username={}, roleCode={}", principal.getId(), principal.getUsername(), principal.getRoleCode());

        HttpSession oldSession = httpServletRequest.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }

        HttpSession newSession = httpServletRequest.getSession(true);
        newSession.setMaxInactiveInterval(8 * 60 * 60);

        newSession.setAttribute(SESSION_USER_ID, principal.getId());
        newSession.setAttribute(SESSION_USERNAME, principal.getUsername());
        newSession.setAttribute(SESSION_ROLE_CODE, principal.getRoleCode());
        log.info("Login session established: sessionId={}, userId={}, username={}", newSession.getId(), principal.getId(), principal.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("id", principal.getId());
        result.put("username", principal.getUsername());
        result.put("displayName", principal.getDisplayName());
        result.put("email", principal.getEmail());
        result.put("roleCode", principal.getRoleCode());
        result.put("status", principal.isEnabled() ? "ACTIVE" : "DISABLED");
        result.put("sessionId", newSession.getId());

        return result;
    }
}
