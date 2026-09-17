# Stage 3 Weights-only FedAvg Report

## 1. Stage 3 Code Readiness

PASS. Code, configuration, static checks, Golden tensor comparison, targeted tests,
and the JDK 21 Java full regression suite are complete. Docker runtime acceptance
remains a user-operated step.

## 2. Legacy FedAvg Chain

```text
ACCEPTED workflow
  -> WorkflowAcceptAutoManageAsyncService / SERVER decrypt action
  -> encrypted legacy checkpoint is downloaded/read and decrypted
  -> WORKFLOW_DECRYPT ModelAsset is registered
  -> WorkflowFederatedAggregationService checks requiredModelCount readiness
  -> Java calls service-python POST /internal/federated/aggregate
  -> legacy Python service loads trusted full checkpoints (legacy weights_only=False path)
  -> YOLO-specific checkpoint FedAvg creates a legacy global checkpoint
  -> Java registers FEDERATED_OUTPUT and updates workflow/upload aggregation state
  -> existing validation path can later consume that checkpoint
```

`expectedModelCount` is preferred for readiness, with the existing client count
fallback and minimum value 1. Legacy behavior, including one-client aggregation,
was not changed.

## 3. New Weights-only FedAvg Chain

```text
Definition workflow + both V1 flags enabled
  -> required INSPECTED uploads are present
  -> Java atomically claims PENDING/FAILED as STARTING
  -> Java validates each formal WORKFLOW_WEIGHTS_V1 asset and persisted SHA
  -> Java validates trusted Definition/architecture sidecars
  -> Java transitions STARTING to RUNNING
  -> POST /internal/federated/aggregate-weights-v1
  -> service-python reruns Safe Inspector and weights_only=True load
  -> exact Definition, architecture, key, shape, and dtype contracts
  -> type-safe equal FedAvg
  -> global_weights.pt plus server evidence sidecars
  -> Safe Inspector validates the output
  -> Java validates response identity, paths, evidence, and output SHA
  -> formal FEDERATED_WEIGHTS_V1 asset registration
  -> workflow and input aggregation statuses become COMPLETED
```

## 4. V1 Input Asset Type

Only `WORKFLOW_WEIGHTS_V1` with `WEIGHTS_PROTOCOL_V1`, `FORMAL_ASSET`, `READY`,
`last_check_status=OK`, validated path, and a `COMPLETED/INSPECTED` upload may
participate.

## 5. Full Checkpoint Read

否。V1 聚合不读取 full checkpoint，且不会进入 Legacy loader。

## 6. yolov8n.pt Requirement

否。

## 7. Base Checkpoint Requirement

否。

## 8. torch.load Safety

Stage 3 所有输入和 Golden/输出复核均使用：

```python
torch.load(path, map_location="cpu", weights_only=True)
```

## 9. Unsafe Fallback

否。V1 无 `weights_only=False` fallback。旧受信任 checkpoint loader 仅保留在
隔离的 Legacy 服务中。

## 10. Aggregation Engine

通用引擎位于
`service-python/app/services/weights_fedavg_v1_service.py`，内部入口为
`POST /internal/federated/aggregate-weights-v1`。它只理解有序 Tensor state，
不构建 YOLO 模型。

## 11. Ultralytics In service-python

否。Stage 3 没有给 `service-python` 引入 Ultralytics。

## 12. Type-safe FedAvg Rules

规则直接复用 weights lab 的冻结语义：

| Input | Accumulator | Output | Rule |
| --- | --- | --- | --- |
| `float16` | `float32` | `float32` | 按服务器权重加权平均 |
| `float32` | `float64` | `float32` | 按服务器权重加权平均后 cast |
| `int64` | 无 | `int64` | `REQUIRE_IDENTICAL_AND_COPY` |

Protocol v1 当前不允许其他 dtype。任一非浮点 Tensor 在客户端间不完全相同
即拒绝；不会转浮点、平均或 round。

## 13. Aggregation Weight Source

当前无服务器可信 sample count，因此采用服务器生成的等权 `1/N`。
客户端 Manifest 的 `aggregationContribution` 是不可信声明，不参与计算。

## 14. ModelDefinition Validation

Java 从 `workflow.model_definition_id` 查询 ENABLED Definition，并检查每个
资产 sidecar 的 Definition ID/SHA。Python 再对完整可信 Definition 执行
canonical integrity validation，并逐输入比较 Definition reference。

## 15. Architecture Validation

每个输入 Manifest 的 `architectureSignature` 必须等于可信 Definition，所有
输入必须一致。当前 YOLO 值为
`2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a`。

## 16. Keys, Shapes, And Dtypes

Safe Inspector 重新计算 ordered key hash、shape signature 和 dtype signature；
聚合器还会从实际安全加载的 state_dict 逐 key 比较顺序、shape 和 dtype。
缺失、额外、换序、shape 或 dtype 不一致全部 fail closed。

## 17. Post-upload Tamper Protection

Java 先以正式文件重新计算 SHA256 并与 `workflow_model_upload.file_sha256`
比较；Python 再计算一次并与服务器请求证据及 Manifest 比较，然后重跑完整
Safe Inspector。任何文件或 sidecar 替换都会拒绝聚合。

## 18. Duplicate Trigger Protection

`WorkflowMapper.claimWeightsFedAvgV1` 使用带状态条件的原子 UPDATE，将
`PENDING/FAILED` 转为 `STARTING`。只有影响行数为 1 的调用可继续；重复
asset ID 也在 Java/Python 两侧拒绝。

## 19. Concurrency

并发保护依赖数据库 CAS，而不是进程内 `synchronized`，因此适用于多 Java
实例。`STARTING -> RUNNING -> COMPLETED/FAILED` 每步都有策略和状态条件。
当前内部 Python API 是同步调用：HTTP 调用开始前进入 RUNNING，成功响应后
完成，异常或证据不匹配立即进入 FAILED。

## 20. Global Weights Path

```text
${WORKFLOW_FEDERATED_MODEL_ROOT}/{workflowCode}/weights-v1/{uuid}/global_weights.pt
```

local-server 默认宿主机对应
`.runtime/local-server/storage/federated-models/...`。

## 21. Global Weights Format

weights-only：

```python
{"state_dict": OrderedDict[str, torch.Tensor]}
```

文件名固定为 `global_weights.pt`，不是 global checkpoint。

## 22. Global Asset Source, Import, And Status

```text
source_type       = FEDERATED_WEIGHTS_V1
import_mode       = WEIGHTS_PROTOCOL_V1
record_mode       = FORMAL_ASSET
status            = READY
last_check_status = OK
```

## 23. Global Sidecars

同目录持久化：`aggregation-manifest.json`、`descriptor.json`、
`inspection-report.json`、`aggregation-report.json`。报告包含 workflow/
Definition/architecture、输入资产与 SHA、等权贡献、输出 SHA、state signatures、
dtype counts 及冻结 dtype policy，均为服务器生成证据。

## 24. Global Output Safe Inspector

保存后重新执行 Stage 1A Safe Inspector，验证 wrapper、SHA/size、keys、shapes、
dtypes、finite 和全部资源限制。只有 `inspection.passed=true` 才返回 Java；
Java还要求该证据为 true 并再次计算输出 SHA。

## 25. YOLO Model Reconstruction

否。

## 26. Validation Trigger

否。V1 聚合成功只产生 global weights asset，不创建 Python validation job。

## 27. Legacy Validation Change

否。

## 28. InternImage Implementation

否。聚合器是通用 Tensor 引擎，但没有新增 InternImage Runtime/Definition。

## 29. Legacy FedAvg Compatibility

是。Legacy workflow 或 Definition workflow 在 weights protocol flag 关闭时仍走
原 checkpoint 路径；旧聚合代码和 loader 均未删除或改写。

## 30. federatedTriggered=false State Bug

是，已修复。V1 权重完成检查但未实际触发时，current step 为
`权重检查完成，等待联邦聚合`，不再显示聚合中。

## 31. When Aggregating May Be Displayed

只有数据库 CAS 已成功占有 `STARTING`，输入完整性全部通过，并且 Java 正要
发出真实 Python 聚合请求且成功转为 `RUNNING` 后，才显示
`联邦学习聚合中`。调用失败会立即转为 `FAILED`。

## 32. Feature Flag

新增 `WEIGHTS_FEDAVG_V1_ENABLED`，Java/Python 默认均为 `false`。只有它和
`WEIGHTS_PROTOCOL_V1_ENABLED` 同时为 true 才启用新聚合；关闭时 V1 资产停在
INSPECTED/等待聚合。

## 33. DB Change Required

NO。现有 workflow、workflow_model_upload、model_asset 字段可表达 CAS 生命周期、
输入状态、global asset 关联、错误和正式资产证据；完整协议记录放 sidecar。

## 34. SQL Execution

否。未生成或执行 Stage 3 SQL。

## 35. Golden FedAvg Test

| Item | SHA256 / result |
| --- | --- |
| Client A | `aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c` |
| Client B | `5db9946f132b65665f5276071882cb9c452a4ee39caafaaccc265090c0da9486` |
| Frozen expected file | `47d309b0e9274acfeefdcd116044d5f65bb6eb60a53f3025a7676c4cb9cd86ad` |
| Stage 3 actual file | `b701ffeced34e9c30ec82d81c5131d2a0b83c5c479adae8e52eb1bc41452cb28` |
| Semantic comparison | 355/355 tensors exact (`torch.equal`) |

两文件 byte SHA 不同是 PyTorch ZIP serialization container bytes 不稳定所致，
不是 Tensor 差异。正确验收依据为 ordered keys、dtype、shape 和逐 Tensor 精确
相等。Actual 输出为 355 tensors、3,021,500 elements、298 float32 + 57 int64，
NaN/+Inf/-Inf 均为 0。

## 36. Single-client Test

PASS。单客户端值保持 identity；float16 依冻结 global policy 转为 float32，
int64 精确复制。

## 37. Negative Tests

PASS：Legacy/mixed asset、Definition mismatch、architecture mismatch、missing/
extra/reordered key、shape mismatch、dtype mismatch、int64 value mismatch、SHA
tamper、NaN、Inf、unsupported dtype、resource limit、path escape、duplicate input、
insufficient input、duplicate trigger、Python failure、Python response identity/
evidence mismatch 均被拒绝。

## 38. Java Tests

JDK 21.0.10 隔离目录定向测试：14 tests，0 failures，0 errors，0 skipped。
其中聚合编排及身份回归 12 项、状态语义 2 项。同步 Python 成功/失败响应覆盖了本架构中
callback success/failure 的等价完成语义。

### Java Full Regression Analysis

首次全量运行共 123 tests，出现 7 failures、1 error、2 skipped。逐项读取
Surefire XML 后的分类和处理如下：

| Test | Failure | Root Cause | Stage 3 Related? | Action |
| --- | --- | --- | --- | --- |
| `WorkflowServiceImplCallbackTest.shouldHandleDuplicateCallbackIdempotentlyWithoutInsertingStep` | callback signature mismatch | 测试签名夹具遗漏当前合同已有的 `jobType`、`standaloneValidationId` | 测试夹具过期 | 仅同步测试请求和签名 payload |
| `WorkflowServiceImplCallbackTest.shouldRefreshDuplicateCompletedCallbackWithoutInsertingStep` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `WorkflowServiceImplCallbackTest.shouldAdvanceToCompletedAndInsertStep` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `WorkflowServiceImplCallbackTest.shouldIgnoreOtherTerminalCallbackAfterCompleted` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `WorkflowServiceImplCallbackTest.shouldAdvanceToFailedAndInsertStep` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `WorkflowServiceImplCallbackTest.shouldIgnoreOutdatedCallbackDuringRunningStates` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `WorkflowServiceImplCallbackTest.shouldIgnoreOutdatedCallbackAfterCompleted` | callback signature mismatch | 同上 | 测试夹具过期 | 同上 |
| `ServiceJavaApplicationTests.contextLoads` | `AccessDeniedException` in `DeploymentStartupRunner` | Windows 将容器默认 `/opt/...` 解析为 `D:\\opt`；测试进程无该路径写权限 | 环境基线 | 全量测试显式设置隔离 `WORKFLOW_STORAGE_ROOT`，不改生产路径逻辑 |

7 个 callback failure 没有修改生产签名验证，仅更新测试夹具，并更新冻结签名。
修复首次全量回归问题后，在 JDK 21.0.10 和隔离 storage/build 目录下的结果为
123 tests、0 failures、0 errors、2 skipped。人工验收 Bug #1 增加 4 个身份负向
用例后再次执行完整回归，最终结果为：

```text
total    = 127
passed   = 125
failures = 0
errors   = 0
skipped  = 2
```

未解释的 Stage 3 regression：0。

### Stage 3 Manual Acceptance Bug #1

**症状：** workflow 36 已绑定数据库 `model_definition.id=1`，资产 73/74 的
Stage 2A 检查均通过，但 Java 在调用 Python FedAvg 前抛出
`WEIGHTS_FEDAVG_DEFINITION_MISMATCH`。

**真实比较：** 原 `validateTrustedIdentity()` 将
`String.valueOf(definition.getId())`（值为 `"1"`，来源是数据库主键）与
`manifest.modelDefinition.definitionId`、
`descriptor.modelDefinition.definitionId`（值均为
`"YOLOV8N_SHEEP_V1"`，来源是 Protocol v1 sidecar）比较。两者 ID 空间不同，
因此合法资产必然失败。`validatePythonResponse()` 还存在同源的潜在错误：它会
用数据库主键 `"1"` 比较 Python 返回的协议 Definition ID。

**根因：** Stage 3 Java 编排混用了数据库 ModelDefinition PK 和 Protocol v1
Definition identity。`ModelAsset` 实体不存在 `model_definition_id`，Stage 2A
也不需要新增该字段；V1 身份根始终是
`workflow.model_definition_id -> server-side ModelDefinition -> trusted
definition_json`，资产身份由正式目录内的服务器持久化 sidecar 交叉验证。

**为什么原测试未发现：** 原测试把 manifest、descriptor 和 Python mock response
的协议 ID 都错误写成数字 `1`，与生产 Bug 使用了相同的错误数据形态，没有覆盖
真实的 `DB PK=1 / protocol definitionId=YOLOV8N_SHEEP_V1`。

**修复：** Java 从已完成注册表字段校验的可信 `definition_json.definitionId`
取得协议 ID；数据库 `code` 仍独立与可信 JSON `code` 校验。manifest 和 descriptor
的 Definition ID/SHA、manifest architecture、descriptor
`extensions.architectureSignature`、persisted inspection `passed` 均保持 fail
closed。Python 响应也使用协议 ID 和 Definition code 双重反向校验。Mismatch
日志新增 workflow/upload/asset、数据库 PK、协议 ID、各来源 SHA/architecture
前 12 位及 evidence source，不输出 Definition payload、Tensor 或密钥。

**新增回归覆盖：** 真实 ID 形态正向 PASS；wrong protocol Definition ID、wrong
Definition SHA、wrong descriptor Definition、manifest/descriptor identity
disagreement、wrong architecture signature 均在调用 Python 前 REJECT。

**影响边界：** 未修改 Stage 2A、Python FedAvg、Legacy FedAvg、数据库或前端。
不需要 DB migration，workflow 36 的失败记录也未被修改；人工复测应创建全新
Definition workflow。

## 39. Python Tests

完整 `service-python`：69 passed，1 skipped，3 subtests passed。Stage 3 定向
Golden 和负向测试全通过；compileall PASS。全量收集仍会触发既有
`test_pressure.py` 访问未启动 `127.0.0.1:8000` 的 11 条线程 warning，不影响
退出码和断言结果。

## 40. Frontend Tests

状态定向测试包含“INSPECTED/PENDING 显示等待”和“RUNNING 才显示聚合中”；
Frontend targeted tests 26/26 PASS，`vue-tsc` PASS，Vite production build PASS。
仅有既有 large chunk advisory。

## 41. Regression Tests

Stage 1A/1B/2A Python 全量测试通过；Legacy 分支仍由原测试覆盖。CLIENT/SERVER
management 与 progress 共用的 nullable Definition 映射未改变；Stage 3 仅新增
等待/运行/完成/失败状态展示。Java full suite 127 tests 中没有 failure/error；
Compose static config PASS。

## 42. Modified Files

Stage 3 涉及：

- `.env.example`
- `service-python/app/core/config.py`
- `service-python/app/main.py`
- `service-python/app/api/weights_federated.py`
- `service-python/app/schemas/model_protocol.py`
- `service-python/app/services/weights_package_inspector.py`
- `service-python/app/services/weights_fedavg_v1_service.py`
- `service-python/tests/test_weights_fedavg_v1.py`
- `service-java/src/main/java/com/workflow/config/WeightsFedAvgProperties.java`
- `service-java/src/main/java/com/workflow/dto/python/PythonWeightsFedAvgRequest.java`
- `service-java/src/main/java/com/workflow/dto/python/PythonWeightsFedAvgResponse.java`
- `service-java/src/main/java/com/workflow/service/python/PythonWeightsFedAvgClient.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowWeightsFederatedAggregationService.java`
- `service-java/src/main/java/com/workflow/mapper/WorkflowMapper.java`
- `service-java/src/main/java/com/workflow/service/ModelAssetService.java`
- `service-java/src/main/java/com/workflow/service/impl/ModelAssetServiceImpl.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowModelUploadServiceImpl.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowAutoManageStatusService.java`
- `service-java/src/main/java/com/workflow/support/AssetSourceCatalog.java`
- `service-java/src/main/java/com/workflow/support/WorkflowCurrentStepSupport.java`
- `service-java/src/main/resources/application.yml`
- `service-java/src/test/java/com/workflow/service/impl/WorkflowWeightsFederatedAggregationServiceTest.java`
- `service-java/src/test/java/com/workflow/service/impl/WorkflowAutoManageStatusServiceWeightsV1Test.java`
- `service-java/src/test/java/com/workflow/service/impl/WorkflowServiceImplCallbackTest.java`
- `frontend-web/src/constants/workflowProgress.ts`
- `frontend-web/src/views/client/ClientWorkflowProgressView.vue`
- `frontend-web/src/views/server/ServerWorkflowProgressView.vue`
- `frontend-web/tests/workflowProgress.test.ts`
- `frontend-web/package.json`
- `docs/weights-protocol/PROTOCOL_V1.md`
- `docs/weights-protocol/STAGE3_REPORT.md`
- `docs/weights-protocol/STAGE3_MANUAL_TEST.md`

## 43. Docker Execution

否。未 build、up、restart、down 或 exec；仅执行 Compose 静态解析，PASS。

## 44. STAGE3_REPORT Path

`D:\workspace\workflow-platform\docs\weights-protocol\STAGE3_REPORT.md`

## 45. STAGE3_MANUAL_TEST Path

`D:\workspace\workflow-platform\docs\weights-protocol\STAGE3_MANUAL_TEST.md`

## 46. User Manual Operations

按 `STAGE3_MANUAL_TEST.md` 使用 Windows CMD：备份 `.env.local-server`，显式打开
两个 V1 flag，静态解析并构建 `service-python/service-java/gateway`，创建新的
Definition workflow，上传 A/B 包，验证等待、RUNNING/COMPLETED、DB 只读记录、
global weights 与五个文件。不要运行 Validation，也不要使用 `down -v`。

## 47. Ready For Stage 3 Manual Test

可以。

## 48. Next Stage

仅建议：Stage 4，将 global weights 通过 ModelDefinition + YOLO Runtime 重建，
再正式切换 Validation。本阶段未实施 Stage 4。
