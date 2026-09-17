package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_definition")
public class ModelDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String displayName;
    private String modelFamily;
    private String modelVersion;
    private String variant;
    private String taskType;
    private String framework;
    private String frameworkVersion;
    private String definitionType;
    private String definitionVersion;
    private String definitionJson;
    private String architectureSignature;
    private String definitionSha256;
    private String runtimeProfileId;
    private Integer classCount;
    private String classOrderJson;
    private Integer ignoreIndex;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
