package com.workflow.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workflow.entity.SysUser;
import com.workflow.mapper.SysUserMapper;
import com.workflow.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class LoginUserContext {

    private final SysUserMapper sysUserMapper;

    public SysUser getCurrentUser() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            throw new RuntimeException("未登录或登录态已失效");
        }

        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request.getSession(false);

        if (session == null) {
            throw new RuntimeException("未登录或登录态已失效");
        }

        Object userIdObj = session.getAttribute(AuthServiceImpl.SESSION_USER_ID);
        if (userIdObj == null) {
            throw new RuntimeException("未登录或登录态已失效");
        }

        Long userId;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else {
            userId = Long.valueOf(String.valueOf(userIdObj));
        }

        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getIsDeleted, 0)
                        .last("limit 1")
        );

        if (user == null) {
            throw new RuntimeException("当前登录用户不存在");
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new RuntimeException("当前登录用户状态不可用");
        }

        return user;
    }
}