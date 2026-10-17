# 双模型工作流验证结果与页面精简报告

> 历史记录：本文记录当时仅显示真实 GT mAP@0.5、并隐藏 fallback 分值的策略。当前 YOLO 结果页改为项目自定义展示分数，具体候选、映射及不回写约束以 `YOLO_ACCURACY_DISPLAY_FIX.md` 为准；InternImage 指标选择规则保持不变。

## 范围与结论

本轮只改结果读取、图片访问、展示和确定冗余的页面文字。没有修改上传、AES、Definition 兼容、FedAvg、两个 Runtime 的推理/指标、Python Job、Java callback、数据集绑定或重新验证状态机。数据库 Schema/数据未改；未执行 Docker、SQL 或真实业务任务。工作区在开始时已有 Stage 5C 未提交改动，均予保留。

代码层面的双模型结果展示已接通；真实浏览器、Docker 和 GPU 闭环仍须用户验收，不能仅以自动测试宣布双模型最终 PASS。

## 1. 原因分析与结果链

| 环节 | 现有数据及问题 | 本轮处理 |
|---|---|---|
| YOLO Runtime | `service-yolo-runtime/app/weights_validation.py` 产生检测 `map50`、`map50_95`、precision、recall、bbox 样例。`accuracy` 是 precision/recall 均值；无 GT 时 `fallback=true` 且指标由预测估计。**没有 mIoU**。 | 展示真实 GT 的 `map50`，准确命名 mAP@0.5；fallback 或来源不明时显示“暂无有效指标”。绝不把 mAP/accuracy 改名 mIoU。 |
| InternImage Runtime | `service-internimage-runtime/app/segmentation_metrics.py` 用真实 GT 混淆矩阵产出 `mIoU/mAcc/aAcc/perClass[{classId,className,iou,accuracy}]`，值为 0..1，GT 缺席类别的 accuracy 为 null。`weights_validation.py` 输出原图、叠加图和预测 Mask。 | 不改计算，只读取现有真实结果。 |
| service-python | `result_service.py` 将 `metrics`、`taskType`、`sampleResults`、`validationEvidence` 写入 `<jobId>_result.json`；分割样例含 `imagePath/annotatedImagePath/predictionMaskPath`。 | 未修改。 |
| Java callback / DB | 最新 Job 的 `metricsJson` 和 `resultFilePath` 存于 workflow；迟到 callback 已由既有 jobId 保护。 | 未改 callback；结果读取额外校验文件 `jobId` 与当前 Job 一致。 |
| Java 结果 DTO | `ValidationResultServiceImpl.getWorkflowResultView` 原以 `workflow.yoloVersion` 作 `algorithmType`，InternImage 无此字段；虽然已有分割指标映射，却只把一张展示图放进样例 DTO，未暴露原图与预测 Mask。 | 通过 `workflow.model_definition_id` 读取可信 Definition 的 family/displayName/taskType/runtimeProfile；增加受角色授权的原图/Mask 图片端点。 |
| Vue | CLIENT/SERVER 验证页标题行固定“YOLO 版本”，fallback 数据强制 `YOLO`。共用 `ValidationResultCard` 主指标固定为泛称 accuracy 或 mIoU，并显示大量技术元数据。 | 两侧统一按可信 Definition/`taskType` 显示；单个共用结果组件按任务类型选取正确展示，不复制管理流程。 |

因此“InternImage 成功后未正确展示”是**展示适配不完整**，不是已经证实的 Runtime/Job/回调失败：后端已存有分割 metrics，但模型类型被 YOLO 字段覆盖，样例 Mask 在结果 DTO 中丢失，页面主指标也不是所需最高类别 Accuracy。若某次结果页仍完全空白，需继续核对该次正式 resultFilePath、Job 与浏览器请求；本轮不能凭代码伪称已读取用户生产结果。

## 2. 指标与图片合同

- InternImage：只有 `taskType=SEMANTIC_SEGMENTATION` 且 `metricsMode=GROUND_TRUTH` 时，在 `segmentationPerClass` 中选择有限、位于 `[0,1]` 的最高 `accuracy`；null 类别不参与。主标签明确为“最高类别准确率”，类别名称单独显示。mIoU/mAcc/aAcc 为次指标。三类由正式结果的 classId/className 读取，不凭文件名推断。
- YOLO：Runtime 无 mIoU。仅当 `fallback=false` 且 `map50` 为有效 0..1 比例时，主标签为“mAP@0.5”。旧结果缺少可靠 fallback 证据时宁可显示“暂无有效指标”，仍可显示状态和图片。不能把已有 `accuracy=(precision+recall)/2` 当作真实 mIoU。
- 统一按协议比例 `0..1` 乘 100、保留两位；缺失、非有限、越界均显示“暂无有效指标”。正式 JSON/DB/API 数值不改写、不随机扰动。
- `imageUrl` 保留既有受权限保护的标注/叠加图路径；新增 `sourceImageUrl`、`predictionMaskUrl`，由 Java 当前结果文件解析、校验真实路径在受控 dataset/sample 根内后返回。无图时页面显示空态。图片端点仍经过 CLIENT 发起人或 SERVER 受理人的工作流授权，禁止跨工作流读取。
- 缓存 manifest 绑定最新 `pythonJobId`；旧 Job 图片缓存不能在新 Job 结果页冒充本次图片。读取结果文件时，若内含的 jobId 与 workflow 当前 Job 不符则拒绝。

## 3. 页面审查清单

| 实际页面/组件 | 原有冗余或风险 | 动作与保留理由 |
|---|---|---|
| 登录、注册 | 重述登录后角色/工作区的副标题 | 删除副标题；保留表单、校验、登录/注册操作。 |
| CLIENT/SERVER 概览 (`OverviewDashboard`) | 说明性口号、最近准确率在 hero/卡片重复，且缺模型任务语义，可能把检测合成 accuracy 与分割指标混淆 | 删除重复准确率及说明、快捷入口说明；保留工作流、最近任务、资产数量、角色状态和跳转。具体指标以结果页为准。 |
| CLIENT/SERVER 工作流管理 | 标题下重复解释页面用途 | 删除副标题；保留筛选、分页、创建/接收、详情、删除确认与入口。 |
| CLIENT/SERVER 工作流进度 | 左右面板副标题重复标题；状态/机制细节可能过密 | 仅删重复副标题。保留阶段状态、上传、绑定、轮询、重新验证、错误和诊断字段，避免既有数据集选择修复回退。 |
| CLIENT/SERVER 模型管理 (`ModelManagePanel`) | 页内再次解释资产库、运行模式标签 | 删除重复介绍；保留模型类型/版本、资产状态、上传/导入与路径限制警告。 |
| CLIENT/SERVER 数据集管理 (`DatasetManagePanel`) | 页内再次解释资产库、运行模式标签 | 删除重复介绍；保留格式/数量/校验状态、导入路径限制及管理操作。 |
| 独立模型验证 (`StandaloneValidationWorkbench`) | 介绍性 alert 复述角色能力 | 改为紧凑标题；保留保留策略警告、输入模式、历史列表、分页及结果入口。 |
| CLIENT/SERVER 工作流验证结果 | 重复列出状态、联邦状态、Python Job、进度，模型行固定 YOLO | 默认只列工作流、模型、状态、数据集；技术 Job/Definition 放结果“详细信息”；保留服务端启动条件与错误提示。 |
| 共用结果组件 | 大型渐变 hero、泛称 accuracy、重复元数据、检测字段强加分割样例 | 改为紧凑指标与样例；分割专用类别指标和 Mask，检测专用 bbox 列表；低频信息折叠。 |
| 实际路由中的其他页面 | `PlaceholderView` 非主导航功能页，SERVER/CLIENT dashboard 外壳、`AppLayout` 负责导航 | 保留，不做大规模删除或替换框架；不影响权限与路由。 |

分类原则：保留核心业务/风险告警；删除重复解释；合并结果主指标与上下文；折叠诊断数据；把模型身份放到结果标题而非重复表格。

## 4. 修改文件

Java：`ValidationResultController.java`、`ValidationResultViewVO.java`、`ValidationResultSampleVO.java`、`ValidationResultService.java`、`ValidationResultServiceImpl.java`、`ValidationImageCacheService.java`；测试 `ValidationResultServiceImplStage5CTest.java`（构造夹具）及新增 `ValidationResultServiceImplDualModelTest.java`。

前端：`src/api/validation.ts`、`src/components/ValidationResultCard.vue`、`StandaloneValidationWorkbench.vue`、`dashboard/OverviewDashboard.vue`、`model/ModelManagePanel.vue`、`dataset/DatasetManagePanel.vue`、`views/LoginView.vue`、`RegisterView.vue`、CLIENT/SERVER `ValidationView.vue`、`WorkflowManageView.vue`、`WorkflowProgressView.vue`；新增 `src/utils/validationResultPresentation.ts` 与 `tests/validationResultPresentation.test.ts`。本报告为新增文档。

本轮没有修改 Python、YOLO/InternImage Runtime、模型权重、正式数据集、Schema 或 FedAvg；这些目录中若有 Git 未提交修改，均为本轮开始时已存在的工作区状态。

## 5. 自动验证与边界

| 测试 | 实际结果 | 类型/限制 |
|---|---|---|
| Java 结果定向 | 4/4 PASS | Mockito + 临时真实 JSON/图片文件，验证 Definition 读取、两任务映射、bbox、分割原图/Mask URL、角色拒绝、旧 Job 拒绝；非生产 DB。 |
| Java 全量 | 157 tests，154 passed / 0 failures / 1 error / 2 skipped | 唯一错误 `ServiceJavaApplicationTests.contextLoads`：沙箱拒绝 JDK HTTP 客户端内部 loopback pipe，`SocketException: Permission denied: connect`。这是测试环境权限，不归因于本轮业务逻辑。排除该 Context 测试重跑其余套件 exit 0。 |
| 前端 `node --test tests/*.test.ts` | 34/34 PASS | 纯逻辑/协议测试，含真实 DTO 形态、分割最高有效类别、YOLO mAP 命名及原有数据集选择稳定性；不是浏览器 E2E。 |
| `vue-tsc --build` | PASS | TypeScript / Vue 类型检查。 |
| `vite build` | PASS | 有大 chunk 提示，不是构建失败。 |
| service-python 全量 | 95 passed, 1 skipped, 3 subtests passed | 使用仓库内隔离临时目录规避 Windows ACL；未改其生产代码。 |
| YOLO Runtime | 17 passed, 3 subtests passed | 本机 Python 回归；未执行真实 Docker/GPU。 |
| InternImage Runtime | 54 passed, 1 skipped | 本机回归；未执行真实 Docker/GPU。 |
| `git diff --check` | PASS | 仅存在已有 LF/CRLF 提示，无 whitespace error。 |

图片在真实浏览器中的授权、加载/预览、两个模型真实生产结果值、分页搜索、重试后缓存与权限仍需人工端到端验收；自动测试不能替代。

## 6. 用户部署：Windows CMD

只需更新 Java 结果 API 与前端 gateway。`service-python`、`yolo-runtime`、`internimage-runtime` 的本轮生产代码未变，不重建。用户在自己的 CMD 中依次运行：

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build service-java gateway
docker compose --env-file .env.local-server up -d --no-deps service-java
docker compose --env-file .env.local-server ps service-java
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps gateway service-java service-python yolo-runtime internimage-runtime
docker compose --env-file .env.local-server logs --tail=80 service-java gateway
```

打开实际 `PUBLIC_HTTP_PORT` 对应的门户，使用已有 SERVER/CLIENT 账号验收。不要先删工作流、资产、结果，也不需要 SQL。

## 7. 双模型人工验收

| 链路 | 操作 | 通过条件 |
|---|---|---|
| YOLO 完整链 | 新建 Definition 工作流 → A/B 客户端分别上传 weights-only 并加密传输 → 服务端接收/解密/检查 → FedAvg → 绑定检测数据集 → 显式验证 → 两侧打开工作流结果 | 正式输入/全局资产状态正确；YOLO Runtime 路由正确；带 GT 时主指标是与结果 JSON 一致的真实 mAP@0.5（**不是 mIoU**）；原图、框图、bbox/类别可访问。无 GT 时不展示估算高分。 |
| InternImage 完整链 | 新建 InternImage Definition 工作流 → A/B 上传 → 安全检查 → 同一 generic FedAvg → 绑定合法 images/masks GT 数据集 → 显式 GPU 验证 → 两侧打开结果 | strict=True 且不使用 checkpoint；结果 JSON 的真实 mIoU/mAcc/aAcc/perClass 与页面数值逐项相符；最高有效 per-class Accuracy 的名称和值准确；原图、叠加图、预测 Mask 可预览；不出现 bbox/mAP 检测 UI。 |
| 已有 Global Weights 重试 | 对已有已完成聚合、失败验证工作流重新绑定合法数据集并点击“重新验证” | 创建新 Job，不重新上传或 FedAvg；新结果覆盖当前展示，旧 Job 迟到 callback 不影响新状态；旧缓存图片不串入新 Job。 |
| 权限、导航与退化 | CLIENT、SERVER 分别浏览自己的工作流，尝试访问不属于本人的图片 URL；切换工作流、筛选/分页、反复轮询、刷新浏览器，测试缺失图片/缺失指标/失败任务 | 本人可读、跨用户拒绝；数据集绑定不消失；按钮与重试有效；缺图、缺指标显示明确空态，不显示 0% 或旧任务数据。 |

**最终结论：代码与自动回归就绪；真实双模型 Docker/浏览器业务验收待用户完成。**
