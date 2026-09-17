# Stage 3 Manual Test (Windows CMD)

本验收只验证 weights-only FedAvg 和 global weights 正式资产。不要启动
Validation，不要执行 Stage 4，不要使用 `docker compose down -v`。

## 0. Manual Acceptance Bug #1 修复部署

本修复只修改 `service-java`。不要复用已进入 FAILED 的 workflow 36，也不要
修改其数据库记录。Windows CMD 中仅重建并更新 Java 服务：

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build service-java
docker compose --env-file .env.local-server --profile model-runtime up -d service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 120 service-java
```

确认 Java healthy 后，按本文后续步骤创建全新的 Definition workflow。修复后的
日志会在身份不匹配时分别显示数据库 PK、可信协议 ID，以及 manifest/descriptor
的 ID、SHA/architecture 前缀；正常 Golden A/B 不应产生该 warning。

## 1. 备份配置

打开 Windows CMD：

```cmd
cd /d D:\workspace\workflow-platform
copy /Y .env.local-server .env.local-server.stage3.backup
notepad .env.local-server
```

保留现有密码和路径，不要把文件内容发到聊天或日志。确认已有：

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
WEIGHTS_PROTOCOL_V1_ENABLED=true
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
```

先将新开关设置为 false，用于验证“检查完成但尚未启动”的状态：

```text
WEIGHTS_FEDAVG_V1_ENABLED=false
```

代码默认值仍是 false。

## 2. 静态检查、构建和启动

Stage 3 没有修改 yolo-runtime。构建 Java、协议 Python 和包含状态文案的
gateway：

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime up -d service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime ps
```

确认 mysql、service-java、service-python、yolo-runtime、gateway 均 healthy/
running。不要执行 down，也不要删除 volume。

## 3. 准备 Golden A/B Package

以下工具只安全读取已冻结的 weights-only 文件，不解析 full checkpoint：

```cmd
python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt --definition service-python\app\model_definitions\YOLOV8N_SHEEP_V1.json --output-dir .runtime\stage3-manual-client-a --created-by stage3-manual-client-a
python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\yolo\artifacts\client_b\weights_only.pt --definition service-python\app\model_definitions\YOLOV8N_SHEEP_V1.json --output-dir .runtime\stage3-manual-client-b --created-by stage3-manual-client-b
certutil -hashfile .runtime\stage3-manual-client-a\weights.pt SHA256
certutil -hashfile .runtime\stage3-manual-client-b\weights.pt SHA256
```

期望：

```text
Client A  aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
Client B  5db9946f132b65665f5276071882cb9c452a4ee39caafaaccc265090c0da9486
```

每个目录必须只有本次上传需要的 `weights.pt`、`manifest.json`、
`descriptor.json`。

## 4. 验证等待聚合状态

1. 保持 `WEIGHTS_FEDAVG_V1_ENABLED=false`。
2. 在 CLIENT UI 创建一个新的、required model count 为 1 的 Definition
   workflow，选择服务器 AVAILABLE 的 `YOLO / YOLOv8`。
3. 上传 client A 的三个 package 文件。
4. SERVER 接收该 workflow，并对上传执行现有接收/解密操作。
5. 打开 CLIENT 与 SERVER progress 页面。

必须显示：

```text
权重检查完成，等待联邦聚合
```

不得显示：

```text
联邦学习聚合中
```

此等待样例不会因稍后仅切换环境变量而自动触发；正式聚合请使用下一节的
新 workflow。

## 5. 启用 Stage 3

```cmd
notepad .env.local-server
```

只把这一项改为：

```text
WEIGHTS_FEDAVG_V1_ENABLED=true
```

让 Java 和 Python 容器重新读取环境，无需重新 build：

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d service-python service-java
docker compose --env-file .env.local-server --profile model-runtime ps
```

## 6. 执行两客户端 Golden FedAvg

1. 新建一个 required model count 为 2 的 Definition workflow。
2. 确认 `model_definition_id=1`，不要使用 Legacy workflow。
3. 按现有 CLIENT 上传流程分别上传 client A 和 client B package。
4. SERVER 接收 workflow，并依次解密两个上传。
5. 第一个权重完成时不得聚合；第二个达到 required count 后才允许触发。

触发调用是同步内部 HTTP。`联邦学习聚合中` 可能很短暂，最终必须显示：

```text
联邦学习聚合完成
```

不得自动创建 Validation job，也不要点击“启动验证”。

## 7. 查看安全日志

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-python
```

应看到：

```text
Weights FedAvg v1 completed
inputCount=2
globalAssetId=<id>
```

日志不应出现 AES key、完整 Tensor 或完整 state_dict key dump。

## 8. 数据库只读验证

不要把密码写在命令中：

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -u root -p
```

仅执行以下 SELECT，并替换 `<workflowId>`：

```sql
SELECT id, workflow_code, model_definition_id, expected_model_count,
       federated_status, federated_strategy, federated_model_asset_id,
       federated_started_at, federated_finished_at, current_step,
       python_job_id, error_message
FROM workflow
WHERE id = <workflowId>;

SELECT id, workflow_id, server_model_asset_id, upload_status,
       aggregation_status, file_sha256, error_message
FROM workflow_model_upload
WHERE workflow_id = <workflowId>
ORDER BY id;

SELECT id, asset_code, source_type, import_mode, record_mode,
       file_path, file_size, status, last_check_status, last_check_message,
       is_deleted
FROM model_asset
WHERE id = (
  SELECT federated_model_asset_id FROM workflow WHERE id = <workflowId>
);
```

期望：

```text
workflow.model_definition_id      = 1
workflow.federated_status         = COMPLETED
workflow.federated_strategy       = WEIGHTS_FEDAVG_V1
workflow.python_job_id            = NULL
两条 input aggregation_status     = COMPLETED
global source_type                = FEDERATED_WEIGHTS_V1
global import_mode                = WEIGHTS_PROTOCOL_V1
global record_mode                = FORMAL_ASSET
global status                     = READY
global last_check_status          = OK
```

退出：

```sql
exit
```

## 9. 文件与 SHA 验证

从上一步 `model_asset.file_path` 得到宿主机对应目录。默认 local-server 布局：

```cmd
dir /s .runtime\local-server\storage\federated-models\global_weights.pt
dir /s .runtime\local-server\storage\federated-models\aggregation-manifest.json
dir /s .runtime\local-server\storage\federated-models\descriptor.json
dir /s .runtime\local-server\storage\federated-models\inspection-report.json
dir /s .runtime\local-server\storage\federated-models\aggregation-report.json
```

在找到的同一 UUID 目录中执行：

```cmd
certutil -hashfile <full-host-path-to-global_weights.pt> SHA256
type <full-host-path-to-inspection-report.json>
type <full-host-path-to-aggregation-report.json>
```

要求 inspection `passed=true`、355 tensors、3,021,500 elements、NaN/+Inf/-Inf
均为 0；报告 input count 为 2，权重为 `[0.5, 0.5]`。文件 byte SHA 不要求
等于 lab 文件 SHA，Tensor contract 才是冻结等价依据。

## 10. 代表性失败验证

自动测试已覆盖所有恶意/不兼容输入。人工只需抽查以下两项，且必须使用新
workflow 和 package 副本，不要编辑 Golden 原件：

1. 将一个副本 Manifest 的 Definition ID 改错，上传后必须拒绝且不产生 global
   asset。
2. 将一个正式输入 weights 文件替换/改动会导致 SHA tamper 拒绝。不要修改用户
   已有 asset 69；如需做此项，仅使用专门创建的临时测试 workflow/asset。

失败后 workflow 应为 `FAILED` 或安全的可重试状态，绝不能长期停留在
`联邦学习聚合中`。

## 11. Legacy 与页面回归

分别打开：

```text
CLIENT Workflow Management
SERVER Workflow Management
CLIENT Workflow Progress
SERVER Workflow Progress
```

检查一个 Legacy workflow 和一个 Definition workflow。Legacy upload/FedAvg
仍按原路径工作；Stage 1C UI 仍只显示最少必要模型信息，不展示 classes、
framework、runtimeProfile 或 architectureSignature。

## 12. 验收通过条件

Stage 3 人工验收只有在以下条件全部满足时通过：

```text
等待状态语义正确
required count 达到后仅触发一次
global_weights.pt 为 weights-only wrapper
global Safe Inspector PASS
global FEDERATED_WEIGHTS_V1 正式资产 READY/OK
输入和 global sidecars 齐全
没有 yolov8n.pt/base checkpoint/full checkpoint 依赖
没有自动 Validation
Legacy 与四个 Workflow 页面无回归
```
