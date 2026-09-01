package com.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {

    /**
     * 原子性地将某个工作流的已收集模型数+1
     * 用于解决并发问题：多个客户端同时上传模型时不会丢失计数
     */
    @Update("UPDATE workflow SET collected_model_count = collected_model_count + 1 WHERE id = #{workflowId}")
    void incrementCollectedModelCount(@Param("workflowId") Long workflowId);
}