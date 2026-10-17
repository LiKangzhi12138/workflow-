# YOLO 分数展示与客户端工作流创建精简

## 审查结论

| 需求 | 原有实现与证据 | 处理 |
| --- | --- | --- |
| YOLO 高分展示 | `frontend-web/src/utils/validationResultPresentation.ts` 已从 `map50`、`map50_95`、`precision`、`recall`、`accuracy` 中选最高有效值；汇总值无效时才读当前 Job 的检测置信度。原先 `91%..<100%` 保留原值，`100%` 映射到 `99.01%..99.99%`。 | 只修改展示映射的 `>95%` 分支。 |
| 创建时的“客户端模型” | `ClientWorkflowManageView.vue` 把 `clientModelAssetId` 设为必选；`CreateWorkflowRequest` 的 `@NotNull` 和 `WorkflowServiceImpl.createWorkflow` 要求现有资产。它不是 `modelDefinitionId`，`ModelAssetItem` 也没有可信 Definition ID，可用资产无法从 Definition 唯一、安全地自动推导。 | Definition 工作流创建时不预绑定资产；旧版工作流仍须选择。 |
| 上传所需资产 | `WorkflowModelUploadServiceImpl.initUpload` 仍要求合法 `modelAssetId`，校验所有权/状态，并在初始化时同步 `workflow.clientModelAssetId`。管理页上传弹窗已有资产选择；进度页原先只能读取工作流预绑定资产。 | 管理页上传按钮要求先选资产；进度页在尚未绑定时提供资产选择。上传协议与服务端校验不变。 |
| 数据库 | `docs/sql/000_init_workflow_platform_schema.sql` 中 `workflow.client_model_asset_id` 为 `bigint DEFAULT NULL`，`workflow.model_definition_id` 独立保存可信身份。 | 无 schema 或数据变更。 |

创建请求的 `clientModelAssetId` 现在为可选；仅在注册表已启用、服务端成功解析可信 Definition 时允许为空。旧版模式继续拒绝空资产，显式传入的资产仍受原有 owner/删除校验。新建 Definition 工作流的资产字段保持空值直至实际上传初始化，不用 YOLO 占位资产冒充 InternImage，也不从客户端文件名推断身份。上传协议仍由工作流的 trusted Definition 决定，Manifest、Descriptor、Weights 校验未改。

## YOLO 展示规则

| 基础候选值 | 旧展示 | 新展示 |
| --- | --- | --- |
| `<=90%` | 稳定 `90.01%..92.99%` | 不变 |
| `>90%` 且 `<91%` | 稳定 `91.01%..91.99%` | 不变 |
| `>=91%` 且 `<=95%` | 原值两位小数 | 不变，`95.00%` 原样保留 |
| `>95%` 且 `<=100%` | 原值或接近 100% 的映射 | 同一 workflow/Job 稳定 `95.01%..95.99%` |

映射继续用工作流、Job、候选字段及原值构造稳定种子，不使用渲染时随机数。有效原始指标、来源与 `basisPercent` 不变；映射仅存在于 Vue 显示层，不是 GT Accuracy，也不写入 API、Java DTO、数据库或 result JSON。InternImage 仍按真实 `GROUND_TRUTH` 的最高有效 per-class Accuracy 显示，完全不经过 YOLO 映射。

## 修改文件

- `frontend-web/src/utils/validationResultPresentation.ts`：仅调整 YOLO `>95%` 展示区间。
- `frontend-web/tests/validationResultPresentation.test.ts`：边界、稳定性、Job 隔离、原始指标不变。
- `frontend-web/src/views/client/ClientWorkflowManageView.vue`：Definition 创建时隐藏客户端资产控件；旧版保留；上传弹窗须选资产。
- `frontend-web/src/views/client/ClientWorkflowProgressView.vue`：尚未绑定资产的新工作流可在上传阶段选择资产；切换工作流清理选择并隔离迟到列表响应。
- `frontend-web/src/api/workflow.ts`：创建请求的资产 ID 改为可选，其他 API 不变。
- `frontend-web/tests/workflowCreateAssetSelection.test.ts`、`frontend-web/package.json`：创建与上传边界的静态组件契约测试。
- `service-java/src/main/java/com/workflow/dto/workflow/CreateWorkflowRequest.java`、`service-java/src/main/java/com/workflow/service/impl/WorkflowServiceImpl.java`：Definition 创建允许空预绑定，旧版仍拒绝，已有资产仍验权。
- `service-java/src/test/java/com/workflow/service/impl/WorkflowServiceImplCreateWorkflowTest.java`：YOLO/InternImage Definition 空资产创建及旧版拒绝测试。
- 本报告。

## 自动检查

| 命令 | 结果 |
| --- | --- |
| `npm run test:model-definition` | PASS，53/53；其中创建表单/进度页测试是静态组件契约测试，未代替浏览器上传验收。 |
| `npm run type-check` | PASS。 |
| `npm run build-only` | PASS；既有大 chunk 提示。 |
| JDK 21 `mvn -o -q test-compile` | PASS，生产及测试源码编译。 |
| JDK 21 `mvn -o -q -Dtest=WorkflowServiceImplCreateWorkflowTest test` | **未执行到测试断言**：本机离线 Maven 缓存缺少 `maven-surefire-plugin:3.5.3` 的依赖；按要求未安装依赖。在线普通命令还曾遇到 `.m2` 目录访问拒绝。Java 定向用例已添加，但不能记为 PASS。 |
| `git diff --check` | PASS；仅有 Git 的 CRLF 提示，无 whitespace error。 |
| Docker/真实上传/FedAvg/GPU | 未执行，待用户人工验收。 |

## Windows CMD 部署

本轮需更新 `service-java` 与 `gateway`，不需要重建 service-python、YOLO 或 InternImage Runtime。先确保 `.env.local-server` 与现有数据卷配置不变；下列命令不会删除数据库或卷。

```bat
cd /d D:\workspace\workflow-platform
git diff --check
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build service-java gateway
docker compose --env-file .env.local-server up -d --no-deps service-java
docker compose --env-file .env.local-server ps service-java
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps service-java gateway
```

待 `service-java` 健康后再更新 gateway。浏览器硬刷新或用无痕窗口检查新静态资源。部署时还应在有完整 Maven 依赖缓存的环境运行 `WorkflowServiceImplCreateWorkflowTest`；若失败，不应视为创建链已验收。

## 浏览器验收与剩余风险

1. 打开已有 YOLO 成功工作流：基础值 `>95%` 时显示 `95.01%..95.99%`，刷新稳定；`95.00%` 原样显示。原图、结果图和样例切换正常。
2. 打开已有 InternImage 成功工作流：原有最高有效类别 Accuracy、原图和分割叠加图不变。
3. CLIENT 新建 YOLO 与 InternImage Definition 工作流：表单没有“客户端模型”，Definition 正确，SERVER 可见；创建后进度页在上传阶段选择自己的已校验模型资产，提交 weights、Manifest、Descriptor，服务端按 trusted Definition 校验。管理页上传弹窗也须选择资产。旧版注册表关闭时仍需选择客户端资产。
4. 检查新建工作流首次上传前资产 ID 可为空，首次上传初始化后绑定真实资产；进度页轮询及切换工作流不串资产。继续核对 SERVER 接收、FedAvg、验证、重试及两个结果页。自动测试不等于这两条完整业务的真实验收。
5. 检查历史工作流、资产、数据集、结果和账号均完整保留。

本轮没有清空数据库、执行 SQL、改 schema、删除正式资产、运行 Docker、重新执行 FedAvg 或执行真实 GPU 验证。
