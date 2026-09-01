package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.dto.auth.LoginRequest;
import com.workflow.dto.auth.RegisterRequest;
import com.workflow.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<?> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Auth register endpoint hit: username={}, email={}, roleCode={}", request.getUsername(), request.getEmail(), request.getRoleCode());
        return ApiResponse.success("注册成功", authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@Valid @RequestBody LoginRequest request,
                                HttpServletRequest httpServletRequest) {
        log.info("Auth login endpoint hit: username={}, remoteAddr={}", request.getUsername(), httpServletRequest.getRemoteAddr());
        return ApiResponse.success("登录成功", authService.login(request, httpServletRequest));
    }
}
