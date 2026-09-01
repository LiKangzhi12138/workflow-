SET @current_schema = DATABASE();

SET @ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @current_schema
              AND TABLE_NAME = 'workflow_model_upload'
              AND COLUMN_NAME = 'model_asset_id'
        ),
        'SELECT ''workflow_model_upload.model_asset_id already exists''',
        'ALTER TABLE workflow_model_upload ADD COLUMN model_asset_id BIGINT DEFAULT NULL COMMENT ''关联客户端本地模型资产ID'' AFTER uploader_id'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @current_schema
              AND TABLE_NAME = 'workflow_model_upload'
              AND COLUMN_NAME = 'token_expire_at'
        ),
        'SELECT ''workflow_model_upload.token_expire_at already exists''',
        'ALTER TABLE workflow_model_upload ADD COLUMN token_expire_at DATETIME DEFAULT NULL COMMENT ''uploadToken过期时间'' AFTER upload_token'
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
              AND INDEX_NAME = 'idx_wmu_model_asset_id'
        ),
        'SELECT ''workflow_model_upload.idx_wmu_model_asset_id already exists''',
        'CREATE INDEX idx_wmu_model_asset_id ON workflow_model_upload (model_asset_id)'
    )
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
