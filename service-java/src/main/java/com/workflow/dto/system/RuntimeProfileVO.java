package com.workflow.dto.system;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RuntimeProfileVO {

    private String runtimeMode;

    private Boolean serverPathImportEnabled;

    private Boolean serverPathImportAllowed;

    private List<String> serverPathImportRoots;
}
