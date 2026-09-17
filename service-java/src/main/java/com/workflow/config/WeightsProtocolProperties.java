package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.weights-protocol-v1")
public class WeightsProtocolProperties {

    private boolean enabled = false;
}
