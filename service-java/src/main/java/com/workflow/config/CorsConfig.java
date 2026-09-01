package com.workflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class CorsConfig {

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of(
            "Content-Type",
            "Authorization",
            "Accept",
            "Origin",
            "X-Requested-With"
    );

    private final WorkflowSecurityProperties workflowSecurityProperties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = workflowSecurityProperties.resolvedAllowedOrigins();
        List<String> allowedOriginPatterns = workflowSecurityProperties.resolvedAllowedOriginPatterns();

        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(List.of("Set-Cookie"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.info(
                "CORS configuration initialized: sourceBean=corsConfigurationSource, pathPattern=/**, allowedOrigins={}, allowedOriginPatterns={}, allowedMethods={}, allowedHeaders={}, allowCredentials={}, exposedHeaders={}",
                allowedOrigins,
                allowedOriginPatterns,
                ALLOWED_METHODS,
                ALLOWED_HEADERS,
                true,
                config.getExposedHeaders()
        );
        return source;
    }

    @Bean
    public CorsProcessor workflowLoggingCorsProcessor() {
        return new WorkflowLoggingCorsProcessor();
    }

    @Bean(name = "corsFilter")
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource,
                                 CorsProcessor workflowLoggingCorsProcessor) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource);
        filter.setCorsProcessor(workflowLoggingCorsProcessor);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter corsFilter) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(corsFilter);
        registration.setEnabled(false);
        return registration;
    }
}
