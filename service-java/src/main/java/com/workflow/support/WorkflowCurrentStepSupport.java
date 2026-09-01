package com.workflow.support;

import com.workflow.entity.Workflow;
import com.workflow.enums.WorkflowStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public final class WorkflowCurrentStepSupport {

    public static final int DB_COMPATIBLE_MAX_LENGTH = 255;
    public static final int SAFE_DISPLAY_MAX_LENGTH = 64;

    public static final String SERVER_ACCEPTED = WorkflowStatusEnum.stepTextOf(WorkflowStatusEnum.ACCEPTED.getCode());
    public static final String MODEL_DOWNLOADING = "\u6B63\u5728\u4E0B\u8F7D\u6A21\u578B";
    public static final String AUTO_DECRYPTING = "\u6B63\u5728\u53CD\u6D17\u724C\u89E3\u5BC6";
    public static final String MODEL_REGISTERING = "\u6B63\u5728\u5BFC\u5165\u670D\u52A1\u7AEF\u6A21\u578B\u7BA1\u7406";
    public static final String SECURE_SHUFFLE_PROCESSING = "安全洗牌处理中";
    public static final String DP_PROCESSING = "差分隐私处理中";
    public static final String SECURE_AGGREGATION_PROCESSING = "安全聚合处理中";
    public static final String FEDERATED_AGGREGATING = "\u8054\u90A6\u5B66\u4E60\u805A\u5408\u4E2D";
    public static final String FEDERATED_COMPLETED = "\u8054\u90A6\u5B66\u4E60\u5B8C\u6210";
    public static final String FEDERATED_FAILED = "\u8054\u90A6\u5B66\u4E60\u5931\u8D25";
    public static final String READY_TO_VALIDATE = "\u89E3\u5BC6\u5B8C\u6210\uFF0C\u53EF\u7EE7\u7EED\u9A8C\u8BC1";
    public static final String AUTO_DECRYPT_FAILED = "\u53CD\u6D17\u724C\u89E3\u5BC6\u5931\u8D25";
    public static final String DEFAULT_STEP = "\u5904\u7406\u4E2D";

    private WorkflowCurrentStepSupport() {
    }

    public static String statusStepOf(String status) {
        return sanitizeCurrentStep(WorkflowStatusEnum.stepTextOf(status));
    }

    public static String sanitizeCurrentStep(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_STEP;
        }
        String normalized = value.trim();
        if (normalized.length() <= SAFE_DISPLAY_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SAFE_DISPLAY_MAX_LENGTH);
    }

    public static void applyCurrentStep(Workflow workflow, String currentStep, String scene) {
        if (workflow == null) {
            return;
        }
        String sanitized = sanitizeCurrentStep(currentStep);
        if (!sanitized.equals(currentStep)) {
            log.warn(
                    "Current step text exceeded safe limit and was truncated: workflowId={}, scene={}, originalLength={}, safeLength={}, dbLengthLimit={}, originalText={}",
                    workflow.getId(),
                    scene,
                    currentStep == null ? 0 : currentStep.length(),
                    SAFE_DISPLAY_MAX_LENGTH,
                    DB_COMPATIBLE_MAX_LENGTH,
                    currentStep
            );
        }
        workflow.setCurrentStep(sanitized);
    }
}
