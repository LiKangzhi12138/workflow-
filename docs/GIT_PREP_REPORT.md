# workflow-platform GitHub 上传前审计报告

审计日期：2026-09-01  
项目路径：`D:\workspace\workflow-platform`

> 本报告不包含任何真实密码、密钥或令牌值。本轮未执行 `git add`、`git commit`、`git push`、历史重写、文件删除或 Git LFS 操作。

## 1. Git 是否已初始化

是。`git rev-parse --is-inside-work-tree` 返回 `true`。

## 2. 当前 branch

`main`。当前分支尚无 commit。

## 3. 当前 remote

未配置 remote；`git remote -v` 无输出。

## 4. 当前 .gitignore 路径

`D:\workspace\workflow-platform\.gitignore`

子目录还存在：

- `frontend-web/.gitignore`
- `service-java/.gitignore`

`service-python` 原来没有独立 `.gitignore`，由根规则覆盖。

## 5. 原 .gitignore 是否存在

存在，原文件只有 `.env`、`.env.local`、`.env.local-server`、`.runtime/` 四项规则。

## 6. 原 .gitignore 结论

**需补充。** 原规则没有覆盖 Node、Maven、Python、IDE、本地 Maven 仓库、Java 本地 storage、Python storage、日志、数据库快照等真实生成内容。

## 7. 是否修改/创建 .gitignore

已在原文件基础上补充，没有覆盖或删除子目录已有合理规则。

## 8. 最终 ignore 分类

- Environment/secrets：真实 `.env*`、证书和私钥文件；保留安全的 `.env.example`、`.env.template`。
- Frontend：`node_modules`、`dist`、Vite/coverage/cache、调试日志。
- Java：`target`、class、本地 Maven 仓库、Mockito 临时 bootstrap JAR。
- Python：`__pycache__`、pyc、测试/类型检查缓存、虚拟环境、build/dist、egg-info。
- IDE/OS：IDEA、根 VS Code 本地配置、Codex/agent 本地目录、Windows/macOS 文件。
- Runtime：`.runtime`、`storage/dev`、`service-java/storage`、`service-python/app/storage`、MySQL 数据目录。
- Logs/temp：日志、临时文件、备份文件、swap 文件。
- Database：本地 DB 文件和根目录 `dump-*.sql`。

未使用全局 `*.pt`、`*.pth` 规则；未来 ModelDefinition、协议 schema 和必要的小型 fixture 不会被模型扩展名规则误伤。

## 9. Environment / secrets 检查

| 文件 | 结论 | 处理建议 |
|---|---|---|
| `.env.local-server` | 含自定义 MySQL 凭据和回调密钥 | 已忽略，不得强制加入 Git |
| `service-python/.env` | 含自定义回调密钥 | 已由 `.env` 规则忽略 |
| `.env` | 当前为占位配置，但按真实环境文件策略忽略 | 使用 `.env.example` 对外提供模板 |
| `.env.example` | 敏感字段均为占位值 | 允许提交 |
| `.env.local-server.example` | 三项 MySQL 凭据与真实 `.env.local-server` 相同 | 当前先忽略；上传前必须人工改为明显占位值，再在 `.gitignore` 中显式放行 |

未发现 GitHub/OpenAI/Claude/DeepSeek/AWS 私钥格式命中。源码中的 AES key/IV、secret 等命中均为字段名、环境变量名或算法代码，不代表硬编码真实密钥。

## 10. Docker runtime 检查

`.runtime/local-server` 实际包含 MySQL 数据、运行日志、模型、上传密文、验证结果和图片，已由 `/.runtime/` 整体忽略。Dockerfile、`docker-compose.yml`、`nginx.conf`、entrypoint/init 脚本均未被忽略。

## 11. storage 检查

- `storage/dev/`：非 Docker 开发运行目录，已定向忽略。
- `service-java/storage/`：发现 21 个 AES 加密模型上传文件，合计约 131 MiB，已定向忽略。
- `service-python/app/storage/`：包含 `jobs.json`、results、result-samples，已忽略；未来该目录下的 `README.md`、`.gitkeep` 可提交。
- 根 `storage/` 没有 README、`.gitkeep` 或示例配置，未删除任何目录。

## 12. model artifact 检查

共发现 56 个 `.pt/.pth/.onnx/.weights/.bin/.ckpt/.safetensors` 模型文件，合计约 251.64 MiB，全部位于 `.runtime/`，均为运行模型、用户资产或联邦输出，当前全部未跟踪且已忽略。

另外发现 `service-java/storage/model-uploads` 下 21 个 `.pt.enc` 密文文件，均已忽略。未发现需要随源码发布的模型 fixture。

## 13. database runtime 检查

- `.runtime/local-server/mysql-data` 中发现 20 个主要数据库/日志匹配文件，合计约 44.89 MiB；整个数据卷已忽略。
- 根目录 `dump-workflow_platform-202604171516.sql` 含用户表/密码哈希字段相关数据，是数据库快照而非 schema migration，已按 `/dump-*.sql` 忽略。
- `docs/sql`、`service-java/sql` 和 Docker 初始化 SQL 保持可提交。

## 14. frontend generated files

真实存在 `frontend-web/node_modules`（约 171.22 MiB）和 `frontend-web/dist`（约 1.53 MiB），均已忽略。`package.json`、`package-lock.json`、Vite/TS 配置、`src` 保持可提交。

## 15. Java generated files

真实存在 `service-java/target`、多套 `.m2/.m2repo` 本地依赖仓库和 `mockitoboot*.jar` 临时文件，均已忽略。`pom.xml`、源码、测试、`.mvn` 和 wrapper 脚本保持可提交。

## 16. Python generated files

真实存在 `__pycache__` 和 app storage，均已忽略。Python 源码、测试、`requirements.txt` 和项目文档保持可提交。

## 17. logs/temp

`.runtime` 内 Java/Python/Nginx 历史日志已忽略；Maven 缓存中的 `.tmp` 文件也由缓存目录和临时文件规则覆盖。没有删除任何日志或临时文件。

## 18. IDE/OS

`service-python/.idea` 已由 `**/.idea/` 忽略；`.sandbox-home`、`.agents`、`.codex` 属于本地工具状态并已忽略。`.DS_Store`、`Thumbs.db`、`Desktop.ini` 和常见 IDE metadata 已覆盖。

## 19. 大文件列表

以下为整个项目磁盘上的最大 20 个文件；Git 尚无 tracked 文件，这些文件目前均为未跟踪且已被最终规则忽略。

| path | size | tracked/untracked | 建议 |
|---|---:|---|---|
| `.runtime/local-server/mysql-data/mysql.ibd` | 31.00 MiB | untracked / ignored | 保持忽略 |
| `frontend-web/node_modules/@rolldown/binding-win32-x64-msvc/rolldown-binding.win32-x64-msvc.node` | 23.19 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/mysql-data/undo_002` | 16.00 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/mysql-data/undo_001` | 16.00 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/mysql-data/ibtmp1` | 12.00 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/mysql-data/#ib_16384_1.dblwr` | 12.00 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/mysql-data/ibdata1` | 12.00 MiB | untracked / ignored | 保持忽略 |
| `frontend-web/node_modules/lightningcss-win32-x64-msvc/lightningcss.win32-x64-msvc.node` | 9.06 MiB | untracked / ignored | 保持忽略 |
| `frontend-web/node_modules/typescript/lib/typescript.js` | 8.72 MiB | untracked / ignored | 保持忽略 |
| `.m2repo/net/bytebuddy/byte-buddy/1.17.8/byte-buddy-1.17.8.jar` | 8.60 MiB | untracked / ignored | 保持忽略 |
| `service-java/.m2/net/bytebuddy/byte-buddy/1.17.8/byte-buddy-1.17.8.jar` | 8.60 MiB | untracked / ignored | 保持忽略 |
| `service-java/.m2repo/net/bytebuddy/byte-buddy/1.17.5/byte-buddy-1.17.5.jar` | 8.54 MiB | untracked / ignored | 保持忽略 |
| `service-java/.m2/repository/net/bytebuddy/byte-buddy/1.17.5/byte-buddy-1.17.5.jar` | 8.54 MiB | untracked / ignored | 保持忽略 |
| `.m2/repository/net/bytebuddy/byte-buddy/1.17.5/byte-buddy-1.17.5.jar` | 8.54 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/storage/federated-models/WF202605061422288487/global_model.pt` | 6.48 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/storage/model-uploads/20/encrypted/22_clientB_crop_lodging.pt.enc` | 6.46 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/storage/model-uploads/20/encrypted/21_clientA_crop_lodging.pt.enc` | 6.46 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/storage/models/WF202605061422288487_21/clientA_crop_lodging.pt` | 6.46 MiB | untracked / ignored | 保持忽略 |
| `.runtime/local-server/storage/models/WF202605061422288487_22/clientB_crop_lodging.pt` | 6.46 MiB | untracked / ignored | 保持忽略 |
| `.m2repo/com/github/luben/zstd-jni/1.5.6-3/zstd-jni-1.5.6-3.jar` | 6.34 MiB | untracked / ignored | 保持忽略 |

统计：整个磁盘共 23,901 个文件，`>10 MiB` 为 7 个，`>50 MiB` 为 0 个，`>100 MiB` 为 0 个。

最终 ignore 后的待提交候选共 282 个文件（包含本审计报告），最大为 `frontend-web/package-lock.json`，约 0.13 MiB；候选中没有超过 10 MiB 的文件，也没有模型、密文、数据库或构建二进制。

## 20. 已跟踪但应该忽略的文件

无。`git ls-files` 为空，仓库尚无 commit，因此不存在 `ALREADY_TRACKED_BUT_SHOULD_IGNORE` 项。

## 21. 潜在敏感文件

| 文件路径 | 敏感类型 | 建议处理方式 |
|---|---|---|
| `.env.local-server` | 数据库凭据、回调密钥 | 保持忽略；不要强制添加 |
| `service-python/.env` | 回调密钥 | 保持忽略 |
| `.env.local-server.example` | 与真实环境相同的 MySQL 凭据 | **必须人工脱敏后再允许提交** |
| `dump-workflow_platform-202604171516.sql` | 用户数据、密码哈希字段、数据库快照 | 保持忽略；公开仓库不要提交 |
| `.runtime/local-server/mysql-data/*.pem` | MySQL 自动生成私钥/证书 | 已随 `.runtime` 忽略 |

## 22. GitHub 100 MB 风险

当前磁盘没有单文件超过 50 MiB 或 100 MiB；最终待提交候选也没有超过 10 MiB。只要不使用 `git add -f` 强制加入 ignored runtime，当前不存在 GitHub 100 MB 单文件阻断风险。

## 23. 是否建议 Git LFS

当前不需要安装或使用 Git LFS。所有模型和运行二进制都不应版本管理。未来若确实需要提交不可替代的大型测试 fixture，再单独评估 Git LFS；ModelDefinition JSON、协议 schema、配置和文档应使用普通 Git。

## 24. 当前是否适合开始 git add

**暂时不可以。** 阻塞项：

1. `.env.local-server.example` 含与真实环境相同的 MySQL 凭据，同时 `start-local-server.ps1` 依赖该模板；需要先人工替换为明显占位值。
2. 模板脱敏后，在 `.gitignore` 增加 `!.env.local-server.example` 例外，并再次执行 `git check-ignore -v .env.local-server.example` 确认它已可提交。
3. 完成上述处理前，不要使用 `git add -f` 绕过 ignore。

remote 尚未配置不影响本地审计，但在最终 push 前需要由用户手动配置。
