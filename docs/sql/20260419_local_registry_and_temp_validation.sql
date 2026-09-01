-- 目的：
-- 1. 区分路径登记项（PATH_REGISTRY）与正式资产（FORMAL_ASSET）
-- 2. 为模型/数据集登记项补充最近校验信息
-- 3. 为独立验证补充临时输入模式与输入清理状态

ALTER TABLE `model_asset`
  ADD COLUMN IF NOT EXISTS `record_mode` varchar(32) NOT NULL DEFAULT 'FORMAL_ASSET' AFTER `import_mode`,
  ADD COLUMN IF NOT EXISTS `last_check_at` datetime DEFAULT NULL AFTER `file_path_validated`,
  ADD COLUMN IF NOT EXISTS `last_check_status` varchar(32) DEFAULT NULL AFTER `last_check_at`,
  ADD COLUMN IF NOT EXISTS `last_check_message` varchar(500) DEFAULT NULL AFTER `last_check_status`;

ALTER TABLE `dataset_asset`
  ADD COLUMN IF NOT EXISTS `record_mode` varchar(32) NOT NULL DEFAULT 'FORMAL_ASSET' AFTER `import_mode`,
  ADD COLUMN IF NOT EXISTS `last_check_at` datetime DEFAULT NULL AFTER `file_path_validated`,
  ADD COLUMN IF NOT EXISTS `last_check_status` varchar(32) DEFAULT NULL AFTER `last_check_at`,
  ADD COLUMN IF NOT EXISTS `last_check_message` varchar(500) DEFAULT NULL AFTER `last_check_status`;

ALTER TABLE `standalone_validation`
  MODIFY COLUMN `model_asset_id` bigint DEFAULT NULL,
  MODIFY COLUMN `dataset_asset_id` bigint DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `input_mode` varchar(32) NOT NULL DEFAULT 'ASSET_REFERENCE' AFTER `algorithm_type`,
  ADD COLUMN IF NOT EXISTS `input_root_path` varchar(500) DEFAULT NULL AFTER `input_mode`,
  ADD COLUMN IF NOT EXISTS `retain_input` tinyint NOT NULL DEFAULT 1 AFTER `input_root_path`,
  ADD COLUMN IF NOT EXISTS `input_cleanup_status` varchar(32) DEFAULT NULL AFTER `retain_input`,
  ADD COLUMN IF NOT EXISTS `input_cleaned_at` datetime DEFAULT NULL AFTER `input_cleanup_status`;

UPDATE `model_asset`
SET `record_mode` = 'FORMAL_ASSET'
WHERE `record_mode` IS NULL OR `record_mode` = '';

UPDATE `dataset_asset`
SET `record_mode` = 'FORMAL_ASSET'
WHERE `record_mode` IS NULL OR `record_mode` = '';

UPDATE `standalone_validation`
SET
  `input_mode` = 'ASSET_REFERENCE',
  `retain_input` = 1
WHERE `input_mode` IS NULL OR `input_mode` = '';

-- 验收查询
SELECT `id`, `asset_name`, `record_mode`, `source_type`, `file_path_validated`, `last_check_status`
FROM `model_asset`
ORDER BY `id` DESC
LIMIT 10;

SELECT `id`, `asset_name`, `record_mode`, `source_type`, `file_path_validated`, `last_check_status`
FROM `dataset_asset`
ORDER BY `id` DESC
LIMIT 10;

SELECT `id`, `validation_code`, `input_mode`, `retain_input`, `input_cleanup_status`, `input_cleaned_at`
FROM `standalone_validation`
ORDER BY `id` DESC
LIMIT 10;
