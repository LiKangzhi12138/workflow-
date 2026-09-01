package com.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.entity.WorkflowModelUpload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowModelUploadMapper extends BaseMapper<WorkflowModelUpload> {

    WorkflowModelUpload selectTrackedById(@Param("id") Long id);

    int updateDecryptResult(WorkflowModelUpload upload);

    int updateDecryptFailure(@Param("id") Long id,
                             @Param("decryptedFilePath") String decryptedFilePath,
                             @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE workflow_model_upload
            SET encrypted_file_path = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND workflow_id = #{workflowId}
              AND aggregation_status = 'COMPLETED'
              AND is_deleted = 0
            """)
    int clearEncryptedFilePath(@Param("id") Long id, @Param("workflowId") Long workflowId);

    @Update("""
            UPDATE workflow_model_upload
            SET decrypted_file_path = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND workflow_id = #{workflowId}
              AND aggregation_status = 'COMPLETED'
              AND is_deleted = 0
            """)
    int clearDecryptedFilePath(@Param("id") Long id, @Param("workflowId") Long workflowId);
}
