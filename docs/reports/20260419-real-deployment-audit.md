# 面向真实部署的业务改造审计报告

## 1. 本次审计结论

本轮改造的核心目标是把 `workflow-platform` 从“开发机路径直读 + 临时调试思维”收口到“浏览器上传 / 服务器路径导入 / 服务端资产存储 / 多模式部署”的统一架构。

当前已完成的主改造包括：

- 模型资产与数据集资产统一改为服务端资产化存储。
- 浏览器上传成为默认主路径，管理员专用的服务器路径导入被单独收口。
- Java 后端、前端页面、Python 独立验证阶段文案同步切换到服务端资产语义。
- 存储目录、运行模式、服务器路径导入根目录与映射规则均已配置化。
- 模型 / 数据集的 `source_path / source_type / import_mode` 元数据已补齐。

## 2. 审计中发现的主要问题

| 模块 | 原问题 | 不适合真实部署的原因 | 本次改造 |
| --- | --- | --- | --- |
| 模型管理 | 前端要求填写任意“本地路径”，后端直接 `File.exists()` 校验 | 浏览器用户机器路径并不等于服务器路径 | 增加浏览器上传接口；服务器路径导入仅对管理员开放 |
| 数据集管理 | 数据集目录路径直接由页面输入并在后端扫描 | 部署后服务器无法读取浏览器侧任意目录 | 数据集上传统一为服务端 zip 接收并解压，服务器导入统一复制到资产根目录 |
| 工作流模型接收 | 已有解密后纳管能力，但模型资产元数据不足 | 无法明确来源类型和纳管模式 | 解密纳管模型补齐 `source_type=WORKFLOW_DECRYPT` 等元数据 |
| 联邦学习聚合产物 | 聚合模型落盘后虽入库，但缺少统一来源标记 | 资产追踪和审计链不完整 | 聚合产物登记为 `FEDERATED_OUTPUT` 类型模型资产 |
| 存储目录 | 路径配置分散，目录语义不完整 | local-server / docker / cloud 切换成本高 | 新增 `runtime-mode`、`temp-dir`、`dataset-root-path`、服务器路径导入配置 |
| 前端语义 | 菜单、页面、独立验证页仍大量使用“本地模型/本地数据集” | 容易误导浏览器用户以为服务器可直接读自己电脑路径 | 菜单、概览、模型页、数据集页、验证工作台改为“资产”语义 |

## 3. 本次落地的架构方案

### 3.1 统一资产生命周期

1. 浏览器上传或管理员服务器路径导入。
2. 文件复制到服务端统一存储根目录。
3. 创建资产记录并写入来源元数据。
4. 工作流 / 验证 / 联邦学习只消费资产 ID 与服务端路径。
5. 验证结果样例通过缓存接口对外展示，而不是依赖浏览器猜测路径。

### 3.2 统一存储目录语义

- `WORKFLOW_STORAGE_ROOT`
- `WORKFLOW_TEMP_DIR`
- `WORKFLOW_MODEL_UPLOAD_DIR`
- `WORKFLOW_MODEL_ROOT`
- `WORKFLOW_DATASET_ROOT`
- `WORKFLOW_VALIDATION_CACHE_ROOT`
- `WORKFLOW_FEDERATED_MODEL_ROOT`
- `PYTHON_VALIDATION_OUTPUT_ROOT`
- `PYTHON_VALIDATION_SAMPLE_ROOT`

### 3.3 多运行模式

- `local-dev`
- `local-server`
- `docker-local`
- `cloud-prod`

其中：

- `local-server / docker-local` 可启用服务器路径导入。
- `cloud-prod` 默认应关闭服务器路径导入，仅保留浏览器上传和已存在资产选择。

## 4. 数据库变更摘要

本次新增并依赖以下字段：

- `model_asset.source_path`
- `model_asset.source_type`
- `model_asset.import_mode`
- `dataset_asset.source_path`
- `dataset_asset.source_type`
- `dataset_asset.import_mode`

并扩展以下字段长度：

- `model_asset.file_path` -> `varchar(500)`
- `dataset_asset.file_path` -> `varchar(500)`
- `workflow.result_file_path` -> `varchar(500)`

对应 SQL 已写入：

- `docs/sql/20260419_asset_deployment_refactor.sql`

## 5. 当前仍需注意的风险

### 5.1 历史数据兼容

历史的模型 / 数据集资产记录会被标记为 `LEGACY_PATH` 和 `LEGACY`。这类记录仍可能指向只在开发机可访问的旧路径，需要管理员逐步重新上传或重新导入。

### 5.2 服务器路径导入的边界

服务器路径导入已经受角色和根目录限制，但仍依赖部署侧正确配置：

- `WORKFLOW_SERVER_PATH_IMPORT_ROOTS`
- `WORKFLOW_SERVER_PATH_MAPPINGS`
- `WORKFLOW_SERVER_PATH_IMPORT_ROLES`

若 Docker 挂载映射配置错误，导入仍会失败，但错误会明确归类为路径范围或路径映射问题。

### 5.3 云部署建议

云端部署时建议默认：

- `WORKFLOW_SERVER_PATH_IMPORT_ENABLED=false`
- 仅开放浏览器上传
- 明确设置 `APP_ALLOWED_ORIGINS` / `APP_ALLOWED_ORIGIN_PATTERNS`
- 为对象存储或共享卷预留后续扩展接口

## 6. 验收建议

1. 执行 `docs/sql/20260419_asset_deployment_refactor.sql`。
2. 检查 `.env.local-server` 或生产环境变量中的存储路径与运行模式配置。
3. 以客户端账号测试浏览器上传模型和数据集。
4. 以服务端账号测试服务器路径导入。
5. 发起独立验证与工作流验证，确认只选择资产、不再依赖浏览器路径。
