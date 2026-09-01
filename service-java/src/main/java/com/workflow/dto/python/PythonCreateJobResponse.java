package com.workflow.dto.python;

import lombok.Data;

@Data
public class PythonCreateJobResponse {

    private String code;
    private String message;
    private PythonCreateJobResponseData data;
}