package com.workflow.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ServerUserOptionVO {

    private Long id;
    private String username;
    private String displayName;
    private String label;
}
