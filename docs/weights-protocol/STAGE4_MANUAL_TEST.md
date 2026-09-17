# Stage 4 Manual Test (Windows CMD)

本验收只验证 `FEDERATED_WEIGHTS_V1 + ModelDefinition -> YOLO Runtime -> Validation`。
不要执行 Stage 5，不要执行写 SQL，不要使用 `docker compose down -v`。

## 1. 备份配置

打开 Windows CMD：

```cmd
cd /d D:\workspace\workflow-platform
copy /Y .env.local-server .env.local-server.stage4.backup
notepad .env.local-server
```

保留已有 secret，不要把 `.env.local-server` 内容贴到终端日志。确认已有：

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
WEIGHTS_PROTOCOL_V1_ENABLED=true
WEIGHTS_FEDAVG_V1_ENABLED=true
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
```

先加入并保持：

```text
WEIGHTS_VALIDATION_V1_ENABLED=false
```

代码默认也是 false。

## 2. 静态检查、构建和启动

Stage 4 修改了 Java、协议 Python、YOLO Runtime 和 Compose mount；只重建这三个
服务：

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build service-java service-python yolo-runtime
docker compose --env-file .env.local-server --profile model-runtime up -d service-java service-python yolo-runtime
docker compose --env-file .env.local-server --profile model-runtime ps
```

确认 mysql、service-java、service-python、yolo-runtime、gateway 均 healthy/running。
不要执行 `down`，尤其禁止 `down -v`。

## 3. Runtime Health

从 `service-python` 容器验证内部网络：

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,urllib.request; print(json.dumps(json.load(urllib.request.urlopen('http://yolo-runtime:8010/internal/runtime/health')),ensure_ascii=False,indent=2))"
```

要求：

```text
runtimeProfileId = YOLO_RUNTIME_V1
probeContext     = CONTAINER_RUNTIME
ultralyticsVersion = 8.4.41
cudaAvailable   = true
gpuAvailable    = true
runtimeReady    = true
```

在 SERVER 登录后的浏览器中确认 `GET /api/model-definitions/available` 仍包含
`YOLOV8N_SHEEP_V1` 且 `availability.available=true`。

## 4. 检查 Workflow 37 是否可直接使用

进入 MySQL 仅执行只读查询。不要把密码写在命令参数中：

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -u root -p
```

```sql
SELECT id, workflow_code, status, current_step, model_definition_id,
       server_dataset_asset_id, federated_status, federated_strategy,
       federated_model_asset_id, python_job_id, error_message
FROM workflow
WHERE id = 37;

SELECT id, asset_name, file_path, status, last_check_status, is_deleted
FROM dataset_asset
WHERE id = (SELECT server_dataset_asset_id FROM workflow WHERE id = 37);

SELECT id, file_path, file_sha256, source_type, import_mode, record_mode,
       status, last_check_status, is_deleted
FROM model_asset
WHERE id = 77;
```

Workflow 37 可直接测试的条件：

```text
model_definition_id       = 1
server_dataset_asset_id   非 NULL
dataset asset             READY/OK、未删除、目录实际存在
federated_status          = COMPLETED
federated_strategy        = WEIGHTS_FEDAVG_V1
federated_model_asset_id  = 77
asset 77 source_type      = FEDERATED_WEIGHTS_V1
asset 77 status/check     = READY/OK
python_job_id             为空（尚未启动过验证）
```

退出 MySQL：

```sql
exit
```

若 dataset 绑定无效或 workflow 已经启动过 Validation，不要 UPDATE workflow 37。
创建新的 Definition workflow，按 Stage 2A/3 完成 A+B 上传与 FedAvg 后再测试。

## 5. Feature Flag=false 安全测试

保持 `WEIGHTS_VALIDATION_V1_ENABLED=false`，在 SERVER 页面打开满足条件的 V1
workflow 并点击“启动验证”。要求请求明确失败，提示 weights-only Validation 尚未
启用；不得创建 Python job，不得 fallback 到 Legacy checkpoint loader。

检查日志：

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 160 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 120 service-python
```

应看到 Java 的 `WEIGHTS_VALIDATION_V1_DISABLED`，service-python 不应收到该 job。

## 6. 启用 Stage 4

```cmd
notepad .env.local-server
```

只把以下项改为：

```text
WEIGHTS_VALIDATION_V1_ENABLED=true
```

让 Java 和 service-python 重新读取环境；镜像已在第 2 节构建：

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d service-java service-python
docker compose --env-file .env.local-server --profile model-runtime ps
```

## 7. 显式启动 V1 Validation

1. 使用 SERVER 登录。
2. 打开 workflow 37（仅当第 4 节全部满足）或新的 Stage 4 workflow。
3. 确认页面显示联邦学习已完成且可启动验证。
4. 点击一次“启动验证”。
5. 不要重复点击，不要重新跑 A/B 聚合。

期望调用链：

```text
POST /api/workflows/<workflowId>/start-python-job
-> service-python POST /internal/jobs
-> yolo-runtime POST /internal/runtime/validate-weights-v1
-> signed callback to Java
```

## 8. 三层日志证据

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-python
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 yolo-runtime
```

应能关联同一 workflowId/jobId/assetId，并看到：

```text
validationMode=WEIGHTS_PROTOCOL_V1
trusted protocol definitionId=YOLOV8N_SHEEP_V1
runtimeProfileId=YOLO_RUNTIME_V1
strict load success
callback status=COMPLETED
```

不得出现 `yolov8n.pt`、`best.pt`、base checkpoint 下载或 Legacy loader。

## 9. 数据库只读验收

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -u root -p
```

替换 `<workflowId>`：

```sql
SELECT id, workflow_code, model_definition_id, server_dataset_asset_id,
       federated_status, federated_strategy, federated_model_asset_id,
       status, current_step, python_job_id, progress,
       result_file_path, error_message
FROM workflow
WHERE id = <workflowId>;

SELECT id, file_path, file_sha256, source_type, import_mode, record_mode,
       status, last_check_status, is_deleted
FROM model_asset
WHERE id = (SELECT federated_model_asset_id FROM workflow WHERE id = <workflowId>);
```

只允许 SELECT。期望 workflow validation 为 COMPLETED、result path 非空，global
asset 仍为 `FEDERATED_WEIGHTS_V1`，没有创建 Legacy `FEDERATED_OUTPUT` 替代品。

## 10. Result 与 Evidence

从上一节取得 `result_file_path`，在 `service-python` 容器中仅作只读检查。将
`<resultPath>` 替换为实际容器路径：

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json; p=r'<resultPath>'; d=json.load(open(p,encoding='utf-8')); print(json.dumps({'validationEvidence':d.get('validationEvidence'),'metrics':d.get('metrics'),'sampleResults':d.get('sampleResults')},ensure_ascii=False,indent=2))"
```

证据必须包含：

```text
validationMode       = WEIGHTS_PROTOCOL_V1
modelDefinitionId    = YOLOV8N_SHEEP_V1
globalWeightsAssetId = 77（若使用 workflow 37）
globalWeightsSha256  = 532fb6fa32b57a8df6403813b7a2fafe92bdfde8a4ca2737b44694d14170a23f
runtimeProfileId     = YOLO_RUNTIME_V1
weightsOnly          = true
checkpointUsed       = false
baseCheckpointUsed   = false
reconstructionMode   = DEFINITION_PLUS_STATE_DICT
strictLoad           = true
missingKeyCount      = 0
unexpectedKeyCount   = 0
validationCompleted  = true
```

如果使用新 workflow，以其实际 asset ID/SHA 为准。

## 11. Sample Image

`sampleResults` 中至少一个条目应有：

```text
annotatedImageAvailable = true
annotatedImagePath       非空
predictionCount          >= 0
predictions              数组
```

用容器内只读命令确认文件存在：

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,os; p=r'<resultPath>'; d=json.load(open(p,encoding='utf-8')); print([(x.get('annotatedImagePath'),os.path.isfile(x.get('annotatedImagePath') or '')) for x in d.get('sampleResults',[])])"
```

## 12. CLIENT / SERVER 页面

分别检查：

1. CLIENT Workflow Progress：Validation 状态完成。
2. SERVER Workflow Progress：状态、进度和启动按钮终态正确。
3. CLIENT Validation Result：metrics、sample image、predictions 正常。
4. SERVER Validation Result：同一 result 正常。

页面无需理解 checkpoint/rebuild 细节，但展示的数据必须来自本次 V1 result。

## 13. Legacy Validation 回归

选择一个已有 Legacy workflow，其 global asset 为 `FEDERATED_OUTPUT`。执行一次现有
手动 Validation，确认仍走 `validationMode=LEGACY_CHECKPOINT` 并能完成。不要使用
workflow 37 做 Legacy 回归，不要修改任何 asset source_type。

## 14. 日志安全复核

部署本轮修复后，重新启动一次 V1 Validation，再读取 Java 与 service-python 日志：

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-python
```

检查要求：

- Java V1 outbound body 只包含 workflow/job、validation mode、asset、Definition/hash
  前缀、runtime 和安全路径摘要，不包含 `trustedModelDefinition.definitionPayload`。
- `callbackSecret` 的明文值不得出现；V1 摘要中不记录该字段。
- `password`、token、API/AES/private/access key 等 credential 不得明文出现。
- `Authorization`、`Cookie`、`Set-Cookie` 等 header 如被记录，其值必须是 `******`。
- service-python 完成日志使用 `metricsFallbackUsed` / `metricsFallbackReason`，不再使用
  含糊的 `fallback=True`。

`metricsFallbackUsed=true` 且 reason 为 `labels_missing_estimated_metrics` 只表示 Runtime
没有读到 YOLO labels，因而显示估算 metrics；它不表示 V1 fallback 到 Legacy
checkpoint loader。此时 annotated sample 仍是实际 inference 结果。

## 15. 失败与回滚

若 V1 Validation 失败：

1. 保留 global weights、四份 aggregation sidecar 和 result/log 证据。
2. 将 `.env.local-server` 的 `WEIGHTS_VALIDATION_V1_ENABLED` 改回 `false`。
3. 仅执行：

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d service-java service-python
```

Legacy 业务仍可运行，不需要回滚数据库，不要执行 `down -v`。
