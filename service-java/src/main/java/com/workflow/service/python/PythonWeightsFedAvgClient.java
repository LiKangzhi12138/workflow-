package com.workflow.service.python;

import com.workflow.config.PythonIntegrationProperties;
import com.workflow.dto.python.PythonWeightsFedAvgRequest;
import com.workflow.dto.python.PythonWeightsFedAvgResponse;
import com.workflow.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Component
public class PythonWeightsFedAvgClient {

    private final RestTemplate restTemplate;
    private final PythonIntegrationProperties properties;

    public PythonWeightsFedAvgClient(
            @Qualifier("pythonJsonRestTemplate") RestTemplate restTemplate,
            PythonIntegrationProperties properties
    ) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public PythonWeightsFedAvgResponse aggregate(PythonWeightsFedAvgRequest request) {
        try {
            PythonWeightsFedAvgResponse response = restTemplate.postForObject(
                    properties.getPythonBaseUrl() + "/internal/federated/aggregate-weights-v1",
                    request,
                    PythonWeightsFedAvgResponse.class
            );
            if (response == null || !Boolean.TRUE.equals(response.getPassed())
                    || !StringUtils.hasText(response.getOutputWeightsPath())) {
                String reason = response == null ? "WEIGHTS_FEDAVG_FAILED" : response.getReasonCode();
                throw new BusinessException(reason, "联邦权重聚合失败。");
            }
            return response;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("WEIGHTS_FEDAVG_FAILED", "联邦权重聚合服务调用失败。", ex);
        }
    }
}
