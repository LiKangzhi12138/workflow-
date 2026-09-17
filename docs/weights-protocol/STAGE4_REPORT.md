# Stage 4: FEDERATED_WEIGHTS_V1 Validation Report

## 1. Stage 4 Code Readiness

**FINAL PASS**.

代码、配置、协议边界、定向测试和全量回归已完成。用户已使用 workflow 37 / asset
77 完成真实 Docker Validation，严格重建、推理、签名 callback 和 CLIENT/SERVER
结果页均通过。日志安全问题已在本轮修复并由自动测试覆盖。

## 2. Legacy Validation 原链

```text
SERVER 点击启动验证
  -> POST /api/workflows/{id}/start-python-job
  -> WorkflowController -> WorkflowServiceImpl.startPythonJob
  -> WorkflowFederatedAggregationService 解析 FEDERATED_OUTPUT
  -> Java POST service-python /internal/jobs
  -> task_runner 后台线程
  -> YoloValidationService
  -> ultralytics.YOLO(full checkpoint)
  -> metrics + annotated samples
  -> service-python 写 {jobId}_result.json
  -> service-python 发送签名 callback
  -> Java 更新 workflow 状态、metricsJson、resultFilePath
  -> CLIENT / SERVER Validation 页面读取 GET /api/workflows/{id}
```

Legacy 的受信任 checkpoint loader 和旧 `FEDERATED_OUTPUT` 路由原样保留。

## 3. V1 Validation 新链

```text
用户显式点击启动验证
  -> Java 识别 Definition workflow + WEIGHTS_FEDAVG_V1
  -> 校验 WEIGHTS_VALIDATION_V1_ENABLED
  -> workflow.model_definition_id 重新加载可信 Definition
  -> Definition ENABLED + AVAILABLE 再检查
  -> FEDERATED_WEIGHTS_V1 正式资产及四份 sidecar 完整性检查
  -> 重算 global_weights.pt SHA256
  -> existing /internal/jobs async job (validationMode=WEIGHTS_PROTOCOL_V1)
  -> service-python Safe Inspector + generic runtime dispatch
  -> YOLO_RUNTIME_V1 /internal/runtime/validate-weights-v1
  -> Definition architecture-only DetectionModel
  -> torch.load(..., weights_only=True)
  -> load_state_dict(..., strict=True)
  -> CUDA inference/validation + sample rendering
  -> service-python 规范化 result.json + validationEvidence
  -> existing signed callback -> Java -> existing UI
```

## 4. Java Legacy / V1 判定

V1 必须同时具备：

- `workflow.model_definition_id != null`
- `workflow.federated_strategy = WEIGHTS_FEDAVG_V1`
- `workflow.federated_status = COMPLETED`
- `workflow.federated_model_asset_id != null`

前两项决定进入 V1 preparation；后两项由 preparation fail closed 校验。其余工作流
继续 Legacy 路径。V1 preparation 失败不会 fallback 到 Legacy。

## 5. V1 Global Asset 条件

资产必须是未删除的：

```text
source_type       = FEDERATED_WEIGHTS_V1
import_mode       = WEIGHTS_PROTOCOL_V1
record_mode       = FORMAL_ASSET
status            = READY
last_check_status = OK
```

文件必须位于受控 federated model root，名称为 `global_weights.pt`，不是符号链接，
且同目录必须存在：

- `aggregation-manifest.json`
- `descriptor.json`
- `inspection-report.json`
- `aggregation-report.json`

Java 交叉验证四份证据的 Definition、architecture、SHA、tensor/element count、key/
shape/dtype signature，并重新计算文件 SHA。

## 6. Trusted Definition

唯一信任根为 `workflow.model_definition_id`。Java 按数据库 PK 读取 ENABLED
ModelDefinition，验证 canonical definition，并执行真实 availability 检查。随后使用
可信 `definition_json.definitionId/code`、`definitionSha256` 和
`architectureSignature` 与 sidecar 比较。

数据库 PK（例如 `1`）不会与协议 ID（`YOLOV8N_SHEEP_V1`）直接比较。

## 7. service-python 与 YOLO 依赖

**Stage 4 V1 dispatcher 不包含、也不导入 Ultralytics。** YOLO-specific 代码仅在
`service-yolo-runtime/app/weights_validation.py` 和复用的
`definition_check.py` 中。

旧 `service-python` 为兼容 Legacy Validation 仍保留原有 Ultralytics 8.3.0 依赖和
Legacy YOLO service；Stage 4 没有新增、升级或让 V1 使用这份依赖。

## 8. Checkpoint 边界

- 需要 `yolov8n.pt`：**NO**
- 需要 base/pretrained checkpoint：**NO**
- V1 读取 full checkpoint：**NO**
- V1 `torch.load`：全部为 `weights_only=True`
- unsafe fallback：**NO**
- V1 进入 Legacy unsafe loader：**NO**

代码搜索发现的 `weights_only=False`、`strict=False`、`YOLO(...)` 仅位于未修改的
Legacy 聚合/验证代码；V1 使用独立 validation mode 和 runtime endpoint。

## 9. Architecture-only Rebuild 与 Strict Load

YOLO Runtime 复用 Stage 1B.5 builder：

```python
DetectionModel(
    cfg=trusted_definition["definitionPayload"]["architecture"],
    ch=trusted_definition["inputSpec"]["channels"],
    nc=trusted_definition["classCount"],
)
```

设置可信 class names 后安全读取 `{"state_dict": OrderedDict[...]}`，再执行
`model.load_state_dict(state_dict, strict=True)`。missing/unexpected key 必须均为 0；
key、shape、dtype evidence 或 finite 检查不满足都会拒绝。

Stage 3 将 client float16 按冻结规则聚合为 global float32。Stage 4 以 global asset
自己的 dtype signature 为证据；float32 state 可严格加载到构建模型，不错误要求它
等于客户端 float16 signature。

## 10. Runtime Validation

Runtime 在模型构建前再次验证：

- trusted Definition schema/hash/signature 和 `YOLO_RUNTIME_V1`
- global weights SHA256
- tensor count、element count、ordered key hash
- shape signature、global dtype signature
- wrapper、Tensor 类型和 NaN/Inf
- CUDA/runtime readiness

成功证据明确包含：

```text
validationMode       = WEIGHTS_PROTOCOL_V1
weightsOnly          = true
checkpointUsed       = false
baseCheckpointUsed   = false
reconstructionMode   = DEFINITION_PLUS_STATE_DICT
strictLoad           = true
missingKeyCount      = 0
unexpectedKeyCount   = 0
validationCompleted  = true
```

## 11. Dataset、Metrics 与结果

Validation 继续使用 `workflow.server_dataset_asset_id -> DatasetAsset.filePath`，没有
新增 Dataset 模块。YOLO Runtime 负责图片发现、YOLO label 读取、模型特定预处理、
推理、metrics 和 annotated image 绘制。

YOLO Runtime 返回标准化 runtime response；`service-python` 将 base64 sample 安全
写入既有 validation sample root，并生成现有 `{jobId}_result.json`。结果继续包含
accuracy、precision、recall、map50、map50_95、sampleResults、predictionCount、
predictions 和 annotatedImagePath。`validationEvidence` 作为附加审计证据保存。

`service-python` 继续发送现有签名 callback。`jobType`、`workflowId`、
`standaloneValidationId`、`jobId`、status、metrics、resultFile 合同保持兼容；Java
既有 callback 幂等与终态保护不变。CLIENT/SERVER 页面无需 Stage 4 改动。

## 12. Validation 状态与启动方式

Validation **不会自动启动**。Stage 3 完成只产生 `FEDERATED_WEIGHTS_V1` 并停在
联邦学习完成；SERVER 用户必须显式点击启动验证。

job 创建后沿用 `PREPARING/VALIDATING -> COMPLETED/FAILED`。Python 成功/失败通过
原 callback 更新 Java；重复或过期 callback 按现有幂等规则忽略。

## 13. Feature Flag

```text
WEIGHTS_VALIDATION_V1_ENABLED=false
```

Java 与 Python 默认均为 `false`。关闭时，V1 workflow 明确返回
`WEIGHTS_VALIDATION_V1_DISABLED`，不会降级到 Legacy checkpoint Validation。

## 14. Stage 3 日志语义小修复

`WorkflowAcceptAutoManageAsyncService` 在 V1 聚合完成后重新读取 workflow；只有
V1 strategy、`federated_status=COMPLETED` 且 global asset 已关联时，日志中的
`federatedTriggered` 才为 true。它不再仅代表 Legacy trigger。该修改没有改变 Stage 3
状态机或聚合实现，并有独立回归测试。

## 15. 数据库结论

`DB Change Required = NO`。现有 workflow、model_asset、Python job/result 和 callback
字段已足够表达路由、状态、global asset、结果和错误；扩展 evidence 保存在
`result.json`。未生成 Stage 4 migration，未执行任何 SQL，也未修改数据库。

## 16. Tests

| Layer | Result |
| --- | --- |
| Java Stage 3 + Stage 4 + logging security targeted | `45 passed, 0 failures, 0 errors, 0 skipped` |
| Java full regression, JDK 21 isolated storage/temp | `139 total, 0 failures, 0 errors, 2 skipped` |
| service-python Stage 4 relevant regression (current) | `10 passed` |
| service-python full regression | `79 passed, 1 skipped, 3 subtests passed` |
| yolo-runtime full regression (current) | `17 passed, 3 subtests passed` |
| Frontend targeted | `26 passed` |
| `vue-tsc` | PASS |
| Vite production build | PASS |
| Compose `config --quiet` | PASS |
| `git diff --check` | PASS |

YOLO Runtime tests覆盖 Definition/architecture/SHA mismatch、safe loader、
`weights_only=True`、strict load、missing/extra/shape mismatch、NaN、inference、sample
render 和 checkpoint=false evidence。service-python tests覆盖 request schema、Safe
Inspector、identity/SHA evidence、runtime unavailable、normalized response、async job
success/failure 和 callback identity。

`service-python` 全量收集仍会触发既有 `test_pressure.py` 线程访问未启动的
`127.0.0.1:8000`，产生 warning，但不导致失败；Stage 4 生产代码未为此改动。

## 17. Manual Docker Acceptance

真实人工验收已经 PASS：

| Evidence | Actual |
| --- | --- |
| workflow / global asset | `37 / 77` |
| jobId | `88d6fe36b62c48259d24e44f20a78500` |
| validationMode | `WEIGHTS_PROTOCOL_V1` |
| trusted DB ModelDefinition PK | `1` |
| trusted protocol definitionId | `YOLOV8N_SHEEP_V1` |
| runtimeProfileId | `YOLO_RUNTIME_V1` |
| Runtime endpoint | `POST /internal/runtime/validate-weights-v1 -> 200 OK` |
| callback | signed, `COMPLETED`, progress `100` |
| persisted result | result path and metrics persisted |
| pages | CLIENT/SERVER result available |
| displayed metrics | `accuracy=0.906`, `map50=0.906` |
| samples | `1`, annotated image available |

该链路证明 `FEDERATED_WEIGHTS_V1 -> trusted ModelDefinition -> service-python async job ->
YOLO Runtime -> strict load -> inference -> signed callback -> existing UI` 已真实运行。

## 18. Manual Acceptance Bug / Security Finding

症状：`RestTemplateConfig` 的 `pythonJsonRestTemplate` logging interceptor 在 INFO 级别
打印原始 request headers、完整 `/internal/jobs` JSON 和 response body，导致
`callbackSecret` 明文进入日志，并打印完整 YOLO architecture。

修复：新增通用 `SensitiveHttpLogSanitizer`，递归脱敏 JSON 中名称包含 password、secret、
token、apiKey、authorization、cookie、AES/private/access key 语义的字段；同时脱敏
`Authorization`、`Cookie`、`Set-Cookie`、`X-Api-Key` 等 headers，移除 URI query。
无法解析的非 JSON body 不再原样记录。V1 `/internal/jobs` 的 INFO body 改为固定摘要，
仅保留 workflow/job/validation mode、asset、Definition/SHA/signature 前缀、runtime、
weights 和 dataset 路径，不再打印 `trustedModelDefinition.definitionPayload`。

脱敏只作用于 logging copy。自动测试逐字节确认实际 HTTP request body 仍包含原始
`callbackSecret`，所以 callback secret、签名算法和验证流程均未改变。`PythonJobClient`
和 `PythonFederatedClient` 的独立成功/错误 body 日志也复用同一脱敏策略；可能夹带
远端 body 的异常 stack trace 不再写入这些日志点。

## 19. `fallback=True` 审计

该字段的真实来源是 YOLO Runtime 的 metrics 分支：当 dataset 图片可推理，但没有读到
任何可用 YOLO label 时，Runtime 使用检测置信度和检测数量生成估算指标，并返回
`fallbackReason=labels_missing_estimated_metrics`。标注图仍由真实模型预测绘制，日志中的
`renderedImageMode=SOURCE_IMAGE_PREDICTIONS` 与该 metrics fallback 是两个独立概念。

日志字段已重命名为 `metricsFallbackUsed` 和 `metricsFallbackReason`。这绝不表示 V1
进入 Legacy checkpoint Validation；本次人工证据中的 YOLO Runtime V1 endpoint 200、
`weightsOnly=true`、`checkpointUsed=false` 和 strict-load evidence 均证明不存在这种
fallback。由于本次为 metrics fallback，页面显示的 `accuracy/map50=0.906` 应理解为
无标签时的估算值，不应当作 ground-truth mAP。

## 20. validationEvidence

生产 Runtime 会生成且 service-python 会原样持久化以下证据：

```text
validationMode=WEIGHTS_PROTOCOL_V1
modelDefinitionId=YOLOV8N_SHEEP_V1
globalWeightsAssetId=77
globalWeightsSha256=<server-verified SHA256>
runtimeProfileId=YOLO_RUNTIME_V1
weightsOnly=true
checkpointUsed=false
baseCheckpointUsed=false
reconstructionMode=DEFINITION_PLUS_STATE_DICT
strictLoad=true
missingKeyCount=0
unexpectedKeyCount=0
validationCompleted=true
```

YOLO Runtime 回归测试现在逐项断言这些字段；测试不读取用户 runtime result 文件。

## 21. Stage 4 修改文件

配置与协议：

- `.env.example`
- `docker-compose.yml`
- `docs/weights-protocol/PROTOCOL_V1.md`
- `service-java/src/main/resources/application.yml`

Java：

- `config/WeightsValidationProperties.java`
- `dto/python/PythonCreateJobRequest.java`
- `dto/python/PythonWeightsValidationJobInput.java`
- `service/impl/WorkflowWeightsValidationPreparationService.java`
- `service/impl/WorkflowServiceImpl.java`
- `service/impl/WorkflowFederatedAggregationService.java`
- `service/impl/WorkflowAcceptAutoManageAsyncService.java`
- `service/python/PythonJobClient.java`
- `service/python/PythonFederatedClient.java`
- `config/RestTemplateConfig.java`
- `config/SensitiveHttpLogSanitizer.java`
- `test/.../RestTemplateConfigLoggingSecurityTest.java`
- `test/.../WorkflowWeightsValidationPreparationServiceTest.java`
- `test/.../WorkflowServiceImplStartPythonJobTest.java`
- `test/.../WorkflowFederatedAggregationServiceAvailabilityTest.java`
- `test/.../WorkflowAcceptAutoManageAsyncServiceStage3LogTest.java`
- `test/.../WorkflowServiceImplCallbackTest.java`
- `test/.../WorkflowServiceImplAdvanceWorkflowTest.java`
- `test/.../WorkflowServiceImplCreateWorkflowTest.java`
- `test/.../WorkflowServiceImplReadModelDefinitionTest.java`

service-python：

- `app/models/request_models.py`
- `app/schemas/model_protocol.py`
- `app/core/config.py`
- `app/api/jobs.py`
- `app/services/yolo_runtime_client.py`
- `app/services/weights_validation_v1_service.py`
- `app/services/result_service.py`
- `app/workers/task_runner.py`
- `tests/test_weights_validation_v1.py`

yolo-runtime：

- `app/definition_check.py`
- `app/schemas.py`
- `app/main.py`
- `app/weights_validation.py`
- `tests/test_runtime.py`

文档：

- `docs/weights-protocol/STAGE4_REPORT.md`
- `docs/weights-protocol/STAGE4_MANUAL_TEST.md`

## 22. 执行边界

- Docker：用户已完成真实人工验收；Codex 本轮**未执行** Docker（只运行 Compose 静态解析）
- SQL：**未执行**
- 数据库：**未修改**
- Legacy Validation：保留
- InternImage：未实现
- Stage 5：未实施

## 23. 结论

Stage 4 的代码、自动回归和真实 Docker 人工 Validation 均已完成；人工发现的日志凭据
泄漏也已完成通用修复。部署本轮安全修复只需重新构建 Java；`fallback` 日志名修正若也
需要进入运行环境，则同时重新构建 service-python。不进入 Stage 5。

Windows CMD：

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server --profile model-runtime build service-java service-python
docker compose --env-file .env.local-server --profile model-runtime up -d service-java service-python
```
