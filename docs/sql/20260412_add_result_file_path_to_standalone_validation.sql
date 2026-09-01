ALTER TABLE standalone_validation
ADD COLUMN result_file_path VARCHAR(500) NULL COMMENT 'Python validation result file path' AFTER metrics_json;
