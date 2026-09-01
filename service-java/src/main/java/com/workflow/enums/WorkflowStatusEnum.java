package com.workflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WorkflowStatusEnum {

    CREATED("CREATED", "\u5BA2\u6237\u7AEF\u5DF2\u53D1\u8D77"),
    ACCEPTED("ACCEPTED", "\u670D\u52A1\u7AEF\u5DF2\u63A5\u6536"),
    PREPARING("PREPARING", "\u51C6\u5907\u4E2D"),
    TRAINING_RUNNING("TRAINING_RUNNING", "\u8054\u90A6\u8BAD\u7EC3\u4E2D"),
    VALIDATING("VALIDATING", "\u9A8C\u8BC1\u4E2D"),
    COMPLETED("COMPLETED", "\u5DF2\u5B8C\u6210"),
    FAILED("FAILED", "\u6267\u884C\u5931\u8D25"),
    WITHDRAWN("WITHDRAWN", "\u5DF2\u64A4\u56DE");

    private final String code;
    private final String desc;

    public static WorkflowStatusEnum fromCode(String code) {
        for (WorkflowStatusEnum item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        throw new IllegalArgumentException("\u672A\u77E5\u5DE5\u4F5C\u6D41\u72B6\u6001: " + code);
    }

    public static WorkflowStatusEnum nextManualStatus(String currentStatus) {
        switch (currentStatus) {
            case "CREATED":
                return ACCEPTED;
            case "ACCEPTED":
                return PREPARING;
            case "PREPARING":
                return TRAINING_RUNNING;
            case "TRAINING_RUNNING":
                return VALIDATING;
            case "VALIDATING":
                return COMPLETED;
            default:
                return null;
        }
    }

    public static int progressOf(String status) {
        switch (status) {
            case "CREATED":
                return 0;
            case "ACCEPTED":
                return 10;
            case "PREPARING":
                return 25;
            case "TRAINING_RUNNING":
                return 60;
            case "VALIDATING":
                return 85;
            case "COMPLETED":
                return 100;
            case "FAILED":
                return 0;
            case "WITHDRAWN":
                return 0;
            default:
                return 0;
        }
    }

    public static String stepTextOf(String status) {
        return fromCode(status).getDesc();
    }
}
