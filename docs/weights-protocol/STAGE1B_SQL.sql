-- MANUAL EXECUTION ONLY
-- DO NOT AUTO EXECUTE
-- Stage 1B: ModelDefinition Registry and nullable workflow binding.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `model_definition` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `display_name` varchar(128) NOT NULL,
  `model_family` varchar(64) NOT NULL,
  `model_version` varchar(64) NOT NULL,
  `variant` varchar(64) NOT NULL,
  `task_type` varchar(64) NOT NULL,
  `framework` varchar(64) NOT NULL,
  `framework_version` varchar(64) NOT NULL,
  `definition_type` varchar(64) NOT NULL,
  `definition_version` varchar(32) NOT NULL,
  `definition_json` json NOT NULL,
  `architecture_signature` char(64) NOT NULL,
  `definition_sha256` char(64) NOT NULL,
  `runtime_profile_id` varchar(64) NOT NULL,
  `class_count` int NOT NULL,
  `class_order_json` json NOT NULL,
  `ignore_index` int DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DISABLED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_definition_code` (`code`),
  KEY `idx_model_definition_status` (`status`),
  KEY `idx_model_definition_runtime_profile` (`runtime_profile_id`),
  KEY `idx_model_definition_family_variant` (`model_family`, `variant`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Keep this migration repeatable without assigning a fake definition to history.
SET @workflow_model_definition_column_sql = IF(
  EXISTS(
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workflow'
      AND COLUMN_NAME = 'model_definition_id'
  ),
  'SELECT 1',
  'ALTER TABLE `workflow` ADD COLUMN `model_definition_id` bigint NULL AFTER `client_model_asset_id`'
);
PREPARE stage1b_column_stmt FROM @workflow_model_definition_column_sql;
EXECUTE stage1b_column_stmt;
DEALLOCATE PREPARE stage1b_column_stmt;

SET @workflow_model_definition_index_sql = IF(
  EXISTS(
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workflow'
      AND INDEX_NAME = 'idx_workflow_model_definition_id'
  ),
  'SELECT 1',
  'CREATE INDEX `idx_workflow_model_definition_id` ON `workflow` (`model_definition_id`)'
);
PREPARE stage1b_index_stmt FROM @workflow_model_definition_index_sql;
EXECUTE stage1b_index_stmt;
DEALLOCATE PREPARE stage1b_index_stmt;

-- definitionSha256 uses CANONICAL_JSON_SHA256_V1 with definitionSha256 blanked.
-- The UTF-8 hex literal avoids client/terminal encoding damage during manual execution.
INSERT INTO `model_definition` (
  `code`,
  `display_name`,
  `model_family`,
  `model_version`,
  `variant`,
  `task_type`,
  `framework`,
  `framework_version`,
  `definition_type`,
  `definition_version`,
  `definition_json`,
  `architecture_signature`,
  `definition_sha256`,
  `runtime_profile_id`,
  `class_count`,
  `class_order_json`,
  `ignore_index`,
  `status`
)
SELECT
  'YOLOV8N_SHEEP_V1',
  CONVERT(0x594f4c4f76386e20e7be8ae7bea4e6a380e6b58b USING utf8mb4),
  'YOLO',
  'YOLOv8',
  'yolov8n',
  'DETECTION',
  'Ultralytics',
  '8.4.41',
  'ULTRALYTICS_DETECTION_MODEL',
  '1.0',
  CAST('{"schemaVersion":"1.0","definitionVersion":"1.0","definitionId":"YOLOV8N_SHEEP_V1","code":"YOLOV8N_SHEEP_V1","displayName":"YOLOv8n \\u7f8a\\u7fa4\\u68c0\\u6d4b","modelFamily":"YOLO","version":"YOLOv8","variant":"yolov8n","taskType":"DETECTION","framework":"Ultralytics","frameworkVersion":"8.4.41","definitionType":"ULTRALYTICS_DETECTION_MODEL","definitionPayload":{"architecture":{"nc":1,"depth_multiple":0.33,"width_multiple":0.25,"backbone":[[-1,1,"Conv",[64,3,2]],[-1,1,"Conv",[128,3,2]],[-1,3,"C2f",[128,true]],[-1,1,"Conv",[256,3,2]],[-1,6,"C2f",[256,true]],[-1,1,"Conv",[512,3,2]],[-1,6,"C2f",[512,true]],[-1,1,"Conv",[1024,3,2]],[-1,3,"C2f",[1024,true]],[-1,1,"SPPF",[1024,5]]],"head":[[-1,1,"nn.Upsample",["None",2,"nearest"]],[[-1,6],1,"Concat",[1]],[-1,3,"C2f",[512]],[-1,1,"nn.Upsample",["None",2,"nearest"]],[[-1,4],1,"Concat",[1]],[-1,3,"C2f",[256]],[-1,1,"Conv",[256,3,2]],[[-1,12],1,"Concat",[1]],[-1,3,"C2f",[512]],[-1,1,"Conv",[512,3,2]],[[-1,9],1,"Concat",[1]],[-1,3,"C2f",[1024]],[[15,18,21],1,"Detect",["nc"]]],"ch":3,"channels":3},"inputChannels":3,"names":{"0":"sheep"},"stride":[8,16,32],"imgsz":640,"parameterSemantics":"EMA_SNAPSHOT","preprocessing":{"implementation":"Ultralytics","resize":"LetterBox","colorConversion":"OpenCV BGR to RGB","layout":"BHWC to BCHW","normalization":"divide by 255 to [0,1]"},"trustedSource":{"type":"EXPERIMENT_VERIFIED_HANDOFF","phase1":"PASS","phase2":"PASS"}},"classCount":1,"classOrder":["sheep"],"ignoreIndex":null,"inputSpec":{"channels":3,"imageSize":640,"layout":"BCHW"},"runtimeProfileId":"YOLO_RUNTIME_V1","architectureSignature":"2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a","definitionSha256":"302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2","status":"ENABLED"}' AS JSON),
  '2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a',
  '302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2',
  'YOLO_RUNTIME_V1',
  1,
  JSON_ARRAY('sheep'),
  NULL,
  'ENABLED'
WHERE NOT EXISTS (
  SELECT 1
  FROM `model_definition`
  WHERE `code` = 'YOLOV8N_SHEEP_V1'
);

-- Intentionally absent: UPDATE workflow SET model_definition_id = ...
-- Historical workflows remain NULL until an explicit future migration is approved.

