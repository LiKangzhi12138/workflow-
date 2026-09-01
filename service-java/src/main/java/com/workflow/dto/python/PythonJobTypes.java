package com.workflow.dto.python;

public final class PythonJobTypes {

    public static final String WORKFLOW_VALIDATION = "WORKFLOW_VALIDATION";
    public static final String STANDALONE_VALIDATION = "STANDALONE_VALIDATION";

    private PythonJobTypes() {
    }

    public static boolean isWorkflowValidation(String jobType) {
        return WORKFLOW_VALIDATION.equals(jobType);
    }

    public static boolean isStandaloneValidation(String jobType) {
        return STANDALONE_VALIDATION.equals(jobType);
    }
}
