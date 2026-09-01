SET NAMES utf8mb4;

ALTER TABLE `model_asset`
  MODIFY COLUMN `file_path` varchar(500) DEFAULT NULL,
  ADD COLUMN `source_path` varchar(500) DEFAULT NULL AFTER `file_path`,
  ADD COLUMN `source_type` varchar(32) NOT NULL DEFAULT 'LEGACY_PATH' AFTER `source_path`,
  ADD COLUMN `import_mode` varchar(32) NOT NULL DEFAULT 'LEGACY' AFTER `source_type`;

ALTER TABLE `dataset_asset`
  MODIFY COLUMN `file_path` varchar(500) DEFAULT NULL,
  ADD COLUMN `source_path` varchar(500) DEFAULT NULL AFTER `file_path`,
  ADD COLUMN `source_type` varchar(32) NOT NULL DEFAULT 'LEGACY_PATH' AFTER `source_path`,
  ADD COLUMN `import_mode` varchar(32) NOT NULL DEFAULT 'LEGACY' AFTER `source_type`;

ALTER TABLE `workflow`
  MODIFY COLUMN `result_file_path` varchar(500) DEFAULT NULL;

UPDATE `model_asset`
SET
  `source_type` = CASE
    WHEN `source_type` IS NULL OR `source_type` = '' THEN 'LEGACY_PATH'
    ELSE `source_type`
  END,
  `import_mode` = CASE
    WHEN `import_mode` IS NULL OR `import_mode` = '' THEN 'LEGACY'
    ELSE `import_mode`
  END
WHERE `source_type` IS NULL OR `source_type` = '' OR `import_mode` IS NULL OR `import_mode` = '';

UPDATE `dataset_asset`
SET
  `source_type` = CASE
    WHEN `source_type` IS NULL OR `source_type` = '' THEN 'LEGACY_PATH'
    ELSE `source_type`
  END,
  `import_mode` = CASE
    WHEN `import_mode` IS NULL OR `import_mode` = '' THEN 'LEGACY'
    ELSE `import_mode`
  END
WHERE `source_type` IS NULL OR `source_type` = '' OR `import_mode` IS NULL OR `import_mode` = '';

SELECT
  `COLUMN_NAME`,
  `COLUMN_TYPE`,
  `IS_NULLABLE`,
  `COLUMN_DEFAULT`
FROM `INFORMATION_SCHEMA`.`COLUMNS`
WHERE `TABLE_SCHEMA` = DATABASE()
  AND `TABLE_NAME` IN ('model_asset', 'dataset_asset', 'workflow')
  AND `COLUMN_NAME` IN ('file_path', 'source_path', 'source_type', 'import_mode', 'result_file_path')
ORDER BY `TABLE_NAME`, `ORDINAL_POSITION`;

SELECT
  COUNT(*) AS `legacy_model_asset_count`
FROM `model_asset`
WHERE `source_type` = 'LEGACY_PATH' OR `import_mode` = 'LEGACY';

SELECT
  COUNT(*) AS `legacy_dataset_asset_count`
FROM `dataset_asset`
WHERE `source_type` = 'LEGACY_PATH' OR `import_mode` = 'LEGACY';
