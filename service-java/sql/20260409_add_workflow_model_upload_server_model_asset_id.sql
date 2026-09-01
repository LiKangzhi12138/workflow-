SET @current_schema = DATABASE();

SET @ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @current_schema
              AND TABLE_NAME = 'workflow_model_upload'
              AND COLUMN_NAME = 'server_model_asset_id'
        ),
        'SELECT ''workflow_model_upload.server_model_asset_id already exists''',
        'ALTER TABLE workflow_model_upload ADD COLUMN server_model_asset_id BIGINT DEFAULT NULL COMMENT ''解密并导入后生成的服务端模型资产ID'' AFTER model_asset_id'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = @current_schema
              AND TABLE_NAME = 'workflow_model_upload'
              AND INDEX_NAME = 'idx_wmu_server_model_asset_id'
        ),
        'SELECT ''workflow_model_upload.idx_wmu_server_model_asset_id already exists''',
        'CREATE INDEX idx_wmu_server_model_asset_id ON workflow_model_upload (server_model_asset_id)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
