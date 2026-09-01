package com.workflow.dto.dashboard;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DashboardModelCardVO {

    private long totalModelCount;

    private Long latestModelId;

    private String latestModelName;

    private String latestModelVersion;

    private String latestModelStatus;

    private LocalDateTime latestModelCreatedAt;
}
