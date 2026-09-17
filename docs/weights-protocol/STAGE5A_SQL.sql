-- MANUAL EXECUTION ONLY
-- DO NOT AUTO EXECUTE
-- Stage 5A: idempotent InternImage ModelDefinition seed.
-- No schema change. Existing workflows are not modified.

SET NAMES utf8mb4;

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
  'INTERNIMAGE_T_UPERNET_WHEAT_V1',
  CONVERT(0x496e7465726e496d6167652d5420e5b08fe9baa6e58092e4bc8fe58886e589b2 USING utf8mb4),
  'INTERNIMAGE',
  'master@31c962dc',
  'InternImage-T + UPerNet',
  'SEMANTIC_SEGMENTATION',
  'MMSegmentation',
  '0.27.0',
  'MMSEG_TRUSTED_CONFIG_TEMPLATE',
  '1.0',
  CAST('{"schemaVersion":"1.0","definitionVersion":"1.0","definitionId":"INTERNIMAGE_T_UPERNET_WHEAT_V1","code":"INTERNIMAGE_T_UPERNET_WHEAT_V1","displayName":"InternImage-T \\u5c0f\\u9ea6\\u5012\\u4f0f\\u5206\\u5272","modelFamily":"INTERNIMAGE","version":"master@31c962dc","variant":"InternImage-T + UPerNet","taskType":"SEMANTIC_SEGMENTATION","framework":"MMSegmentation","frameworkVersion":"0.27.0","definitionType":"MMSEG_TRUSTED_CONFIG_TEMPLATE","definitionPayload":{"architecture":{"templateId":"INTERNIMAGE_T_UPERNET_WHEAT_V1","modelConfig":{"type":"EncoderDecoder","pretrained":null,"backbone":{"type":"InternImage","core_op":"DCNv3","channels":64,"depths":[4,4,18,4],"groups":[4,8,16,32],"mlp_ratio":4.0,"drop_path_rate":0.2,"norm_layer":"LN","layer_scale":1.0,"offset_scale":1.0,"post_norm":false,"with_cp":true,"out_indices":[0,1,2,3],"init_cfg":null},"decode_head":{"type":"UPerHead","in_channels":[64,128,256,512],"in_index":[0,1,2,3],"pool_scales":[1,2,3,6],"channels":512,"dropout_ratio":0.1,"num_classes":3,"norm_cfg":{"type":"GN","num_groups":32,"requires_grad":true},"align_corners":false,"ignore_index":255,"loss_decode":{"type":"CrossEntropyLoss","use_sigmoid":false,"loss_weight":1.0,"avg_non_ignore":true}},"auxiliary_head":{"type":"FCNHead","in_channels":256,"in_index":2,"channels":256,"num_convs":1,"concat_input":false,"dropout_ratio":0.1,"num_classes":3,"norm_cfg":{"type":"GN","num_groups":32,"requires_grad":true},"align_corners":false,"ignore_index":255,"loss_decode":{"type":"CrossEntropyLoss","use_sigmoid":false,"loss_weight":0.4,"avg_non_ignore":true}},"train_cfg":{},"test_cfg":{"mode":"whole"}},"customOps":["DCNv3"],"stateContract":{"tensorCount":728,"elementCount":58963958,"dtype":"float32"},"source":{"repository":"OpenGVLab/InternImage","commit":"31c962dc6c1ceb23e580772f7daaa6944694fbe6","decoder":"UPerNet"},"pretrainedPolicy":"FORBIDDEN"},"names":{"0":"background","1":"healthy_wheat","2":"lodged_wheat"},"decoder":"UPerNet","parameterSemantics":"MODEL_STATE_DICT","preprocessing":{"imageSize":[480,480],"resize":{"keepRatio":false},"normalization":{"mean":[123.675,116.28,103.53],"std":[58.395,57.12,57.375],"toRgb":true},"layout":"BCHW","segmentation":{"reduceZeroLabel":false,"ignoreIndex":255}},"runtimeRequirements":{"cudaRequired":true,"customOps":["DCNv3"]},"trustedSource":{"type":"EXPERIMENT_VERIFIED_HANDOFF","phase1":"PASS","phase2":"PASS","repository":"OpenGVLab/InternImage","commit":"31c962dc6c1ceb23e580772f7daaa6944694fbe6"}},"classCount":3,"classOrder":["background","healthy_wheat","lodged_wheat"],"ignoreIndex":255,"inputSpec":{"channels":3,"imageSize":[480,480],"layout":"BCHW"},"runtimeProfileId":"INTERNIMAGE_RUNTIME_V1","architectureSignature":"be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f","definitionSha256":"f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700","status":"ENABLED"}' AS JSON),
  'be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f',
  'f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700',
  'INTERNIMAGE_RUNTIME_V1',
  3,
  JSON_ARRAY('background', 'healthy_wheat', 'lodged_wheat'),
  255,
  'ENABLED'
WHERE NOT EXISTS (
  SELECT 1
  FROM `model_definition`
  WHERE `code` = 'INTERNIMAGE_T_UPERNET_WHEAT_V1'
);
