package com.workflow.service;

import com.workflow.dto.auth.LoginRequest;
import com.workflow.dto.auth.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

public interface AuthService {

    Map<String, Object> register(RegisterRequest request);

    Map<String, Object> login(LoginRequest request, HttpServletRequest httpServletRequest);
}