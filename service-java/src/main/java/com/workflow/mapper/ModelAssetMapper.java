package com.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workflow.entity.ModelAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelAssetMapper extends BaseMapper<ModelAsset> {

    @Update("""
            UPDATE model_asset
            SET is_deleted = 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND owner_user_id = #{serverUserId}
              AND owner_role_code = 'SERVER'
              AND source_type = 'WORKFLOW_DECRYPT'
              AND record_mode = 'FORMAL_ASSET'
              AND is_deleted = 0
            """)
    int softDeleteWorkflowDecryptAsset(@Param("id") Long id, @Param("serverUserId") Long serverUserId);

    @Update("""
            UPDATE model_asset
            SET is_deleted = 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND owner_user_id = #{serverUserId}
              AND owner_role_code = 'SERVER'
              AND source_type = 'FEDERATED_OUTPUT'
              AND record_mode = 'FORMAL_ASSET'
              AND is_deleted = 0
            """)
    int softDeleteOwnedFederatedOutputAsset(@Param("id") Long id, @Param("serverUserId") Long serverUserId);
}
