-- Result retention governance for workflow outputs.
-- MySQL 9.x compatible note:
-- Do not run the ALTER statements if the columns already exist.
-- Verify first with the information_schema query below.

SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'workflow'
  AND COLUMN_NAME IN ('result_retention_status', 'result_saved_at', 'result_deleted_at');

ALTER TABLE `workflow`
  ADD COLUMN `result_retention_status` varchar(32) NOT NULL DEFAULT 'TEMPORARY' COMMENT 'Workflow result retention state: TEMPORARY, SAVED, DELETED' AFTER `result_file_path`,
  ADD COLUMN `result_saved_at` datetime DEFAULT NULL COMMENT 'Time when user manually saved result' AFTER `result_retention_status`,
  ADD COLUMN `result_deleted_at` datetime DEFAULT NULL COMMENT 'Time when result artifacts were deleted' AFTER `result_saved_at`;

UPDATE `workflow`
SET `result_retention_status` = 'TEMPORARY'
WHERE `result_retention_status` IS NULL OR `result_retention_status` = '';

CREATE INDEX `idx_workflow_result_retention_status`
  ON `workflow` (`result_retention_status`);

-- Acceptance queries
SELECT `id`, `workflow_code`, `status`, `result_file_path`, `result_retention_status`, `result_saved_at`, `result_deleted_at`
FROM `workflow`
ORDER BY `id` DESC
LIMIT 20;

SELECT `result_retention_status`, COUNT(*) AS count
FROM `workflow`
GROUP BY `result_retention_status`;
