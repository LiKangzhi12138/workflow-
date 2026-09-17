package com.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "workflow.weights-fedavg-v1")
public class WeightsFedAvgProperties {

    private boolean enabled = false;
}
