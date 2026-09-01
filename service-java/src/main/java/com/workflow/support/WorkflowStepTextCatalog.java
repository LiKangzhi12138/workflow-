package com.workflow.support;

import com.workflow.enums.WorkflowStatusEnum;

import java.nio.file.Path;

public final class WorkflowStepTextCatalog {

    private WorkflowStepTextCatalog() {
    }

    public static String initiateStepName() {
        return "\u5BA2\u6237\u7AEF\u53D1\u8D77\u5DE5\u4F5C\u6D41";
    }

    public static String initiateMessage(String modelName) {
        return "\u5BA2\u6237\u7AEF\u53D1\u8D77\u5DE5\u4F5C\u6D41\uFF0C\u5E76\u9009\u62E9\u6A21\u578B\uFF1A" + safe(modelName);
    }

    public static String withdrawStepName() {
        return "\u5BA2\u6237\u7AEF\u64A4\u56DE\u5DE5\u4F5C\u6D41";
    }

    public static String withdrawMessage() {
        return "\u5BA2\u6237\u7AEF\u4E3B\u52A8\u64A4\u56DE\u5DE5\u4F5C\u6D41\u3002";
    }

    public static String acceptStepName() {
        return "\u670D\u52A1\u7AEF\u63A5\u6536\u5DE5\u4F5C\u6D41";
    }

    public static String acceptMessage(int requiredModelCount,
                                       int actualUploadedCount,
                                       int pendingAutoManageCount) {
        return String.format(
                "\u670D\u52A1\u7AEF\u5DF2\u63A5\u6536\u5DE5\u4F5C\u6D41\uFF1A\u8981\u6C42\u6A21\u578B\u6570=%d\uFF0C\u5DF2\u4E0A\u4F20=%d\uFF0C\u5F85\u81EA\u52A8\u5904\u7406=%d\u3002",
                requiredModelCount,
                actualUploadedCount,
                pendingAutoManageCount
        );
    }

    public static String bindDatasetStepName() {
        return "\u7ED1\u5B9A\u9A8C\u8BC1\u6570\u636E\u96C6";
    }

    public static String bindDatasetMessage(String datasetName) {
        return "\u670D\u52A1\u7AEF\u5DF2\u7ED1\u5B9A\u9A8C\u8BC1\u6570\u636E\u96C6\uFF1A" + safe(datasetName);
    }

    public static String startPythonJobStepName() {
        return "\u542F\u52A8 Python \u9A8C\u8BC1\u4EFB\u52A1";
    }

    public static String startPythonJobMessage(String jobId) {
        return "\u5DF2\u63D0\u4EA4 Python \u4EFB\u52A1\uFF0CjobId=" + safe(jobId);
    }

    public static String modelUploadInitStepName() {
        return "\u521D\u59CB\u5316\u6A21\u578B\u4E0A\u4F20";
    }

    public static String modelUploadInitMessage(Long uploadId, Long modelAssetId, String originalFilename) {
        return "\u5BA2\u6237\u7AEF\u521D\u59CB\u5316\u6A21\u578B\u4E0A\u4F20\uFF0CuploadId=" + safe(uploadId)
                + "\uFF0CmodelAssetId=" + safe(modelAssetId)
                + "\uFF0C\u6587\u4EF6\u540D=" + safe(originalFilename);
    }

    public static String modelUploadCompleteStepName() {
        return "\u6A21\u578B\u4E0A\u4F20\u5B8C\u6210";
    }

    public static String modelUploadCompleteMessage(Long uploadId, Long modelAssetId, Path encryptedFilePath) {
        return "\u5BA2\u6237\u7AEF\u5DF2\u4E0A\u4F20\u52A0\u5BC6\u6A21\u578B\u6587\u4EF6\uFF0CuploadId=" + safe(uploadId)
                + "\uFF0CmodelAssetId=" + safe(modelAssetId)
                + "\uFF0C\u4E34\u65F6\u6587\u4EF6=" + safe(encryptedFilePath);
    }

    public static String modelDecryptStepName() {
        return "\u81EA\u52A8\u89E3\u5BC6\u6A21\u578B";
    }

    public static String modelDecryptMessage(Long uploadId, Long serverModelAssetId, Path targetFile) {
        return "\u670D\u52A1\u7AEF\u5DF2\u5B8C\u6210\u6A21\u578B\u89E3\u5BC6\u4E0E\u7EB3\u7BA1\uFF0CuploadId=" + safe(uploadId)
                + "\uFF0CserverModelAssetId=" + safe(serverModelAssetId)
                + "\uFF0C\u6587\u4EF6\u8DEF\u5F84=" + safe(targetFile);
    }

    public static String federatedAggregateStepName() {
        return "\u8054\u90A6\u5B66\u4E60\u805A\u5408";
    }

    public static String federatedAggregateStartMessage(int readyModelCount, String strategy) {
        return "\u670D\u52A1\u7AEF\u5F00\u59CB\u8054\u90A6\u5B66\u4E60\u805A\u5408\uFF0CreadyModelCount="
                + readyModelCount + "\uFF0Cstrategy=" + safe(strategy);
    }

    public static String secureShuffleStepName() {
        return "安全洗牌处理";
    }

    public static String secureShuffleMessage(String batchNo, String orderSummary) {
        return "已进入安全洗牌处理阶段，batchNo=" + safe(batchNo) + "，orderSummary=" + safe(orderSummary);
    }

    public static String differentialPrivacyStepName() {
        return "差分隐私处理";
    }

    public static String differentialPrivacyMessage(Object epsilon, Object delta, Object clipNorm, Object noiseMultiplier) {
        return "差分隐私处理链路已启用，epsilon=" + safe(epsilon)
                + "，delta=" + safe(delta)
                + "，clipNorm=" + safe(clipNorm)
                + "，noiseMultiplier=" + safe(noiseMultiplier);
    }

    public static String secureAggregationStepName() {
        return "安全聚合协议处理";
    }

    public static String secureAggregationMessage(String mode, int participantCount) {
        return "安全聚合协议处理链路执行中，mode=" + safe(mode) + "，participantCount=" + participantCount;
    }

    public static String federatedAggregateCompleteMessage(Long federatedModelAssetId, Path globalModelPath) {
        return "\u8054\u90A6\u5B66\u4E60\u805A\u5408\u5B8C\u6210\uFF0CfederatedModelAssetId=" + safe(federatedModelAssetId)
                + "\uFF0CglobalModelPath=" + safe(globalModelPath);
    }

    public static String federatedAggregateFailedMessage(String errorMessage) {
        return "\u8054\u90A6\u5B66\u4E60\u805A\u5408\u5931\u8D25\uFF1A" + safe(errorMessage);
    }

    public static String pythonCallbackStepName(String status) {
        return "Python \u56DE\u8C03\uFF1A" + WorkflowStatusEnum.stepTextOf(status);
    }

    public static String manualStatusStepName(String status) {
        switch (status) {
            case "ACCEPTED":
                return acceptStepName();
            case "PREPARING":
                return "\u51C6\u5907\u4EFB\u52A1";
            case "TRAINING_RUNNING":
                return "\u8054\u90A6\u8BAD\u7EC3\u4E2D";
            case "VALIDATING":
                return "\u9A8C\u8BC1\u4E2D";
            case "COMPLETED":
                return "\u9A8C\u8BC1\u5B8C\u6210";
            case "FAILED":
                return "\u6267\u884C\u5931\u8D25";
            default:
                return status;
        }
    }

    private static String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
