package com.workflow.support;

import org.springframework.util.StringUtils;

import java.util.Locale;

public final class WorkflowErrorSummarySupport {

    private static final int WORKFLOW_ERROR_MAX_LENGTH = 180;
    private static final int UPLOAD_ERROR_MAX_LENGTH = 240;

    private WorkflowErrorSummarySupport() {
    }

    public static String summarizeFederatedAggregation(Throwable throwable) {
        return summarizeFederatedAggregation(extractMeaningfulMessage(throwable));
    }

    public static String summarizeFederatedAggregation(String rawMessage) {
        String normalized = normalizeMessage(rawMessage);
        if (!StringUtils.hasText(normalized)) {
            return "联邦学习聚合失败：请查看服务端日志";
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "\"loc\":[\"body\"]", "field required", "unprocessable_entity", "unprocessable entity")) {
            return "联邦学习聚合失败：Python 接口未正确接收到请求体";
        }
        if (containsAny(lower, "weights only load failed", "weights_only", "detectionmodel", "ultralytics.nn.tasks")) {
            return "联邦学习聚合失败：YOLO 模型加载失败，请查看服务端日志";
        }
        if (containsAny(lower, "missing key", "unexpected key", "size mismatch", "state_dict")
                || containsAny(normalized, "模型结构不一致", "参数键集合无法对齐", "张量形状不一致")) {
            return "联邦学习聚合失败：模型结构不一致";
        }
        if (containsAny(lower, "invalid checkpoint", "checkpoint format")
                || containsAny(normalized, "checkpoint 不合法", "无法提取参数", "无法识别模型文件格式")) {
            return "联邦学习聚合失败：模型 checkpoint 不合法";
        }
        if (containsAny(lower, "global model", "output model", "torch.save")
                || containsAny(normalized, "全局模型保存失败", "未返回全局模型路径", "全局模型文件不存在")) {
            return "联邦学习聚合失败：全局模型保存失败";
        }
        if (containsAny(lower, "connection refused", "connection reset", "timed out", "i/o error")) {
            return "联邦学习聚合失败：Python 聚合服务调用失败，请查看服务端日志";
        }
        if (isTechnicalMessage(normalized) || looksLikeMojibake(normalized)) {
            return "联邦学习聚合失败：服务端处理异常，请查看服务端日志";
        }
        if (normalized.startsWith("联邦学习聚合失败") || normalized.startsWith("联邦聚合失败")) {
            return truncateWorkflowError(normalized);
        }
        return truncateWorkflowError("联邦学习聚合失败：" + normalized);
    }

    public static String summarizeForWorkflow(Throwable throwable, String fallbackSummary) {
        return summarizeForWorkflow(extractMeaningfulMessage(throwable), fallbackSummary);
    }

    public static String summarizeForWorkflow(String rawMessage, String fallbackSummary) {
        String normalized = normalizeMessage(rawMessage);
        if (!StringUtils.hasText(normalized)) {
            return truncateWorkflowError(fallbackSummary);
        }
        if (isTechnicalMessage(normalized) || looksLikeMojibake(normalized)) {
            return truncateWorkflowError(fallbackSummary);
        }
        return truncateWorkflowError(normalized);
    }

    public static String summarizeForUpload(Throwable throwable, String fallbackSummary) {
        return summarizeForUpload(extractMeaningfulMessage(throwable), fallbackSummary);
    }

    public static String summarizeForUpload(String rawMessage, String fallbackSummary) {
        String normalized = normalizeMessage(rawMessage);
        if (!StringUtils.hasText(normalized)) {
            return truncateUploadError(fallbackSummary);
        }
        if (isTechnicalMessage(normalized) || looksLikeMojibake(normalized)) {
            return truncateUploadError(fallbackSummary);
        }
        return truncateUploadError(normalized);
    }

    public static String summarizeUploadFailureForWorkflow(Long uploadId, String rawMessage) {
        String summary = summarizeForUpload(rawMessage, "模型上传处理失败，请查看服务端日志");
        return truncateWorkflowError("uploadId=" + safe(uploadId) + "：" + summary);
    }

    public static String truncateWorkflowError(String message) {
        return truncate(normalizeMessage(message), WORKFLOW_ERROR_MAX_LENGTH);
    }

    public static String truncateUploadError(String message) {
        return truncate(normalizeMessage(message), UPLOAD_ERROR_MAX_LENGTH);
    }

    public static String extractMeaningfulMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        String best = normalizeMessage(current.getMessage());
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            String causeMessage = normalizeMessage(current.getMessage());
            if (StringUtils.hasText(causeMessage)) {
                best = causeMessage;
            }
        }
        return best;
    }

    private static boolean isTechnicalMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "traceback", "org.springframework", "java.sql", "jdbc", "sqlstate",
                "data too long", "badsqlgrammar", "http 500", "<!doctype", "<html")
                || message.length() > 220;
    }

    private static boolean looksLikeMojibake(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return containsAny(message, "é—", "é", "å©", "ç¼", "é–»", "å“„");
    }

    private static boolean containsAny(String source, String... candidates) {
        if (source == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncate(String message, int maxLength) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
