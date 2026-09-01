package com.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_step")
public class WorkflowStep {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("workflow_id")
    private Long workflowId;

    @TableField("step_no")
    private Integer stepNo;

    @TableField("step_code")
    private String stepCode;

    @TableField("step_name")
    private String stepName;

    @TableField("from_status")
    private String fromStatus;

    @TableField("to_status")
    private String toStatus;

    @TableField("operator_user_id")
    private Long operatorUserId;

    @TableField("operator_role")
    private String operatorRole;

    @TableField("message")
    private String message;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
