# workflow-platform 本机临时服务器模式说明

## 1. 这是什么模式

本机临时服务器模式（local server mode）指的是：

- 把你当前的 Windows 开发电脑临时当作一台小型服务器
- 让同一局域网内的其他电脑通过你的电脑 IP 访问项目
- 继续使用现有的 `gateway + Java + Python + MySQL` 架构
- 浏览器只走一个统一入口，尽量避免跨域和登录态问题

这不是正式云部署，也不是最终生产架构。它更适合：

- 组内联调
- 局域网演示
- 临时给老师、同事或测试人员访问
- 上云前的功能验证

## 2. 本模式解决什么问题

改造后的本机服务器模式重点解决的是：

- 页面不再只能本机打开，局域网其他电脑也能访问
- 前端、Java、Python 不再默认只绑 `127.0.0.1`
- 浏览器优先通过统一入口访问，不用手工记多个端口
- 前端 API 地址不再写死 localhost
- Java -> Python 的地址保持配置化，后续切云服务器也不需要回退到硬编码

## 3. 当前推荐架构

本机服务器模式推荐仍然使用统一入口：

1. 浏览器访问 `gateway`
2. `gateway` 提供前端静态页面
3. `gateway` 把 `/api/*` 代理到 Java
4. Java 通过 Docker 内部网络调用 Python
5. Java 和 Python 共用本机挂载目录保存模型、验证结果和联邦聚合文件

推荐访问地址：

```text
http://你的局域网IP:18080/
```

默认情况下：

- 局域网用户只需要访问 `18080`
- Java `8080` 和 Python `8000` 不作为给别人直接访问的入口
- MySQL `13306` 默认只绑定到本机 `127.0.0.1`

## 4. 本模式涉及的文件

本轮新增或用于本模式的关键文件如下：

- `.env.local-server.example`
- `README_LOCAL_SERVER.md`
- `start-local-server.ps1`
- `stop-local-server.ps1`
- `check-local-server.ps1`
- `start-local-server.bat`
- `stop-local-server.bat`
- `check-local-server.bat`

## 5. 本模式使用的配置文件

本机服务器模式使用：

```text
.env.local-server
```

如果第一次启动时该文件不存在，启动脚本会自动从模板复制：

```text
.env.local-server.example
```

### 5.1 关键默认值

本模式默认值如下：

- 统一入口端口：`18080`
- Gateway 监听地址：`0.0.0.0`
- Java 监听地址：`0.0.0.0`
- Python 监听地址：`0.0.0.0`
- 前端 API Base URL：`/api`
- MySQL 宿主机端口：`13306`
- MySQL 宿主机绑定地址：`127.0.0.1`

这里最关键的是：

- 对浏览器而言，页面和接口默认同源
- 对局域网用户而言，只暴露 Gateway 即可
- 对系统内部而言，Java 和 Python 仍通过容器内部服务名通信

## 6. 为什么本模式优先推荐同源访问

当前推荐方式是：

- 页面地址：`http://你的局域网IP:18080/`
- 接口地址：`/api`

这意味着：

- 浏览器访问页面和调用接口使用同一个源
- Session/Cookie 更稳定
- 不需要额外开放 Java、Python 给浏览器直连
- 可以最大程度减少 CORS 错误

因此，除非你有明确需求，否则请不要让别人分别访问：

- 前端开发端口
- Java 端口
- Python 端口

统一使用 Gateway 即可。

## 7. Windows 下如何启动

### 7.1 先准备环境

本模式建议至少具备：

- Windows 10 / 11
- Docker Desktop
- PowerShell

先确认 Docker 已安装：

```powershell
docker version
docker compose version
```

### 7.2 启动 Docker Desktop

在启动项目之前：

1. 先打开 Docker Desktop
2. 等待 Docker 完全启动
3. 再执行本机服务器模式脚本

### 7.3 启动命令

推荐用 PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local-server.ps1
```

也可以直接双击或在 `cmd` 里执行：

```bat
start-local-server.bat
```

### 7.4 首次启动的行为

首次启动时，如果没有 `.env.local-server`，脚本会：

1. 自动复制模板文件
2. 提示你修改数据库密码和回调密钥
3. 停止继续启动

这时你需要：

1. 打开 `.env.local-server`
2. 至少改掉以下占位值：
   - `MYSQL_PASSWORD`
   - `MYSQL_ROOT_PASSWORD`
   - `PYTHON_CALLBACK_SECRET`
3. 保存后再次执行启动脚本

### 7.5 停止服务

PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\stop-local-server.ps1
```

或：

```bat
stop-local-server.bat
```

### 7.6 启动后健康检查

PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\check-local-server.ps1
```

或：

```bat
check-local-server.bat
```

这个脚本会检查：

- docker compose 服务状态
- Gateway 健康检查
- Java 健康检查
- Python 通过 Java 的健康检查
- Windows 本机是否监听了统一入口端口

## 8. 如何查看本机局域网 IP

在 PowerShell 或 cmd 中执行：

```powershell
ipconfig
```

找到当前正在使用的网卡，查看：

- `IPv4 地址`

常见示例：

- `192.168.1.23`
- `192.168.31.45`
- `10.0.0.18`

如果本机服务器模式端口是 `18080`，那么其他电脑的访问地址通常就是：

```text
http://192.168.x.x:18080/
```

## 9. 局域网其他电脑如何访问

同一局域网内的其他电脑访问步骤：

1. 在你的开发机上启动本机服务器模式
2. 执行 `ipconfig`
3. 找到当前局域网 IPv4 地址
4. 让其他电脑打开：

```text
http://你的局域网IP:18080/
```

例如：

```text
http://192.168.1.23:18080/
```

不要让别人访问多个分散端口，也不要让别人自己拼接口地址。

## 10. 哪些端口需要放行

默认情况下，局域网访问主要只需要放行：

- `18080/tcp`

说明：

- MySQL `13306` 不是给浏览器访问的
- Java `8080` 和 Python `8000` 也不是给浏览器直接访问的
- 如果你坚持让别人直连其他端口，那就会增加跨域、会话和误配置风险

## 11. Windows 防火墙如何检查

### 11.1 先确认端口是否在监听

```powershell
netstat -ano | findstr :18080
```

如果本机服务器模式已经正常启动，应该能看到监听记录。

### 11.2 添加放行规则

请以管理员身份打开 PowerShell，然后执行：

```powershell
netsh advfirewall firewall add rule name="workflow-platform-local-server-http" dir=in action=allow protocol=TCP localport=18080
```

如果你后续把统一入口端口改掉了，也要同步修改这条规则。

### 11.3 如何确认不是只监听 localhost

本模式中关键配置已经改成可对外绑定：

- Gateway 使用 `PUBLIC_HTTP_BIND_IP=0.0.0.0`
- Java 使用 `SERVER_ADDRESS=0.0.0.0`
- Python 使用 `PYTHON_SERVICE_HOST=0.0.0.0`
- 如果你本地手工跑 Vite，也可以用 `VITE_DEV_SERVER_HOST=0.0.0.0`

## 12. Windows 路径与运行目录

本模式为了兼容 Windows，本地运行数据默认放在项目根目录下：

```text
.\.runtime\local-server\
```

主要目录：

- `.\.runtime\local-server\storage`
- `.\.runtime\local-server\logs`
- `.\.runtime\local-server\mysql-data`

这样做的好处是：

- 不依赖 Linux 绝对路径
- 不容易污染正式云部署目录
- 停止本模式后也方便备份或整体清理

脚本会自动创建这些目录，不需要你手工先建。

## 13. 常见问题排查

### 13.1 别人完全打不开页面

优先排查：

1. 两台电脑是否在同一局域网
2. Docker Desktop 是否真的在运行
3. 本机服务器模式是否已经启动成功
4. 你给出的 IP 是否正确
5. `18080` 是否被 Windows 防火墙拦截
6. `check-local-server.ps1` 是否通过

### 13.2 别人能打开页面，但接口全失败

这通常意味着浏览器没有正确通过统一入口访问接口。重点检查：

1. 当前访问地址是否是 `http://你的IP:18080/`
2. `VITE_API_BASE_URL` 是否仍是 `/api`
3. Gateway 是否健康
4. Java 是否健康
5. 浏览器是否缓存了旧的静态页面

推荐修法：

- 保持 `VITE_API_BASE_URL=/api`
- 只通过 Gateway 统一入口访问
- 不要把前端和 Java 拆开给浏览器访问

### 13.3 你自己能登录，别人登录后马上失效

优先检查：

1. 对方是不是也是通过统一入口访问
2. 页面和接口是不是同源
3. Java 服务是否健康
4. 浏览器是否阻止 Cookie

如果未来切 HTTPS，再把：

```text
SESSION_COOKIE_SECURE=true
```

### 13.4 Java 调 Python 失败

请检查：

- `PYTHON_SERVICE_BASE_URL`
- Python 容器健康状态
- Docker Desktop 是否正常

本模式下，通常应该保持：

```text
PYTHON_SERVICE_BASE_URL=http://service-python:8000
```

### 13.5 Python 回调 Java 失败

请检查：

- `JAVA_CALLBACK_BASE_URL`
- `PYTHON_CALLBACK_SECRET`

本模式下，通常应该保持：

```text
JAVA_CALLBACK_BASE_URL=http://service-java:8080
```

### 13.6 上传失败

请检查：

- 磁盘空间是否足够
- Docker Desktop 磁盘配额是否足够
- `.\.runtime\local-server\storage` 是否存在
- `CLIENT_MAX_BODY_SIZE`
- `MAX_UPLOAD_SIZE`
- `MAX_REQUEST_SIZE`

### 13.7 PowerShell 提示脚本禁止执行

可以直接使用：

```bat
start-local-server.bat
stop-local-server.bat
check-local-server.bat
```

或者显式用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local-server.ps1
```

## 14. 什么时候需要配置 CORS

在当前推荐的同源访问模式下：

- `APP_ALLOWED_ORIGINS` 可以留空
- `APP_ALLOWED_ORIGIN_PATTERNS` 也可以留空

如果未来你改成“页面和接口不在同一个源”，再配置它们。

例如：

```text
APP_ALLOWED_ORIGINS=http://192.168.1.23:5173
```

或者：

```text
APP_ALLOWED_ORIGIN_PATTERNS=http://192.168.*.*:5173,http://10.*.*.*:5173
```

## 15. 外网访问预备模式

本轮没有强制集成内网穿透或公网域名，但已经给后续预留了配置位。

如果未来接入以下方式：

- 公网 IP
- 域名
- 内网穿透
- 另一层反向代理

你主要需要核对这些配置：

- `PUBLIC_HTTP_BIND_IP`
- `PUBLIC_HTTP_PORT`
- `NGINX_SERVER_NAME`
- `VITE_API_BASE_URL`
- `APP_ALLOWED_ORIGINS`
- `APP_ALLOWED_ORIGIN_PATTERNS`
- `SESSION_COOKIE_SECURE`

### 15.1 后续接域名时怎么改

如果未来使用域名，例如：

```text
demo.example.com
```

重点调整：

```text
NGINX_SERVER_NAME=demo.example.com
```

### 15.2 后续接 HTTPS 时怎么改

如果未来通过 HTTPS 对外访问：

```text
SESSION_COOKIE_SECURE=true
```

### 15.3 后续仍建议保持同源

即使后续切到外网访问，也仍推荐：

- 只暴露 Gateway
- 前端继续使用 `/api`
- Java 和 Python 不直接给浏览器暴露

这是最省事、最稳定的方式。

## 16. 如何切回正式云部署模式

如果后续要迁移到正式云服务器：

1. 停止本机服务器模式
2. 改用云部署文档
3. 使用：
   - `README_DEPLOY.md`
   - `README_DEPLOY_CHECKLIST.md`
   - `.env.example`

本机服务器模式不会破坏云部署模式，因为核心配置仍然是外置的。

## 17. 推荐启动顺序

建议按这个顺序操作：

1. 打开 Docker Desktop
2. 进入项目根目录
3. 运行 `start-local-server.ps1`
4. 如果第一次生成了 `.env.local-server`，先编辑它
5. 改掉示例密码和回调密钥
6. 再次运行 `start-local-server.ps1`
7. 运行 `check-local-server.ps1`
8. 执行 `ipconfig`
9. 把 `http://你的局域网IP:18080/` 发给其他电脑

## 18. 推荐停止顺序

1. 先通知测试人员停止使用
2. 执行 `stop-local-server.ps1`
3. 确认容器已经停止

## 19. 本模式的局限

请务必知道这些限制：

- 你的开发电脑关机、休眠、断网，服务就会中断
- 这不是正式云服务器
- 这不是多机分布式部署
- Java 和 Python 当前仍依赖共享挂载目录
- 性能和稳定性取决于你的本机资源

但作为“先让别人能打开、能演示、能联调”的临时方案，它已经足够实用。

## 20. 最后建议

如果你当前目标是：

- 先让别人能通过网络访问页面
- 先在局域网里演示完整流程
- 先避免前后端跨域和登录态问题

那么最稳妥的方式就是：

- 使用本机服务器模式
- 使用统一 Gateway 入口
- 保持前端 API 为 `/api`
- 只把 `http://你的局域网IP:18080/` 给其他人
