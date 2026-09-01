package com.workflow.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;
import java.util.List;

@Slf4j
public class WorkflowLoggingCorsProcessor implements CorsProcessor {

    private final DefaultCorsProcessor delegate = new DefaultCorsProcessor();

    @Override
    public boolean processRequest(@Nullable CorsConfiguration config,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String accessControlRequestMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        boolean authRequest = isAuthRequest(requestUri);
        boolean corsRequest = CorsUtils.isCorsRequest(request);

        if (authRequest || corsRequest) {
            log.info(
                    "CORS filter entry: filter=corsFilter, processor={}, requestURI={}, method={}, origin={}, accessControlRequestMethod={}, isCorsRequest={}, isPreFlight={}, config={}",
                    getClass().getSimpleName(),
                    requestUri,
                    method,
                    origin,
                    accessControlRequestMethod,
                    corsRequest,
                    CorsUtils.isPreFlightRequest(request),
                    summarize(config)
            );
        }

        boolean accepted = delegate.processRequest(config, request, response);

        if (authRequest || corsRequest) {
            if (accepted) {
                log.info(
                        "CORS accepted: filter=corsFilter, requestURI={}, method={}, origin={}, responseAllowOrigin={}, config={}",
                        requestUri,
                        method,
                        origin,
                        response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                        summarize(config)
                );
            } else {
                log.warn(
                        "CORS rejected: filter=corsFilter, requestURI={}, method={}, origin={}, accessControlRequestMethod={}, reason=Invalid CORS request, config={}",
                        requestUri,
                        method,
                        origin,
                        accessControlRequestMethod,
                        summarize(config)
                );
            }
        }

        return accepted;
    }

    private boolean isAuthRequest(String requestUri) {
        return "/api/auth/login".equals(requestUri) || "/api/auth/register".equals(requestUri);
    }

    private String summarize(@Nullable CorsConfiguration config) {
        if (config == null) {
            return "null";
        }
        List<String> methods = config.getAllowedMethods();
        List<String> headers = config.getAllowedHeaders();
        List<String> origins = config.getAllowedOrigins();
        List<String> originPatterns = config.getAllowedOriginPatterns();
        return "CorsConfiguration{allowedOrigins=" + origins
                + ", allowedOriginPatterns=" + originPatterns
                + ", allowedMethods=" + methods
                + ", allowedHeaders=" + headers
                + ", allowCredentials=" + config.getAllowCredentials()
                + "}";
    }
}
