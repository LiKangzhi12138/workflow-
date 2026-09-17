# Stage 1B Engineering Report

## 1. 结论

Stage 1B engineering = **PASS**。

本阶段完成 ModelDefinition 注册表设计、可信 RuntimeProfile Registry、真实 YOLOv8n 羊群检测 Definition、完整性校验、Runtime 能力探针、architecture-only 构建自检、fail-closed availability 判定、Python 内部检查接口和 Java 可用模型查询接口。

没有执行 SQL、启动 Docker、修改前端、上传、FedAvg、Validation 或 Python 依赖。

## 2. 数据库设计

新增手工 SQL 设计：

- model_definition：保存服务器可信模型结构定义，不保存基础 checkpoint。
- workflow.model_definition_id BIGINT NULL：可空绑定字段，兼容历史工作流。
- code 唯一约束，以及 status、runtime profile、family/variant 索引。
- 不增加强制外键，先由应用层校验。
- 不回填历史 workflow，不根据旧 yolo_version 猜测 Definition。

SQL 文件为 docs/weights-protocol/STAGE1B_SQL.sql，顶部明确标记 MANUAL EXECUTION ONLY 和 DO NOT AUTO EXECUTE。

用户手工执行顺序：

1. 创建 model_definition 表。
2. 给 workflow 增加 nullable model_definition_id。
3. 创建 workflow 绑定索引。
4. 幂等插入 YOLOV8N_SHEEP_V1。

更新后的 Java Workflow Entity 已包含该字段。因此部署更新后的 Java 前，必须先由用户手工执行 Stage 1B SQL；本轮未执行数据库操作。

## 3. 真实 YOLO ModelDefinition

| 字段 | 值 |
| --- | --- |
| code | YOLOV8N_SHEEP_V1 |
| displayName | YOLOv8n 羊群检测 |
| modelFamily | YOLO |
| version | YOLOv8 |
| variant | yolov8n |
| taskType | DETECTION |
| framework | Ultralytics |
| frameworkVersion | 8.4.41 |
| classes | sheep |
| runtimeProfileId | YOLO_RUNTIME_V1 |
| parameterSemantics | EMA_SNAPSHOT |
| architectureSignature | 2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a |
| definitionSha256 | 302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2 |

Architecture 直接来自只读实验 handoff，不是重新手写的 yolov8n.yaml。代码重新计算 architecture signature 和 canonical Definition SHA；测试证明 JSON serialize/deserialize、类别顺序和两个签名稳定。

ModelDefinition 中不存在 base_model_path、pretrained_model_path、checkpoint_path 或同义字段。

## 4. RuntimeProfile Registry

可信代码 Registry 当前注册 YOLO_RUNTIME_V1：

- runtimeType：MODEL_RUNTIME
- adapterType：YOLO
- supportedModelFamilies：YOLO
- Python：3.11
- Ultralytics：严格 8.4.41
- PyTorch：必须可导入
- CUDA/GPU：必须真实可用
- healthCheckType：RUNTIME_AND_DEFINITION_SELF_CHECK

RuntimeProfile 描述运行能力，不包含模型 checkpoint 路径。该逻辑抽象以后可迁移到独立 YOLO runtime service，而不改变 workflow 的 ModelDefinition 语义。

## 5. Runtime Probe

探针真实采集 probe context、Python、PyTorch、Ultralytics、CUDA 和 GPU name。

CONTAINER_RUNTIME 才能形成正式 runtime ready 结论；HOST_DEV 只提供诊断并返回 NOT_EVALUATED。

本轮 Codex Host 实测：

- context：HOST_DEV
- Python：3.13.11
- PyTorch：2.12.0+cpu
- Ultralytics：不可导入
- CUDA：false
- final availability：NOT_EVALUATED

生产 Docker runtime 未启动、未检测，因此 production availability = NOT_EVALUATED。

当前 service-python/requirements.txt 仍是 Ultralytics 8.3.0，与 Definition 要求的 8.4.41 不一致。本轮按约束未修改依赖；以后正式启用 Registry 前必须单独对齐，否则探针会 fail closed。

## 6. YOLO Definition Self-check

Self-check 仅调用 DetectionModel(cfg=architecture_dict, ch=3, nc=1, verbose=False)，随后验证 model type、class count、names、stride 和 architecture signature。它不读取 yolov8n.pt、best.pt、客户端 weights 或 global weights。

只读实验环境真实执行：

- Python 3.10.21
- Ultralytics 8.4.41
- PyTorch 2.7.1+cu128
- CUDA true
- model type DetectionModel
- class count 1
- names 为 sheep
- stride 为 8、16、32
- architecture signature 与可信值一致
- checkpoint used false

实验 handoff SHA256 执行前后均为 a3621a59eba3c75cc18d2029bb983eb3f81842d13d383a781b6b4ec853dc589f，实验产物未修改。

## 7. Availability 语义

最终 AVAILABLE 必须同时满足：

1. Definition status 为 ENABLED。
2. Definition schema、canonical SHA、architecture signature、class contract 有效。
3. Definition family/framework/version 与可信 RuntimeProfile 一致。
4. RuntimeProfile 存在且启用。
5. 对应 adapter/self-check capability 已注册。
6. 正式 runtime probe 已评估并 ready。
7. CUDA/GPU 等 profile 要求满足。
8. architecture-only build self-check 通过。

任一失败均不可用；Host 诊断或正式 runtime 未运行返回 NOT_EVALUATED。ENABLED 不直接等价于 AVAILABLE。

| 检查 | 当前状态 |
| --- | --- |
| Definition enabled | true |
| Definition valid | true |
| Adapter/self-check registered | true |
| Host diagnostics collected | true |
| Production runtime evaluated | false |
| Production runtime ready | unknown |
| Real build in verified lab env | PASS |
| Final production availability | NOT_EVALUATED |

## 8. API

Python POST /internal/model-definitions/check：

- 输入完整可信 ModelDefinition 和 runtimeProfileId。
- 输出 ModelAvailabilityResult。
- MODEL_DEFINITION_REGISTRY_V1_ENABLED=false 时返回 404。

Java GET /api/model-definitions/available：

- Java 读取 ENABLED 记录，但不会直接返回。
- 校验 registry 列与 definition_json 的基础一致性。
- 每条 Definition 调用 Python availability。
- 只有 Python 明确返回 available=true 才进入结果。
- Python 失败、空响应、NOT_EVALUATED 或任意不可用原因均 fail closed。
- 响应不包含大体积 architecture/definition payload。

MODEL_DEFINITION_REGISTRY_V1_ENABLED 默认 false，旧业务不读取新 Registry。

## 9. 测试

Python 全量测试：48 passed，1 skipped，1 warning。

覆盖 Definition roundtrip、稳定 hash/signature、SHA/architecture/class order 篡改、RuntimeProfile 缺失、Definition 与 Runtime framework version 不一致、实际 runtime version 不一致、CUDA unavailable、disabled、build failure、Host NOT_EVALUATED、全部条件满足才 AVAILABLE，以及 Stage 1A Inspector 回归。

Java：

- JDK 21 隔离目录完整源码编译通过。
- 定向测试 6 passed，0 failed，0 errors。
- 覆盖 available filtering、Python unavailable 排除、Python 调用失败 fail closed、flag disabled、内部 endpoint 请求和 HTTP 失败映射。

仓库既有 target/classes/application.properties 权限锁定会阻塞普通 Maven lifecycle。本轮未清理 target，而是使用 JDK 21 隔离编译目录和 Surefire 无文件报告模式验证。

## 10. 兼容性边界

- 旧 workflow create 未修改，modelDefinitionId 当前不必填。
- 旧 model_type、task_type、yolo_version 保留。
- 不自动把历史 workflow 绑定到新 Definition。
- 模型上传、AES、FedAvg、global model、Validation 未修改。
- 前端未修改，旧 YOLO 版本选项暂时保留。
- Docker 未修改、未启动。
- Python requirements 未修改。
- 服务器未新增 yolov8n.pt 或 InternImage pretrained checkpoint 管理。

## 11. Stage 1C 建议

Stage 1C 只推进：

1. 前端读取真实 available ModelDefinition。
2. workflow create 提交并绑定 modelDefinitionId。
3. 服务器从可信 Definition 派生 modelFamily/version/taskType/classes/framework。

进入 Stage 1C 前，应由用户手工执行 Stage 1B SQL，并在正式 runtime 中对齐 Ultralytics 8.4.41 后验证 production availability。

