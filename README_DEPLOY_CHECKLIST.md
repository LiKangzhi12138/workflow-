# workflow-platform 云服务器实操检查单

这是一份面向首次上云试装的实操检查单。

使用方式：

- 一步一步照着执行
- 每完成一项就打勾
- 如果某一步失败，先不要继续，先看本清单后面的排障项

适用范围：

- 单台 Linux 云服务器
- Docker Compose 部署
- Nginx + 前端静态资源 + Java + Python + MySQL
- Java 与 Python 共享宿主机挂载目录

---

## 0. 部署目标确认

- [ ] 本次目标是“单机云部署版”，不是多机分布式部署
- [ ] 已接受当前版本仍使用共享存储目录
- [ ] 已确认云服务器系统建议为 Ubuntu 22.04 LTS 或 24.04 LTS
- [ ] 已确认服务器至少 4 核 CPU、8 GB 内存、80 GB 磁盘

---

## 1. 登录服务器后的基础检查

- [ ] 登录服务器

```bash
ssh root@你的云服务器IP
```

- [ ] 确认系统版本

```bash
cat /etc/os-release
uname -a
```

- [ ] 确认磁盘空间够用

```bash
df -h
```

- [ ] 确认 80 端口没有被别的服务占用

```bash
ss -ltnp | grep ':80 '
```

- [ ] 如果需要暴露 MySQL，也确认 3306 端口是否已占用

```bash
ss -ltnp | grep ':3306 '
```

---

## 2. 安装基础软件

- [ ] 更新系统包索引

```bash
sudo apt-get update
```

- [ ] 安装 Git / curl / 证书

```bash
sudo apt-get install -y git curl ca-certificates
```

- [ ] 安装 Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
```

- [ ] 当前用户加入 docker 组

```bash
sudo usermod -aG docker "$USER"
newgrp docker
```

- [ ] 验证 Docker

```bash
docker version
docker compose version
```

---

## 3. 创建部署目录

- [ ] 创建项目根目录

```bash
sudo mkdir -p /opt/workflow-platform
sudo chown -R "$USER":"$USER" /opt/workflow-platform
cd /opt/workflow-platform
```

- [ ] 创建运行期目录

```bash
sudo mkdir -p /opt/workflow-platform/storage
sudo mkdir -p /opt/workflow-platform/logs
sudo mkdir -p /opt/workflow-platform/mysql-data
sudo chown -R "$USER":"$USER" /opt/workflow-platform/storage /opt/workflow-platform/logs /opt/workflow-platform/mysql-data
```

- [ ] 验证目录可写

```bash
touch /opt/workflow-platform/storage/.write-test
touch /opt/workflow-platform/logs/.write-test
touch /opt/workflow-platform/mysql-data/.write-test
rm -f /opt/workflow-platform/storage/.write-test /opt/workflow-platform/logs/.write-test /opt/workflow-platform/mysql-data/.write-test
```

---

## 4. 拉取项目代码

- [ ] 进入部署目录并拉代码

```bash
cd /opt/workflow-platform
git clone <你的仓库地址> workflow-platform
cd /opt/workflow-platform/workflow-platform
```

- [ ] 确认关键部署文件存在

```bash
ls
ls frontend-web
ls service-java
ls service-python
ls scripts
ls docs/sql
```

- [ ] 重点确认以下文件存在

```text
docker-compose.yml
nginx.conf
.env.example
deploy.sh
README_DEPLOY.md
README_DEPLOY_CHECKLIST.md
scripts/preflight-check.sh
scripts/init-db.sh
scripts/smoke-test.sh
docs/sql/000_init_workflow_platform_schema.sql
frontend-web/Dockerfile
service-java/Dockerfile
service-python/Dockerfile
```

---

## 5. 生成并填写 `.env`

- [ ] 复制环境变量模板

```bash
cp .env.example .env
```

- [ ] 编辑 `.env`

```bash
nano .env
```

- [ ] 必改项已修改

必须至少改这些值：

- [ ] `MYSQL_PASSWORD`
- [ ] `MYSQL_ROOT_PASSWORD`
- [ ] `SPRING_DATASOURCE_PASSWORD`
- [ ] `PYTHON_CALLBACK_SECRET`
- [ ] `NGINX_SERVER_NAME`
- [ ] `SESSION_COOKIE_SECURE`

- [ ] 确认以下部署关键值正确

推荐值：

```text
VITE_API_BASE_URL=/api
PYTHON_SERVICE_BASE_URL=http://service-python:8000
JAVA_CALLBACK_BASE_URL=http://service-java:8080
JAVA_UPSTREAM_URL=http://service-java:8080
WORKFLOW_STORAGE_ROOT=/opt/workflow-platform/storage
HOST_STORAGE_ROOT=/opt/workflow-platform/storage
HOST_MYSQL_DATA_ROOT=/opt/workflow-platform/mysql-data
PYTHON_FILE_LOG_ENABLED=false
```

- [ ] 如果当前还没有 HTTPS，先保持

```text
SESSION_COOKIE_SECURE=false
```

- [ ] 如果未来挂 HTTPS，再改成

```text
SESSION_COOKIE_SECURE=true
```

---

## 6. 给脚本执行权限

- [ ] 赋予脚本执行权限

```bash
chmod +x deploy.sh scripts/*.sh
```

- [ ] 确认权限成功

```bash
ls -l deploy.sh scripts
```

---

## 7. 部署前自检

- [ ] 执行自检脚本

```bash
./deploy.sh preflight
```

- [ ] 自检重点确认这些项目已通过

- [ ] `.env` 存在
- [ ] Docker / Docker Compose 已安装
- [ ] 关键文件存在
- [ ] 关键环境变量非空
- [ ] 示例密码和示例密钥已替换
- [ ] 挂载目录存在且可写
- [ ] 80/3306 端口未冲突
- [ ] `docker compose config` 校验通过

- [ ] 如果自检失败，不继续部署，先修复失败项

---

## 8. 首次启动

- [ ] 启动服务

```bash
./deploy.sh up
```

- [ ] 查看容器状态

```bash
./deploy.sh ps
```

- [ ] 应看到以下服务

- [ ] `gateway`
- [ ] `service-java`
- [ ] `service-python`
- [ ] `mysql`

- [ ] 如果有服务不是 `Up`，先看日志

```bash
./deploy.sh logs
```

---

## 9. 空库初始化检查

- [ ] 确认这次是不是“全新空库首次启动”

说明：

- 如果 `HOST_MYSQL_DATA_ROOT` 是空目录，MySQL 首次启动应自动导入 schema
- 如果该目录不是空的，自动初始化脚本不会再次执行

- [ ] 检查数据库里是否已有表

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "USE $MYSQL_DATABASE; SHOW TABLES;"
```

- [ ] 如果没有表，手动执行初始化

```bash
./deploy.sh init-db
```

- [ ] 初始化完成后再次查看表

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "USE $MYSQL_DATABASE; SHOW TABLES;"
```

---

## 10. 健康检查

- [ ] 检查 Nginx 健康

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/healthz
```

期望结果：

```text
ok
```

- [ ] 检查 Java 健康

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/api/health
```

期望包含：

```json
"code":"OK"
```

- [ ] 检查 Python 健康

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/api/health/python
```

期望包含：

```json
"code":"OK"
```

- [ ] 检查 Java 容器能否访问 Python

```bash
docker compose exec service-java curl -fsS http://service-python:8000/internal/ping
```

---

## 11. 共享挂载目录检查

- [ ] 检查 Java 容器是否能访问共享目录

```bash
docker compose exec service-java sh -lc "test -d \"$WORKFLOW_STORAGE_ROOT\" && test -w \"$WORKFLOW_STORAGE_ROOT\""
```

- [ ] 检查 Python 容器是否能访问共享目录

```bash
docker compose exec service-python sh -lc "test -d \"$WORKFLOW_STORAGE_ROOT\" && test -w \"$WORKFLOW_STORAGE_ROOT\""
```

- [ ] 检查关键子目录是否被自动创建

```bash
find /opt/workflow-platform/storage -maxdepth 3 -type d | sort
```

至少应看到这些目录：

- [ ] `model-uploads`
- [ ] `models`
- [ ] `validation-cache`
- [ ] `federated-models`
- [ ] `python/results`
- [ ] `python/result-samples`
- [ ] `tmp/java-multipart`

---

## 12. 部署后冒烟测试

- [ ] 执行自动冒烟脚本

```bash
./deploy.sh smoke
```

- [ ] 确认自动冒烟覆盖了这些内容

- [ ] 前端首页返回 200
- [ ] Nginx 健康检查返回 200
- [ ] Java 健康检查返回 200
- [ ] Python 健康检查返回 200
- [ ] Java -> Python 网络调用通
- [ ] 共享挂载目录可用
- [ ] 可注册临时 SERVER 用户
- [ ] 可注册临时 CLIENT 用户
- [ ] 可登录并拿到会话
- [ ] 工作流列表接口可达
- [ ] 模型列表接口可达
- [ ] 服务端用户列表接口可达
- [ ] 上传初始化接口可达

---

## 13. 浏览器人工检查

- [ ] 浏览器访问首页

```text
http://你的公网IP/
```

- [ ] 首页可以正常打开，不是空白页，不是 502
- [ ] 页面刷新路由不会 404
- [ ] 登录/注册接口可正常使用

---

## 14. 最小业务闭环检查

- [ ] 注册一个 SERVER 账号
- [ ] 注册一个 CLIENT 账号
- [ ] 使用 CLIENT 登录
- [ ] 客户端模型管理页可打开
- [ ] 客户端工作流页可打开
- [ ] 创建一个工作流
- [ ] 选择 SERVER 参与者
- [ ] 上传或绑定一个客户端模型
- [ ] 使用 SERVER 登录
- [ ] 绑定服务端数据集
- [ ] 点击接受工作流
- [ ] 观察接收 / 解密 / 纳管 / 联邦聚合 / 验证链路
- [ ] 观察客户端进度页状态同步
- [ ] 观察服务器端进度页状态同步

---

## 15. 查看日志的方法

- [ ] 查看全部日志

```bash
./deploy.sh logs
```

- [ ] 单独查看网关日志

```bash
./deploy.sh logs gateway
```

- [ ] 单独查看 Java 日志

```bash
./deploy.sh logs service-java
```

- [ ] 单独查看 Python 日志

```bash
./deploy.sh logs service-python
```

- [ ] 单独查看 MySQL 日志

```bash
./deploy.sh logs mysql
```

---

## 16. 常见失败点快速排查

### 16.1 `./deploy.sh preflight` 失败

- [ ] 检查 `.env` 是否存在
- [ ] 检查示例密码是否未替换
- [ ] 检查挂载目录是否可写
- [ ] 检查 Docker / Compose 是否已安装
- [ ] 检查 80/3306 是否被占用

### 16.2 启动后 `gateway` 是 502/504

- [ ] 看 `gateway` 日志
- [ ] 看 `service-java` 日志
- [ ] 看 `service-python` 日志
- [ ] 检查 `JAVA_UPSTREAM_URL`
- [ ] 检查 `service-java` 是否 healthy

### 16.3 Java 连接数据库失败

- [ ] 检查 `SPRING_DATASOURCE_URL`
- [ ] 检查 `SPRING_DATASOURCE_USERNAME`
- [ ] 检查 `SPRING_DATASOURCE_PASSWORD`
- [ ] 检查 `MYSQL_PASSWORD`
- [ ] 检查 `MYSQL_ROOT_PASSWORD`
- [ ] 检查数据库是否已初始化

### 16.4 Java 调 Python 失败

- [ ] 检查 `PYTHON_SERVICE_BASE_URL`
- [ ] 检查 Python 容器是否 healthy
- [ ] 检查 Java 容器内能否 `curl http://service-python:8000/internal/ping`

### 16.5 Python 回调 Java 失败

- [ ] 检查 `JAVA_CALLBACK_BASE_URL`
- [ ] 检查 `PYTHON_CALLBACK_SECRET`
- [ ] 检查 Java 内部接口日志

### 16.6 上传失败或超时

- [ ] 检查 `VITE_API_TIMEOUT_MS`
- [ ] 检查 `CLIENT_MAX_BODY_SIZE`
- [ ] 检查 `MAX_UPLOAD_SIZE`
- [ ] 检查 `MAX_REQUEST_SIZE`
- [ ] 检查宿主机磁盘空间
- [ ] 检查 `tmp/java-multipart` 是否存在并可写

### 16.7 页面能打开但登录会话异常

推荐方式：

- [ ] 前后端同域访问
- [ ] `VITE_API_BASE_URL=/api`
- [ ] `APP_ALLOWED_ORIGINS=` 保持空

如果上了 HTTPS：

- [ ] `SESSION_COOKIE_SECURE=true`

---

## 17. 更新与重启

- [ ] 更新代码

```bash
git pull
```

- [ ] 重新做自检

```bash
./deploy.sh preflight
```

- [ ] 重新部署

```bash
./deploy.sh up
```

- [ ] 查看状态

```bash
./deploy.sh ps
```

---

## 18. 备份检查

- [ ] 已确认需要备份以下目录

- [ ] `/opt/workflow-platform/storage`
- [ ] `/opt/workflow-platform/logs`
- [ ] `/opt/workflow-platform/mysql-data`

- [ ] 已制定数据库备份方式

例如：

```bash
docker compose exec mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" > workflow_platform_backup.sql
```

---

## 19. 本次试装完成判断标准

只有满足以下条件，才算“本次云服务器试装成功”：

- [ ] `./deploy.sh preflight` 通过
- [ ] `./deploy.sh up` 成功
- [ ] `./deploy.sh ps` 中 4 个服务均正常
- [ ] `/healthz` 正常
- [ ] `/api/health` 正常
- [ ] `/api/health/python` 正常
- [ ] 数据库已初始化成功
- [ ] `./deploy.sh smoke` 通过
- [ ] 浏览器可打开首页
- [ ] 登录会话正常
- [ ] 能完成一次最小工作流闭环

---

## 20. 当前版本的已知局限

这份检查单对应的是“快速上云版”，不是最终形态。

已知局限：

- [ ] Java 与 Python 仍共享宿主机目录
- [ ] Python 任务仍是进程内运行，不是独立任务队列
- [ ] 当前仍是单机架构，不是多机分布式
- [ ] 认证仍以 Session 为主，不是统一 OAuth/JWT 架构

这些局限不影响本次云服务器试装。
