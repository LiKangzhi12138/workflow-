package com.workflow.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public enum WorkflowModelUploadStatusEnum {

    PENDING("PENDING"),
    UPLOADING("UPLOADING"),
    ENCRYPTED_STORED("ENCRYPTED_STORED"),
    DECRYPTING("DECRYPTING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private static final Set<String> ACTIVE_SLOT_STATUSES = EnumSet.of(
            PENDING,
            UPLOADING,
            ENCRYPTED_STORED,
            DECRYPTING,
            COMPLETED
    ).stream().map(WorkflowModelUploadStatusEnum::getCode).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> RECEIVED_FILE_STATUSES = EnumSet.of(
            ENCRYPTED_STORED,
            DECRYPTING,
            COMPLETED
    ).stream().map(WorkflowModelUploadStatusEnum::getCode).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> VALIDATION_READY_STATUSES = EnumSet.of(
            COMPLETED
    ).stream().map(WorkflowModelUploadStatusEnum::getCode).collect(Collectors.toUnmodifiableSet());

    private final String code;

    WorkflowModelUploadStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static boolean isActiveSlotStatus(String code) {
        return ACTIVE_SLOT_STATUSES.contains(code);
    }

    public static boolean isReceivedFileStatus(String code) {
        return RECEIVED_FILE_STATUSES.contains(code);
    }

    public static boolean isValidationReadyStatus(String code) {
        return VALIDATION_READY_STATUSES.contains(code);
    }

    public static Set<String> activeSlotCodes() {
        return ACTIVE_SLOT_STATUSES;
    }

    public static Set<String> receivedFileCodes() {
        return RECEIVED_FILE_STATUSES;
    }

    public static Set<String> validationReadyCodes() {
        return VALIDATION_READY_STATUSES;
    }

    public static boolean isKnown(String code) {
        return Arrays.stream(values()).anyMatch(item -> item.code.equals(code));
    }
}
