# Workflow UI Issues Audit and Fix

## 结论

本轮按四个现象检查了当前工作区源码、接口/权限路径、Compose 构建方式和自动化测试，没有改上传格式、加解密、descriptor/manifest 校验、FedAvg、Runtime 推理、Global Weights、Python Job/callback 或数据库。

源码已修正工作流列表顺序与进度侧栏分页、客户端上传协议的状态门控、列表/详情/结果请求的乱序覆盖。双模型结果适配已经存在于本地工作区，本轮核查并增加了结果页异步请求保护。**生产 Docker/数据库/浏览器当前不可读，因此不能断言线上 InternImage 空白已确认是旧容器或已在真实部署修复；必须按下方只读检查确认镜像和 API 响应。**

## 四项审查表

| 问题 | 页面/API | 根因及证据 | 修改前/后 | 旧容器判断、影响范围 |
|---|---|---|---|---|
| SERVER 管理与进度侧栏不一致 | 工作流管理和进度页都请求 `GET /api/workflows?pageNum&pageSize&status`；详情为 `GET /api/workflows/{id}`。证据：`WorkflowController.java:28-35`、两侧进度页 `ClientWorkflowProgressView.vue:995-1028` / `ServerWorkflowProgressView.vue:777-829`。 | `WorkflowServiceImpl.pageWorkflows`（`WorkflowServiceImpl.java:191-217`）使用同一角色/软删除条件：CLIENT 为本人发起或公开，SERVER 为分配给本人，`is_deleted=0`，状态只在显式传入时过滤；管理页分页，进度页原来通过 `listAllWorkflowsApi()` 收集全量并在前端另行排序。 | 进度侧栏现使用同一后端分页 API，每页 10/20/50；后端统一按持久化 `updated_at DESC, id DESC` 排序；切页、切工作流及管理列表请求均有序号保护。显式 URL 中的 workflowId 即使不在当前页仍直接请求详情，不会误选别的工作流；无权限由详情 API 拒绝。状态变化时回到最近页刷新侧栏。排序回归：`WorkflowServiceImplReadModelDefinitionTest.java:143-156`。 | 代码层已修；不能从当前工作区证明线上镜像是否旧。影响 Java 列表排序和 CLIENT/SERVER 进度/管理页，不放宽权限、不改合法状态过滤。 |
| InternImage 成功但结果空白 | `GET /api/validation-results/workflows/{workflowId}`，图片为 `/api/validation-results/workflows/{workflowId}/samples/{sampleIndex}/...`。证据：`ValidationResultController.java:34-58`、前端 API `validation.ts:184-186`。 | 上轮报告列出的适配当前均能在本地源码找到：Java 结果服务按 workflow 绑定的可信 ModelDefinition 映射 taskType/family（`ValidationResultServiceImpl.java:146-191`）；结果文件与当前 pythonJobId 交叉核验（`495-511`）；DTO 含 segmentation metrics 与 source/overlay/mask URL（`669-791`）；共用 Vue 结果卡按 taskType 展示（`ValidationResultCard.vue:84-125, 295-296`）。YOLO 用真实 `map50`，没有 mIoU；InternImage 只从 `GROUND_TRUTH` 的有效 per-class accuracy 选最高项（`validationResultPresentation.ts:9-39`）。此前无当前 route 请求序号保护，切换 workflow 时迟到响应可能覆盖新页面。 | 本轮在 CLIENT/SERVER 结果页增加 route/job 请求序号保护，旧请求不能覆盖新 workflow（`ClientValidationView.vue:147-208, 216-260`；SERVER 同结构）。缺图只影响对应图片块，结果卡可显示指标/状态。上轮适配本身并未重做。 | **真实线上根因未定**：旧 service-java、旧 gateway 静态资源、旧浏览器缓存、或该次 Job/result 数据均仍可能；当前无实时 API/DB/容器/浏览器证据。Java 和前端源码均需重建部署；service-python 与两个 Runtime 本轮无需更新。 |
| 已上传仍显示上传协议异常 | CLIENT 进度页读取 `GET /api/workflow-uploads/progress/{id}` 和 `GET /api/workflow-uploads/contract/{id}`。证据：`WorkflowModelUploadController.java:78-80, 93-97`。 | Controller 限 CLIENT；`WorkflowModelUploadServiceImpl.java:71-74, 533-539, 945-954` 只允许 CREATED/ACCEPTED，并在工作流离开上传状态后拒绝。进度页原来每次轮询都请求 contract，且将错误显示为“上传链路出现异常”，所以已进入接收/聚合/验证的工作流也触发错误。 | 现在先按 workflow status 和上传进度判断是否仍有上传槽位（`workflowProgressState.ts:11-34`、`ClientWorkflowProgressView.vue:1028-1060`）；已完成/不再允许上传时不再请求 contract，保留上传完成/后续阶段状态。协议读取失败独立于文件传输错误并做安全分类（`workflowProgressState.ts:36-52`）；未读到可信协议仍禁止启动上传，不放宽服务器校验。 | 源码根因确定并已修；不依赖更新 Java/Python，前端由 gateway 镜像提供，需重建 gateway。 |
| 左侧工作流列表过长 | CLIENT/SERVER 进度页侧栏。证据：`ClientWorkflowProgressView.vue:15-59, 995-1024`、`ServerWorkflowProgressView.vue:15-59, 777-824`。 | 管理表格原有分页，但进度页使用全量列表；全量量大时占满侧栏，也与管理页的页大小/排序来源不同。 | 两侧进度侧栏改为服务端分页，显示总数、页码及 10/20/50 页大小；删除造成页码越界时自动回退到有效页；当前选中 workflowId 与分页解耦。 | 源码已修；SERVER/CLIENT 共用同一个后端分页和授权合同。 |

## 代码审计细节

### 工作流列表

- `WorkflowController.pageWorkflows` 和两侧管理/进度页面都使用 `listWorkflowsApi`，没有另一个只供进度页使用的权限接口。
- `WorkflowServiceImpl.pageWorkflows` 保留 `is_deleted=0`。CLIENT 条件仍是 `initiator_user_id=currentUser` 或 `is_public=1`；SERVER 条件仍是 `server_user_id=currentUser`。可选 status 参数由管理页筛选传入，进度页不传 status，因此 COMPLETED、FAILED、运行中等合法状态不会被进度页过滤。
- 原差异是进度页调用全量分页聚合接口并客户端排序，管理页使用服务端分页；后端此前仅按创建时间排序。现在所有列表共用 `updated_at DESC, id DESC`，`updated_at` 表示持久化行最近更新时间，id 是稳定次序，不以验证时间或上传时间代替。
- 进度侧栏明确使用后端 `total` 和页码；管理页现有表格分页保持原样。删除后端页码越界会将当前页 clamp 到最后有效页。
- 新建/更新后的列表最近优先；进行中 workflow 状态变化时侧栏回到第一页刷新，但详情仍绑定此前 workflowId。

### 双模型结果链路

- 结果服务读取 workflow 当前 `pythonJobId`、`resultFilePath` 和持久化 metrics；若结果文件 job id 与当前 job 不一致，不把旧结果图片/指标用于当前 job。
- 模型任务类型来自 workflow 绑定的可信 Definition；文件名、显示名、`yoloVersion` 不作为 InternImage 分类依据。
- YOLO Runtime 产生检测结果 `map50`、bbox/sample image 等字段，不产生真实 mIoU。结果卡只把有效、非 fallback 的 `map50` 标为 mAP@0.5，不冒充 mIoU。
- InternImage Runtime 的 `metricsMode=GROUND_TRUTH` 与 `segmentationPerClass` 是展示依据；只选有限且有效的 per-class accuracy，缺失/无 GT 类别不参与。原图、叠加图、prediction mask 使用受 Java workflow 授权和存储路径检查的图片接口；图片缺失不隐藏整体结果。
- CLIENT/SERVER 页面都调用同一类 workflow result API 和共用卡片。结果页新加的 request sequence 只拦截旧 workflow 的迟到响应，不改 callback 或结果生成。
- 上述为本地源代码审计结论，不是当前生产 workflow 的实时数据证明。没有访问 workflow 当前最新 job、resultFilePath、result JSON 或浏览器 Network，因此不把生产空白提前归咎于缓存或代码。

### 上传协议状态

- 上传合同 API `/api/workflow-uploads/contract/{workflowId}` 只对 CLIENT 开放，并由服务端校验 owner/public 与 CREATED/ACCEPTED 状态。
- 页面只有尚有上传名额（或单模型工作流明确允许替换）时才读取协议。上传完成、工作流进入后续处理或验证时只展示后端上传进度，不要求重新取得上传合同。
- 协议读失败不会允许使用默认 FULL_CHECKPOINT/旧合同发起上传。错误与真实文件传输失败分开呈现，刷新/重新选择仍以可信服务端合同为准。

## 修改文件

本轮四项问题的相关修改：

| 文件 | 用途 |
|---|---|
| `service-java/src/main/java/com/workflow/service/impl/WorkflowServiceImpl.java` | 工作流列表统一按 `updatedAt DESC, id DESC` 稳定排序；保留角色与软删除过滤。 |
| `service-java/src/test/java/com/workflow/service/impl/WorkflowServiceImplReadModelDefinitionTest.java` | 回归检查排序、软删除过滤及 SERVER owner 条件。 |
| `frontend-web/src/views/client/ClientWorkflowProgressView.vue` | 后端分页、列表/详情乱序保护、按状态读取上传协议、区分协议错误与上传错误、状态变化刷新侧栏。 |
| `frontend-web/src/views/server/ServerWorkflowProgressView.vue` | 后端分页、列表请求保护、状态变化刷新侧栏；保留数据集草稿及绑定状态逻辑。 |
| `frontend-web/src/views/client/ClientWorkflowManageView.vue` | 列表请求按序应用，迟到的旧筛选/分页响应不覆盖新状态。 |
| `frontend-web/src/views/server/ServerWorkflowManageView.vue` | 同上。 |
| `frontend-web/src/views/client/ClientValidationView.vue` | workflow route 切换/轮询的结果请求序号保护。 |
| `frontend-web/src/views/server/ServerValidationView.vue` | 同上。 |
| `frontend-web/src/utils/workflowProgressState.ts` | 上传协议读取门控、错误归类、请求序号及 workflow 目标选择纯逻辑。 |
| `frontend-web/tests/workflowProgressState.test.ts` | 覆盖上传完成/可上传/错误分类、跨页选中、页码边界及请求序号。 |
| `frontend-web/package.json` | 将相关已有及新增前端逻辑测试纳入 `test:model-definition`。 |
| `docs/WORKFLOW_UI_ISSUES_AUDIT_AND_FIX.md` | 本交付审查与部署报告。 |

双模型结果适配相关的本地未提交实现已在 `docs/DUAL_MODEL_RESULT_UI_REFACTOR_REPORT.md` 中列明，本轮逐项复核并继续复用；其中 `ValidationResultServiceImpl`、结果 DTO、图片服务、`ValidationResultCard.vue` 和 `validationResultPresentation.ts` 不是本轮重写的业务实现。工作区还存在 Stage 5C 等其他预先未提交文件，本报告不把它们归作本轮改动，也没有清理或覆盖它们。

## 自动测试

| 检查 | 结果 | 说明 |
|---|---|---|
| `cd frontend-web && npm run test:model-definition` | **38/38 PASS** | Node 纯逻辑测试，包含结果指标展示、数据集状态和上传协议/分页助手；不是浏览器 E2E。 |
| `cd frontend-web && npm run type-check` | **PASS** | `vue-tsc --build`。 |
| `cd frontend-web && npm run build-only` | **PASS** | Vite 生产构建成功；存在主 bundle 大于 500KB 的提示，不是错误。 |
| Java 相关 targeted tests（JDK 21 隔离运行） | **11/11 PASS** | `ValidationResultServiceImplDualModelTest` 2、`ValidationResultServiceImplStage5CTest` 2、`WorkflowServiceImplReadModelDefinitionTest` 7。 |
| Java 当前全量 Surefire 报告 | **158 total；0 failures；1 error；2 skipped** | 唯一 error 是 `ServiceJavaApplicationTests.contextLoads` 在沙箱中创建 JDK loopback pipe 时 `SocketException: Permission denied: connect`，栈在 `RestTemplateConfig` 的 HttpClient 初始化；不是业务断言失败。其余 155 项通过。默认 shell `java` 是 JDK 8；该 targeted/full 报告来自先前显式 JDK 21 隔离测试，未用系统 JDK 8 重跑。 |
| `git diff --check` | **PASS** | 无 whitespace error；Git 仅提示若干现有文件 LF/CRLF 转换。 |
| Docker/SQL/真实浏览器 | **未执行** | 按要求未运行 Docker、未写数据库；没有真实浏览器会话或生产容器访问权。 |

前端助手测试没有替代真实 DOM/浏览器、权限或图片请求测试。Java unit tests 没有连接当前生产数据库。自动测试通过不等于两条真实联邦学习业务重新验收通过。

## Docker 与部署判断

Compose 中 `service-java` 由 `service-java/Dockerfile` 单独打包；`gateway` 使用仓库根构建上下文和 `frontend-web/Dockerfile`，在镜像构建时执行 npm build 并复制 `frontend-web/dist` 到 Nginx。因而本轮相关源代码部署需要更新 **service-java 与 gateway**。本轮没有修改 service-python、yolo-runtime、internimage-runtime；不需要重建它们。

由于未读取运行容器 image ID，也未读取 gateway 当前静态资源哈希，无法判断生产环境究竟是旧镜像还是数据/接口问题。容器 Healthy 只代表健康检查通过，不证明代码版本新。

### Windows CMD 部署顺序

以下由用户在部署机 CMD 手动执行；Codex 未执行任何 Docker 命令：

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build service-java gateway
docker compose --env-file .env.local-server up -d --no-deps service-java
docker compose --env-file .env.local-server ps service-java
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps service-java gateway
docker compose --env-file .env.local-server images service-java gateway
docker compose --env-file .env.local-server logs --tail=100 service-java gateway
```

完成后在浏览器执行强制刷新（Ctrl+F5），Network 中查看 gateway 返回的 `index.html` 引用的 hashed JS/CSS 是否变化。用已登录的 SERVER/CLIENT 页面查看 `GET /api/validation-results/workflows/{workflowId}` 响应：InternImage 成功结果应包含当前 job id、`taskType=SEMANTIC_SEGMENTATION`、真实 `metricsMode=GROUND_TRUTH`/per-class metrics 和 sample 的图片 URL；YOLO 则检查真实 `map50` 与检测样例。不要把访问令牌复制进日志或报告。

可选只读容器身份核对：从 `docker compose ... ps -q service-java` / `gateway` 取得容器 ID，然后执行：

```cmd
docker inspect <container-id> --format "{{.Image}} {{.Created}}"
```

将其 Image ID 与 `docker compose ... images service-java gateway` 的当前构建镜像对应。若 `index.html` 仍引用旧 hashed asset，检查 gateway；若 JS 已新但 API DTO 不含字段或 job/result 不一致，检查 service-java 镜像与后端结果记录。不要只看 Healthy。

## 人工验收清单

| 范围 | 操作 | 通过条件 |
|---|---|---|
| SERVER 列表一致性 | 分别打开工作流管理、进度工作台；检查新建、最近更新、COMPLETED、FAILED、运行中的合法 workflow，并跨页定位。 | 两页使用同一授权集合和最近更新顺序；软删除不显示；选中详情的 workflowId 与 URL/列表项一致。 |
| 分页与竞态 | 选 10/20/50、第一页/末页、筛选后翻页、删除最后一页项；快速切换 workflow。 | 无重复/漏项造成的错选；空页回退；旧响应不覆盖新页或新 workflow。 |
| CLIENT 上传完成 | 打开已上传完成且已进入服务端处理/聚合/验证的旧 workflow，等待至少三个轮询周期。 | 不再出现“读取上传协议失败”误报；已完成上传状态正常；尚可上传的 CREATED/ACCEPTED 工作流仍取得真实协议。 |
| 协议安全 | 对仍可上传的工作流模拟/观察协议接口权限或暂时失败。 | 页面说明准确，上传按钮保持禁用；没有 legacy 默认合同绕过。 |
| YOLO 结果 | CLIENT、SERVER 分别打开正式完成的 YOLO workflow。 | 展示真实 mAP@0.5（非 mIoU）、检测图/bbox；缺图不隐藏其他结果。 |
| InternImage 结果 | CLIENT、SERVER 打开正式完成的 InternImage workflow。 | 展示最高有效真实 per-class Accuracy 及类别名、原图/overlay/mask；不出现 bbox/mAP；无有效指标显示空态。 |
| 结果 API/资源 | Network 检查 `/api/validation-results/workflows/{id}` 与各 sample artifact 请求。 | 当前 workflow/job 的 DTO 字段非空且一致；图片请求在权限范围内成功，缺图仅降级图片块。 |
| 已有 Global Weights 重新验证 | 对已有正式 Global Weights 的失败验证 workflow 绑定合法数据后点击重试。 | 创建新的 Validation Job，不重新上传、不再 FedAvg；旧 Job 迟到 callback 不覆盖新 Job/结果。 |
| 完整业务回归 | 分别按已有验收流程跑 YOLO 与 InternImage 新 workflow。 | 上传、加密、服务端检查、FedAvg、Global Weights、Runtime 验证、callback、结果展示全部通过；这一步由用户执行，当前报告不声称已实测。 |

## 最终状态

- 工作流列表一致性、分页、请求竞态和上传协议误报：源码修复及对应逻辑测试已完成。
- 双模型结果映射：本地适配存在并经过 Java/前端定向测试；结果页旧响应覆盖风险已修复。
- 生产环境 InternImage 展示故障根因：**尚未能由本地证据区分旧镜像/静态资源与真实 Job/结果数据问题**，需要用户部署后按 API/hashed assets 核验。
- Docker 部署、当前数据库记录、真实角色授权、浏览器图片加载、两条完整业务：待用户人工验收。
- 未执行 SQL、Docker、模型上传、FedAvg 或正式 GPU 验证；未修改正式资产/数据。
