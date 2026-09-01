package com.workflow.dto.validation;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateStandaloneValidationRequest {

    @NotNull(message = "\u6A21\u578B\u8D44\u4EA7ID\u4E0D\u80FD\u4E3A\u7A7A")
    private Long modelAssetId;

    @NotNull(message = "\u6570\u636E\u96C6\u8D44\u4EA7ID\u4E0D\u80FD\u4E3A\u7A7A")
    private Long datasetAssetId;

    private String algorithmType;
}
