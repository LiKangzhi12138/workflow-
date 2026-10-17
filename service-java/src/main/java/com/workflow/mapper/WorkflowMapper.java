package com.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {

    @Update("""
            UPDATE workflow SET server_dataset_asset_id = #{datasetId}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND status = #{expectedStatus} AND is_deleted = 0
              AND ((#{expectedJobId} IS NULL AND python_job_id IS NULL)
                   OR python_job_id = #{expectedJobId})
            """)
    int bindDatasetIfCurrent(@Param("workflowId") Long workflowId,
                             @Param("datasetId") Long datasetId,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("expectedJobId") String expectedJobId);

    @Update("""
            UPDATE workflow SET status = 'PREPARING', python_job_id = #{newJobId},
                current_step = '重新验证任务提交中', progress = 0,
                error_message = NULL, metrics_json = NULL, result_file_path = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND status = 'FAILED'
              AND python_job_id = #{oldJobId}
              AND federated_status = 'COMPLETED'
              AND federated_model_asset_id IS NOT NULL AND is_deleted = 0
            """)
    int claimValidationRetry(@Param("workflowId") Long workflowId,
                             @Param("oldJobId") String oldJobId,
                             @Param("newJobId") String newJobId);

    @Update("""
            UPDATE workflow SET current_step = '重新验证任务提交中', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND status = 'PREPARING'
              AND python_job_id = #{jobId}
              AND current_step = '重新验证任务提交待确认' AND is_deleted = 0
            """)
    int reclaimValidationSubmission(@Param("workflowId") Long workflowId,
                                    @Param("jobId") String jobId);

    @Update("""
            UPDATE workflow SET current_step = '重新验证任务提交待确认',
                error_message = '验证任务提交未获确认，可使用同一任务编号重试。',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{workflowId} AND status = 'PREPARING'
              AND python_job_id = #{jobId}
              AND current_step = '重新验证任务提交中' AND is_deleted = 0
            """)
    int markValidationSubmissionUncertain(@Param("workflowId") Long workflowId,
                                          @Param("jobId") String jobId);

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
