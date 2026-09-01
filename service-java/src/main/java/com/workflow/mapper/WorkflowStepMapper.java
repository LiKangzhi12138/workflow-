package com.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.entity.WorkflowStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkflowStepMapper extends BaseMapper<WorkflowStep> {

    @Select("SELECT COALESCE(MAX(step_no), 0) FROM workflow_step WHERE workflow_id = #{workflowId}")
    Integer selectMaxStepNoByWorkflowId(@Param("workflowId") Long workflowId);
}
