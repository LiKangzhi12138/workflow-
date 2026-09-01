package com.workflow.config;

import com.workflow.security.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.channel.ChannelProcessingFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final UserDetailsService workflowUserDetailsService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CorsFilter corsFilter;

    @Bean
    public DaoAuthenticationProvider workflowAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(workflowUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setHideUserNotFoundExceptions(false);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider workflowAuthenticationProvider) {
        return new ProviderManager(workflowAuthenticationProvider);
    }

    @Bean
    public AuthenticationEntryPoint workflowAuthenticationEntryPoint() {
        return (request, response, ex) -> {
            log.warn(
                    "Spring Security authentication rejected request: method={}, path={}, reason={}, authenticated={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    ex.getClass().getSimpleName(),
                    request.getUserPrincipal() != null
            );
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"请先登录后再访问\"}");
        };
    }

    @Bean
    public AccessDeniedHandler workflowAccessDeniedHandler() {
        return (request, response, ex) -> {
            log.warn(
                    "Spring Security denied request: method={}, path={}, reason={}, principal={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    ex.getClass().getSimpleName(),
                    request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName()
            );
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"当前账号无权访问该资源\"}");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationEntryPoint workflowAuthenticationEntryPoint,
                                                   AccessDeniedHandler workflowAccessDeniedHandler,
                                                   DaoAuthenticationProvider workflowAuthenticationProvider) throws Exception {
        List<String> permitAllRoutes = List.of(
                "/",
                "/index.html",
                "/favicon.ico",
                "/assets/**",
                "/healthz",
                "/api/auth/login",
                "/api/auth/register",
                "/api/health/**",
                "/api/internal/**",
                "/error"
        );

        SecurityFilterChain chain = http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authenticationProvider(workflowAuthenticationProvider)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(permitAllRoutes.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(workflowAuthenticationEntryPoint)
                        .accessDeniedHandler(workflowAccessDeniedHandler)
                )
                .addFilterBefore(corsFilter, ChannelProcessingFilter.class)
                .addFilterBefore(sessionAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .build();

        log.info(
                "SecurityFilterChain initialized: permitAllRoutes={}, csrfDisabled=true, corsManagedBy={}, formLoginDisabled=true, httpBasicDisabled=true, logoutDisabled=true, sessionPolicy={}, authenticationProvider={}, sessionFilter={}",
                permitAllRoutes,
                "corsFilter(" + corsFilter.getClass().getSimpleName() + ")",
                SessionCreationPolicy.IF_REQUIRED,
                "workflowAuthenticationProvider(sys_user)",
                SessionAuthenticationFilter.class.getSimpleName()
        );
        return chain;
    }
}
