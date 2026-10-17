# CLIENT 工作流模型资产与上传就绪修复

## 审查结论

| 检查点 | 代码事实 | 结论 |
| --- | --- | --- |
| 新建 Definition 工作流的资产 ID | [CreateWorkflowRequest.java](/D:/workspace/workflow-platform/service-java/src/main/java/com/workflow/dto/workflow/CreateWorkflowRequest.java:19) 允许资产 ID 为空；[WorkflowServiceImpl.java](/D:/workspace/workflow-platform/service-java/src/main/java/com/workflow/service/impl/WorkflowServiceImpl.java:111) 仅旧版要求该字段，`:133` 保存资产，`:134` 保存可信 Definition。 | 创建时无资产是有意设计，不代表上传初始化可以不传资产。无需恢复创建页面资产选择。 |
| 上传阶段资产来源 | 进度页先请求当前用户 `/models` 全页列表，再填充选项；[workflowUploadSelection.ts](/D:/workspace/workflow-platform/frontend-web/src/utils/workflowUploadSelection.ts:34) 的 WEIGHTS_V1 规则只返回当前选择值，不回退到 workflow 上的资产 ID。 | CLIENT B 不会因共享工作流的 `clientModelAssetId` 自动使用 A 的资产。 |
| 前端与后端资产资格差异 | 进度页之前只保留 `filePathValidated === 1`。Java [WorkflowModelUploadServiceImpl.java](/D:/workspace/workflow-platform/service-java/src/main/java/com/workflow/service/impl/WorkflowModelUploadServiceImpl.java:973) 对当前用户自己的 `PATH_REGISTRY + CLIENT_PATH_REGISTRY` 资产直接接受；其他资产则要求 `status=READY` 且 `filePathValidated=1`。 | 前端过滤比后端更窄，是已确认的资格规则不一致。路径登记资产被排除时无法在上传页选择，即使 Java 接受该资产。 |
| API 数据权限 | [ModelAssetServiceImpl.java](/D:/workspace/workflow-platform/service-java/src/main/java/com/workflow/service/impl/ModelAssetServiceImpl.java:398) 按当前用户 ID、当前用户角色和未删除过滤，并按更新时间、ID 倒序。 | 列表来源已经按当前用户隔离；没有放宽跨用户读取。实际账号是否有资产只能由真实 API/DB 返回确认，本任务未读取生产 DB。 |
| Definition 与资产关系 | ModelAsset 列表没有 `modelDefinitionId`，资产的 `modelType/modelVersion/taskType` 也不构成平台可信 Definition 绑定。weights 包 Manifest/Descriptor 才会由服务端按工作流 Definition 校验。 | 不能按“只有一条资产”就自动绑定为兼容资产；那可能把另一模型的资产挂到上传记录。上传阶段保留明确选择。 |

## 诊断码含义

`MODEL_ASSET_NOT_SELECTED` 表示上传使用的模型资产 ID 为空。新建 Definition 工作流没有预绑定资产时，这是预期初始值，用户需在上传阶段选择自己的资产。

`MODEL_ASSET_NOT_AVAILABLE` 表示已解析出非空 ID，但该 ID 在当前用户当前可上传资产列表中找不到。原实现通过严格的 `asset.id === selectedId` 和只筛 `filePathValidated === 1` 判定；因此记录模式不匹配前端过滤条件，或 ID 字符串/数字类型不同，都会被误判为不可用。代码能确定这些失败路径；由于本任务无法访问用户运行中的浏览器或数据库，不能断言本次页面实际数据属于其中哪一种。

## 修复方式

1. 新增 `isUploadableClientModelAsset`，把 Java 的资格分支映射到前端列表：精确的客户端路径登记记录（`PATH_REGISTRY` + `CLIENT_PATH_REGISTRY`），或正式资产（`READY` 且路径校验为 1）。模型列表接口已经按当前用户过滤，服务端初始化仍再次检查归属、角色、状态与工作流权限。
2. 新增规范 ID 查找。下拉值即使以数字字符串到达，也会在当前用户可上传资产列表中解析为规范资产记录；发起 WEIGHTS_V1 初始化时使用该记录返回的数值 ID。未知或不可用 ID 仍 fail closed。
3. 当账号资产列表成功加载但没有可上传资产时，显示 `MODEL_ASSETS_NONE_AVAILABLE` 和“先到模型管理登记或校验”的提示；资产列表请求失败与“账号没有资产”分别提示。
4. 两个上传入口共用资格和 ID 查找规则。进度页仍按当前用户的显式 V1 选择上传；历史绑定 ID 只在它确实属于当前用户可上传列表时预选。工作流切换仍清空临时选择。
5. 上传按钮、点击前复核和请求中的 `modelAssetId` 仍来自同一就绪结果/当前资产。weights、Manifest、Descriptor、可信 Definition 和名额校验未改变。

没有恢复创建时的默认资产绑定。唯一一条“可上传资产”不等于已证明它与工作流 Definition 兼容，也不等于它就是用户准备上传的那份权重。ModelAsset 当前没有可信 Definition 外键，实际包兼容性依赖服务器对 Manifest/Descriptor/Weights 的 V1 校验。因此上传阶段仍需选定实际客户端资产；若账号没有资产，应先通过模型管理登记或上传正式客户端资产。不会虚构 ID，也不会把 A 的资产默认给 B。

## 上传链路与多客户端隔离

`workflow.clientModelAssetId` 是单一的历史/当前工作流资产字段，不表示所有客户端各自的资产。每次 `initUpload` 都要求 `modelAssetId`；Java 以当前用户自己的资产校验后，将该 ID 写入对应 `workflow_model_upload` 上传记录。V1 前端选择不回退到共享 workflow 字段，因此 B 用自己的列表和选择值提交自己的资产 ID。Java 仍检查上传者、资产所有权/客户端角色、工作流状态和剩余槽位；Weights Protocol 的可信 Definition 兼容检查仍在既有接收/检查链路完成。

`modelAssetId` 不是 `modelDefinitionId`，资产名称/文件名也不决定 Runtime。YOLO 与 InternImage 都由工作流的可信 Definition 和上传包 Descriptor/Manifest 决定协议兼容性。本次没有变更 Java、SQL、数据库 Schema、创建接口、加密、FedAvg、Global Weights 或两个 Runtime。

## 修改文件

- `frontend-web/src/utils/workflowUploadSelection.ts`：添加与 Java 上传策略一致的资产可用性判断和规范 ID 查找；保留 V1 只用当前客户端选择值的规则。
- `frontend-web/src/utils/workflowUploadReadiness.ts`：区分资产列表加载失败、无资产、未选择资产和已选择 ID 不在可用列表。
- `frontend-web/src/views/client/ClientWorkflowProgressView.vue`：使用上传资格过滤、当前用户资产解析和规范 ID 发起上传；改善空列表文案。
- `frontend-web/src/views/client/ClientWorkflowManageView.vue`：保持旧管理弹窗的资产规则与进度页一致。
- `frontend-web/tests/workflowUploadSelection.test.ts`：覆盖客户端路径登记/正式资产资格、字符串/数字 ID 及 A/B 资产隔离。
- `frontend-web/tests/workflowUploadReadiness.test.ts`：覆盖无资产、未选择、无效选择和完整权重包的就绪情况。
- `frontend-web/tests/workflowCreateAssetSelection.test.ts`：更新两个入口资产过滤、解析与初始化请求契约检查。
- `frontend-web/package.json`：纳入上传就绪测试。
- 本报告。

## 自动测试

| 命令 | 结果 |
| --- | --- |
| `npm run test:model-definition` | PASS，64/64。资产资格和响应式就绪逻辑为纯逻辑测试；组件入口、服务请求字段为源码契约测试。 |
| `npm run type-check` | PASS，`vue-tsc --build`。 |
| `npm run build-only` | PASS，Vite 构建完成；存在既有大 chunk 警告。 |
| Java 测试 | 未运行：Java 未修改。服务端资格分支通过源码审查。 |
| `git diff --check` | PASS；Git 只报告工作区 LF/CRLF 转换提示，无空白错误。 |
| Vue DOM 实测、API 实际资产列表、真实上传初始化、多客户端接收 | 未运行；当前环境没有用户运行页面、生产数据库或容器访问，也未安装 DOM 测试依赖。 |

64 项通过不能证明当前账号存在兼容资产，也不能代替真实上传。若新页面仍显示无资产提示，需在当前 CLIENT 账号的模型管理页登记其资产；若选项已有而仍显示 `MODEL_ASSET_NOT_AVAILABLE`，请查看新版 readiness data code 和模型列表响应的 `id/recordMode/sourceType/status/filePathValidated` 字段（不要发权重、令牌或敏感路径）。

## Windows CMD 部署

本次只改前端与测试，更新 `gateway` 即可；无需重建 Java、service-python、YOLO Runtime 或 InternImage Runtime。以下命令不清理卷或业务数据：

```cmd
cd /d D:\workspace\workflow-platform
git diff --check
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build gateway
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps gateway
docker compose --env-file .env.local-server --profile model-runtime ps gateway
```

部署后浏览器使用 `Ctrl+F5`。生产 Java 必须已经包含允许 Definition 工作流在创建时不预绑定 `clientModelAssetId` 的那次版本；本轮没有改 Java。

## 浏览器验收

1. 用 CLIENT A 登录，在模型管理确认自己的模型记录存在。路径登记资产或 `READY + filePathValidated=1` 的正式资产应出现在当前账号列表。新建 YOLO、InternImage Definition 工作流时资产字段保持隐藏/空值。
2. 进入新工作流进度页。唯一或多个候选都由上传阶段资产下拉选择；没有资产时页面应明确提示登记，不应出现占位资产。选定资产后检查 weights、Manifest、Descriptor 和剩余名额齐全，按钮启用。
3. DevTools Network 检查 `GET /api/models` 返回当前用户自己的有效记录；点击上传后检查 `POST /api/workflow-uploads/init` 的 `modelAssetId` 与所选资产一致。确认后续加密文件传输成功、上传记录进入服务端现有流程。
4. 用 CLIENT B 打开允许参与的同一工作流，确认下拉列表来自 B 自己的资产；选择 B 的资产上传，核对初始化请求未复用 A 的资产 ID。达到工作流名额后，额外上传仍由前后端拒绝。
5. 分别用 YOLO 与 InternImage 的真实 Manifest/Descriptor/Weights 包完成既有兼容性检查；错误资产权限、Definition 不兼容、缺文件和无名额请求仍应失败。此项及后续 FedAvg/Runtime 验收需用户真实部署环境完成。

未执行 Docker、SQL、数据库写入、数据删除、FedAvg 或真实 GPU 验证；没有修改 Java 后端。
