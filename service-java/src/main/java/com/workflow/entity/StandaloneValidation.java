package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("standalone_validation")
public class StandaloneValidation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String validationCode;

    private Long userId;

    private Long modelAssetId;

    private String modelPath;

    private Long datasetAssetId;

    private String datasetPath;

    private String algorithmType;

    private String inputMode;

    private String inputRootPath;

    private Integer retainInput;

    private String inputCleanupStatus;

    private LocalDateTime inputCleanedAt;

    private String status;

    private Integer progress;

    private String pythonJobId;

    private String metricsJson;

    private String resultFilePath;

    private String qualityLabel;

    private String errorMessage;

    private LocalDateTime finishedAt;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
