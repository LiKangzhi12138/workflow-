# 多模型上传与双模型展示评分专项审查

## 根因与范围

| 项目 | 证据与根因 | 本轮处理 |
| --- | --- | --- |
| 进度页开始上传禁用 | `ClientWorkflowProgressView.vue:241,692-719,1270-1285`：按钮依赖可信上传协议、可用状态/槽位、资产和完整文件包。此前 `handleUploadFileChange` 在第一个 Element Plus `onChange` 回调内同步 `clearFiles()`；一次选多个文件时，每个文件都有一个排队回调，后续回调读到被清空的队列并将 `selectedUploadFiles` 覆盖为空，故 `canStartClientUpload=false`。 | `workflowUploadSelection.ts` 在 Vue 下一轮 tick 清理内部队列，所有本批回调先完成选中文件同步；V1 的每次提交只选一个 `.pt`。不取消按钮门槛。 |
| 取消创建时资产预绑定后的影响 | `ClientWorkflowProgressView.vue:187,627-632,1184-1197`：旧进度页只有 `clientModelAssetId` 为空才显示资产选择，且解析资产时优先使用工作流已绑定 ID。首位上传后绑定资产，第二位客户端无法在进度页选择自己的资产；管理页虽然有选择器，但默认继承首位资产。 | V1 始终显示当前用户的可用资产选择；仅当前用户资产才自动预选；V1 初始化必须使用用户明确选择的 ID。旧版预绑定资产仍可沿用。 |
| 管理页按钮禁用/重复打开 | `ClientWorkflowManageView.vue:517,655-663,1433-1441,1480-1525`：按钮依赖真实资产和 `protocolUploadReady`。弹窗原本未销毁，`el-upload limit=1` 在用户选文件、关闭但未提交、再打开时可能保留旧队列，而本地 `uploadFile` 已重置，导致新文件超限且按钮保持禁用。协议读取失败也可能保留上一个弹窗的协议值。 | 关闭时销毁文件控件；协议读取成功才可提交；按工作流请求序号隔离迟到响应；V1 在当前用户资产列表加载并选定后才可提交；移除文件同步清理本地 File。 |
| InternImage 高分 | `validationResultPresentation.ts:93-105`：InternImage 从 `GROUND_TRUTH` 的有效 `segmentationPerClass[].accuracy` 取最高值，但此前直接格式化百分比；YOLO 在 `:66-81` 经过稳定的展示区间映射。98% 是真实 per-class Accuracy 的显示，非 Java DTO 错映。 | 保留 InternImage 真实指标选取，只让最终百分比使用共用展示映射；返回 `basisPercent` 与 `source` 供技术追溯。 |

## 上传链路与安全边界

`WEIGHTS_V1` 的一个上传包是一个 `.pt`、一个 manifest、一个 descriptor。`protocolUploadReady` 仍要求三者齐全；JSON 读取仍由现有工具校验。`expectedModelCount`/`requiredModelCount` 是上传名额，V1 多客户端各自逐次提交，不把多份权重误当成同一个包。Legacy 上传仍按剩余名额选择批量文件。

进度页按钮完整门槛：`uploadContractLoaded`、工作流详情/ID、非空文件选择、状态 `CREATED|ACCEPTED`、可用资产、每个文件符合协议、以及服务端进度对应的剩余槽位；单模型替换仍按原有规则。已完成或进入聚合/验证的工作流不会因此开放上传。工作流切换清理资产和文件，异步资产列表按请求序号应用。管理页也要求可信协议与真实文件包，V1 选择的资产必须属于当前用户可用列表；异常时禁用并提示。浏览器端不是最终授权者。

服务端未改：`WorkflowModelUploadServiceImpl.java:96-117` 的 `initUpload` 仍要求 `modelAssetId` 并校验所有权/状态；`:533-542` 的上传协议由工作流可信 Definition 决定，不依赖预绑定资产；`:945-954` 限制发起人或公开工作流且只允许 `CREATED|ACCEPTED`；`:1052-1083` 按有效上传记录占用名额，多模型未满可继续，满额拒绝。V1 manifest、descriptor、Definition、Safe Inspector 校验未关闭。YOLO 与 InternImage 使用同一上传协议，服务器仍按各自可信 Definition 判兼容；本轮未修改加密、FedAvg、Runtime 或 callback。

## 展示规则

YOLO 仍从合法的汇总字段择最大值，必要时才从当前 Job 的检测 confidence 取候选。InternImage 仍仅从真实 GT 的有效 per-class Accuracy 取最高值，排除 `null`、NaN、越界及非 GT；不使用 YOLO 候选逻辑。两者只共用最后一步展示映射：

| 原始基础值 | 页面值 |
| --- | --- |
| `<=90%` | 同一 workflow/Job 稳定映射至 `90.01%..92.99%` |
| `>90%` 且 `<91%` | 稳定映射至 `91.01%..91.99%` |
| `>=91%` 且 `<=95%` | 原值，两位小数 |
| `>95%` 且 `<=100%` | 稳定映射至 `95.01%..95.99%` |

页面值是**项目自定义展示评分，不是未经转换的真实 GT Accuracy**。原始来源及基础百分比保留在前端适配结果中；Runtime 原始指标、Java DTO、API、result JSON 和数据库均不变。共用结果卡仍只展示一个主数值与同一 Job/样例的原图和输出图，不增加类别明细、Mask 单图或检测框详情。

## 修改文件

| 文件 | 用途 |
| --- | --- |
| `frontend-web/src/views/client/ClientWorkflowProgressView.vue` | 修复批量文件回调、V1 单包选择、第二位客户端资产选择及按钮门槛。 |
| `frontend-web/src/views/client/ClientWorkflowManageView.vue` | 弹窗文件状态重建、可信协议门槛、资产列表/乱序保护、移除/超限反馈。 |
| `frontend-web/src/utils/workflowUploadSelection.ts` | 可测试的 V1/Legacy 资产选择与延迟清队列逻辑。 |
| `frontend-web/src/utils/validationResultPresentation.ts` | InternImage 复用展示区间，真实 GT 来源保持独立。 |
| `frontend-web/tests/workflowUploadSelection.test.ts` | 多个排队回调、单包/Legacy 限额、第二位客户端资产选择。 |
| `frontend-web/tests/workflowCreateAssetSelection.test.ts` | 两个入口的协议与资产选择组件契约。 |
| `frontend-web/tests/validationResultPresentation.test.ts` | 双模型边界、原始值不变、Job 稳定与隔离。 |
| `frontend-web/package.json` | 纳入新增定向测试。 |
| `docs/MULTI_MODEL_UPLOAD_AND_SCORE_AUDIT.md` | 本审查与手动验收说明。 |

工作区原本存在其它未提交的 Stage 5C 与 UI 改动；本轮未回退、清理或归并那些改动。

## 自动检查

| 检查 | 真实结果与局限 |
| --- | --- |
| `npm run test:model-definition` | **PASS 57/57**。上传回调为 Vue scheduler + 文件队列模拟；组件契约为静态测试。没有 DOM/浏览器组件测试依赖，不能把此项视为真实上传。 |
| `npm run type-check` | **PASS**，`vue-tsc --build`。 |
| `npm run build-only` | **PASS**，Vite v8.0.3；既有大 chunk 警告。 |
| JDK 21 `mvn -o -q test-compile` | **PASS**；本轮没有 Java 源码修改。 |
| JDK 21 离线 Java 定向 `test` | **未执行到断言**：本机离线缓存缺少 `maven-surefire-plugin:3.5.3` 的 `surefire-api` 等依赖；未安装或下载。 |
| `git diff --check` | **PASS**；仅现有 Windows LF/CRLF 提示。 |
| Docker、多客户端加密上传、FedAvg、GPU 验证 | **未执行**，须用户人工验收。 |

## Windows CMD 最小部署

本轮只改变前端打包内容，只重建 `gateway`。不重建 Java、service-python 或两个 Runtime；不执行数据库操作。保持现有 `.env.local-server` 和数据卷配置：

```bat
cd /d D:\workspace\workflow-platform
git diff --check
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build gateway
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps gateway
docker compose --env-file .env.local-server logs --tail=80 gateway
```

浏览器强制刷新或无痕窗口打开新前端。若当前生产 `service-java` 尚未部署上一轮允许 Definition 创建时资产为空的改动，本轮仅更新 gateway 仍不足以完成新工作流创建，需先按上一轮报告升级 Java；本轮未修改该服务。

## 人工验收

1. CLIENT A 新建 YOLO Definition 工作流（资产初始为空、计划 2 个客户端）；在管理弹窗和进度页分别检查自己的资产、`.pt`、manifest、descriptor 齐备后按钮可用。缺任一文件、资产或可信协议时仍禁用；切工作流后不串选文件。选过文件但未提交、关闭再打开，重新选文件不被 `limit=1` 阻止。
2. A 实际提交第一包，核对上传记录/状态及剩余名额为 1；CLIENT B 打开同一公开工作流，在进度页必须能选择 B 自己的资产（不能被 A 的绑定 ID 固定），提交第二包。管理页同样可选 B 的资产。用既有服务端接收与资产/Definition 校验确认两个 uploader、两个 upload/asset ID；重复或超名额上传应被拒绝。不用同一资产 ID 伪装两个客户端。
3. 用 InternImage Definition 重复上述单包逐次上传，确认错误家族、错误 shape/dtype、缺 manifest/descriptor 均被原有服务端检查拒绝；服务端收到的仍是加密文件及可信协议元数据。分别检查 YOLO/InternImage 的接收、FedAvg、Global Weights 正式资产、Runtime 路由、验证和结果页；本轮不自动启动这些真实任务。
4. 检查旧版预绑定工作流上传与已完成历史记录，已满名额或进入聚合/验证状态不能再次开始上传，单模型显式替换仍走原规则。验证失败后已有重试入口、数据集绑定、CLIENT/SERVER 分页和权限不回退。
5. 已有 YOLO 与 InternImage 成功结果：原图与输出图仍来自同一 Job/样例；InternImage 原始 per-class Accuracy 例如 98% 时页面落在 `95.01%..95.99%`，刷新稳定、换 Job 可变；无真实 GT 有效类别时不凭空生成原始指标。对照 result JSON/API 验证原始数值未改。

**状态：前端代码与配置检查通过；P0 的真实多客户端上传与双模型闭环仍待用户 Docker/浏览器人工验收，不能提前宣布最终业务 PASS。**
