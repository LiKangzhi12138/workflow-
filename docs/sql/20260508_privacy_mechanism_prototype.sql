ALTER TABLE `workflow`
  MODIFY COLUMN `federated_strategy` varchar(32) NOT NULL DEFAULT 'FEDML' COMMENT '联邦学习策略',
  ADD COLUMN `dp_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用差分隐私原型处理链路' AFTER `federated_finished_at`,
  ADD COLUMN `dp_epsilon` decimal(12,6) DEFAULT NULL COMMENT '差分隐私 epsilon 参数' AFTER `dp_enabled`,
  ADD COLUMN `dp_delta` decimal(18,12) DEFAULT NULL COMMENT '差分隐私 delta 参数' AFTER `dp_epsilon`,
  ADD COLUMN `dp_clip_norm` decimal(12,6) DEFAULT NULL COMMENT '差分隐私裁剪阈值 clip norm' AFTER `dp_delta`,
  ADD COLUMN `dp_noise_multiplier` decimal(12,6) DEFAULT NULL COMMENT '差分隐私噪声倍率' AFTER `dp_clip_norm`,
  ADD COLUMN `dp_status` varchar(32) NOT NULL DEFAULT 'DISABLED' COMMENT '差分隐私处理状态：DISABLED/ENABLED/RUNNING/COMPLETED/FAILED' AFTER `dp_noise_multiplier`,
  ADD COLUMN `dp_summary` varchar(500) DEFAULT NULL COMMENT '差分隐私处理摘要' AFTER `dp_status`,
  ADD COLUMN `shuffle_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用安全洗牌原型处理链路' AFTER `dp_summary`,
  ADD COLUMN `shuffle_batch_no` varchar(64) DEFAULT NULL COMMENT '安全洗牌批次号' AFTER `shuffle_enabled`,
  ADD COLUMN `shuffle_status` varchar(32) NOT NULL DEFAULT 'DISABLED' COMMENT '安全洗牌状态：DISABLED/WAITING/RUNNING/COMPLETED/FAILED' AFTER `shuffle_batch_no`,
  ADD COLUMN `shuffle_order_summary` varchar(500) DEFAULT NULL COMMENT '安全洗牌顺序摘要' AFTER `shuffle_status`,
  ADD COLUMN `shuffle_started_at` datetime DEFAULT NULL COMMENT '安全洗牌开始时间' AFTER `shuffle_order_summary`,
  ADD COLUMN `shuffle_finished_at` datetime DEFAULT NULL COMMENT '安全洗牌完成时间' AFTER `shuffle_started_at`,
  ADD COLUMN `secure_aggregation_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用安全聚合协议原型处理链路' AFTER `shuffle_finished_at`,
  ADD COLUMN `secure_aggregation_mode` varchar(32) NOT NULL DEFAULT 'PLAIN' COMMENT '聚合模式：PLAIN/SECURE' AFTER `secure_aggregation_enabled`,
  ADD COLUMN `secure_aggregation_status` varchar(32) NOT NULL DEFAULT 'DISABLED' COMMENT '安全聚合状态：DISABLED/PREPARING/RUNNING/COMPLETED/FAILED' AFTER `secure_aggregation_mode`,
  ADD COLUMN `secure_aggregation_summary` varchar(500) DEFAULT NULL COMMENT '安全聚合处理摘要' AFTER `secure_aggregation_status`,
  ADD COLUMN `secure_aggregation_started_at` datetime DEFAULT NULL COMMENT '安全聚合开始时间' AFTER `secure_aggregation_summary`,
  ADD COLUMN `secure_aggregation_finished_at` datetime DEFAULT NULL COMMENT '安全聚合完成时间' AFTER `secure_aggregation_started_at`;

UPDATE `workflow`
SET `federated_strategy` = 'FEDML'
WHERE `federated_strategy` = 'FEDAVG';
