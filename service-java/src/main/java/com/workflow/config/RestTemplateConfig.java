package com.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@Slf4j
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     PythonIntegrationProperties pythonIntegrationProperties) {
        return builder
                .setConnectTimeout(Duration.ofMillis(Math.max(1000, pythonIntegrationProperties.getConnectTimeoutMs())))
                .setReadTimeout(Duration.ofMillis(Math.max(1000, pythonIntegrationProperties.getReadTimeoutMs())))
                .build();
    }

    @Bean("pythonJsonRestTemplate")
    public RestTemplate pythonJsonRestTemplate(PythonIntegrationProperties pythonIntegrationProperties,
                                               ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, pythonIntegrationProperties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Math.max(1000, pythonIntegrationProperties.getReadTimeoutMs()));

        RestTemplate restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(requestFactory));
        restTemplate.setMessageConverters(buildPythonJsonConverters(objectMapper));
        restTemplate.setInterceptors(List.of(new PythonJsonLoggingInterceptor()));
        return restTemplate;
    }

    private List<HttpMessageConverter<?>> buildPythonJsonConverters(ObjectMapper objectMapper) {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(objectMapper.copy());
        jacksonConverter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("application", "*+json")
        ));
        converters.add(jacksonConverter);
        converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return converters;
    }

    private static final class PythonJsonLoggingInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(org.springframework.http.HttpRequest request,
                                            byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String requestBody = body == null ? null : new String(body, StandardCharsets.UTF_8);
            log.info(
                    "pythonJsonRestTemplate outbound request: method={}, uri={}, headers={}, bodyBytes={}, body={}",
                    request.getMethod(),
                    request.getURI(),
                    request.getHeaders(),
                    body == null ? 0 : body.length,
                    requestBody
            );

            ClientHttpResponse response = execution.execute(request, body);
            String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            log.info(
                    "pythonJsonRestTemplate inbound response: method={}, uri={}, statusCode={}, headers={}, body={}",
                    request.getMethod(),
                    request.getURI(),
                    response.getStatusCode(),
                    response.getHeaders(),
                    responseBody
            );
            return response;
        }
    }
}
