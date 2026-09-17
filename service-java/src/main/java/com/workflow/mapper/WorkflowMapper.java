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

    @Update("""
            UPDATE workflow
            SET federated_status = 'STARTING',
                federated_strategy = 'WEIGHTS_FEDAVG_V1',
                federated_started_at = NULL,
                federated_finished_at = NULL,
                federated_model_asset_id = NULL,
                current_step = #{currentStep},
                error_message = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
              AND status = 'ACCEPTED'
              AND model_definition_id IS NOT NULL
              AND (federated_status IS NULL OR federated_status IN ('PENDING', 'FAILED'))
              AND is_deleted = 0
            """)
    int claimWeightsFedAvgV1(@Param("workflowId") Long workflowId, @Param("currentStep") String currentStep);

    @Update("""
            UPDATE workflow
            SET federated_status = 'RUNNING',
                federated_started_at = CURRENT_TIMESTAMP,
                current_step = #{currentStep},
                progress = GREATEST(COALESCE(progress, 0), 55),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
              AND federated_status = 'STARTING'
              AND federated_strategy = 'WEIGHTS_FEDAVG_V1'
              AND is_deleted = 0
            """)
    int startWeightsFedAvgV1(@Param("workflowId") Long workflowId, @Param("currentStep") String currentStep);

    @Update("""
            UPDATE workflow
            SET federated_status = 'COMPLETED',
                federated_model_asset_id = #{assetId},
                federated_finished_at = CURRENT_TIMESTAMP,
                current_step = #{currentStep},
                progress = GREATEST(COALESCE(progress, 0), 60),
                error_message = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
              AND federated_status = 'RUNNING'
              AND federated_strategy = 'WEIGHTS_FEDAVG_V1'
              AND is_deleted = 0
            """)
    int completeWeightsFedAvgV1(@Param("workflowId") Long workflowId,
                                @Param("assetId") Long assetId,
                                @Param("currentStep") String currentStep);

    @Update("""
            UPDATE workflow
            SET federated_status = 'FAILED',
                federated_finished_at = CURRENT_TIMESTAMP,
                current_step = #{currentStep},
                error_message = #{errorMessage},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId}
              AND federated_status IN ('STARTING', 'RUNNING')
              AND federated_strategy = 'WEIGHTS_FEDAVG_V1'
              AND is_deleted = 0
            """)
    int failWeightsFedAvgV1(@Param("workflowId") Long workflowId,
                            @Param("currentStep") String currentStep,
                            @Param("errorMessage") String errorMessage);
}
