# workflow-platform Single-Host Cloud Deployment Guide

This document describes the fastest practical way to deploy the current `workflow-platform` project on **one Linux cloud server**.

This is a **single-host cloud edition**:
- Frontend is served by Nginx.
- Java backend is exposed through Nginx reverse proxy.
- Python service is called by Java over the Docker internal network.
- MySQL runs on the same host.
- Java and Python share one mounted storage directory on the same host.

This is not the final distributed architecture. It is the quickest version that is suitable for a real cloud trial installation.

## 1. Recommended server baseline

- OS: Ubuntu 22.04 LTS or Ubuntu 24.04 LTS
- CPU: 4 cores or more
- Memory: at least 8 GB, 16 GB recommended
- Disk: at least 80 GB
- Network: public IP or domain name that can reach ports `80` and optionally `3306`

Notes:
- The current Python image uses CPU PyTorch by default.
- If you need GPU later, treat that as a separate deployment upgrade.

## 2. Deployment architecture

The deployed services are:

- `gateway`: Nginx, serves frontend static files and proxies `/api/*` to Java
- `service-java`: Spring Boot backend
- `service-python`: FastAPI service for validation and federated aggregation
- `mysql`: MySQL 8.4

Traffic flow:

1. Browser -> `gateway`
2. `gateway` -> `service-java` for `/api/*`
3. `service-java` -> `service-python` by HTTP over Docker network
4. `service-python` -> `service-java` callback by HTTP over Docker network
5. `service-java` and `service-python` both use the same mounted storage root

Recommended access mode:

- Use **same-origin deployment** through Nginx
- Keep `VITE_API_BASE_URL=/api`
- Keep `APP_ALLOWED_ORIGINS` empty unless you truly need cross-origin browser access

This avoids most Session, Cookie, and CORS problems.

## 3. Project files used by deployment

Main deployment files:

- `docker-compose.yml`
- `nginx.conf`
- `.env.example`
- `deploy.sh`
- `scripts/preflight-check.sh`
- `scripts/init-db.sh`
- `scripts/smoke-test.sh`
- `docs/sql/000_init_workflow_platform_schema.sql`
- `frontend-web/Dockerfile`
- `service-java/Dockerfile`
- `service-python/Dockerfile`

## 4. Install prerequisites on Ubuntu

```bash
sudo apt-get update
sudo apt-get install -y git curl ca-certificates
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker compose version
```

Optional but useful:

```bash
sudo apt-get install -y jq
```

## 5. Suggested host directory layout

Recommended project home:

```bash
sudo mkdir -p /opt/workflow-platform
sudo chown -R "$USER":"$USER" /opt/workflow-platform
cd /opt/workflow-platform
```

Recommended runtime directories:

```bash
sudo mkdir -p /opt/workflow-platform/storage
sudo mkdir -p /opt/workflow-platform/logs
sudo mkdir -p /opt/workflow-platform/mysql-data
sudo chown -R "$USER":"$USER" /opt/workflow-platform/storage /opt/workflow-platform/logs /opt/workflow-platform/mysql-data
```

The application will create subdirectories automatically, but the three host roots above should exist and be writable before the first startup.

## 6. Clone the project

```bash
cd /opt/workflow-platform
git clone <your-repo-url> workflow-platform
cd workflow-platform
```

## 7. Create the `.env` file

```bash
cp .env.example .env
```

Open `.env` and replace at least the following values:

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `SPRING_DATASOURCE_PASSWORD`
- `PYTHON_CALLBACK_SECRET`
- `NGINX_SERVER_NAME`
- `SESSION_COOKIE_SECURE`

Important `.env` notes:

- `PUBLIC_HTTP_PORT`: external HTTP port, usually `80`
- `MYSQL_HOST_PORT`: host MySQL port, can stay `3306`
- `SPRING_DATASOURCE_URL`: should point to `mysql:3306` inside Docker unless you intentionally use an external database
- `PYTHON_SERVICE_BASE_URL`: should stay `http://service-python:8000`
- `JAVA_CALLBACK_BASE_URL`: should stay `http://service-java:8080`
- `VITE_API_BASE_URL`: should stay `/api` for same-origin deployment
- `WORKFLOW_STORAGE_ROOT`: shared in-container storage root
- `HOST_STORAGE_ROOT`: host path mounted to `WORKFLOW_STORAGE_ROOT`
- `HOST_MYSQL_DATA_ROOT`: host path for MySQL data volume
- `SPRING_SERVLET_MULTIPART_LOCATION`: Java multipart temp directory, should stay under shared storage
- `PYTHON_FILE_LOG_ENABLED`: defaults to `false`; keep it disabled to use console-only Python logging

For HTTPS later:

- set `SESSION_COOKIE_SECURE=true`

## 8. Database initialization strategy

This repository now includes a full schema file:

- `docs/sql/000_init_workflow_platform_schema.sql`

There are two supported ways to initialize the database.

### 8.1 Automatic initialization on an empty MySQL volume

When `mysql` starts for the first time with an empty `HOST_MYSQL_DATA_ROOT`, the MySQL container will run:

- `docker/mysql/init/000_init_workflow_platform_schema.sh`

That script imports:

- `docs/sql/000_init_workflow_platform_schema.sql`

This is the recommended first-install path.

### 8.2 Manual initialization after containers are up

If you need to initialize or re-initialize manually:

```bash
./deploy.sh init-db
```

Safety note:

- `scripts/init-db.sh` refuses to import into a non-empty database unless you set `ALLOW_NONEMPTY_DB_INIT=1`

## 9. Preflight check before first startup

Run:

```bash
chmod +x deploy.sh scripts/*.sh
./deploy.sh preflight
```

The preflight script checks:

- `.env` exists
- required deployment files exist
- required environment variables are filled
- placeholder secrets are replaced
- Docker and Docker Compose are installed
- host mount directories exist and are writable
- gateway and MySQL host ports are not already occupied
- `docker compose config` is valid

Do not skip this step on a fresh server.

## 10. First startup

Run:

```bash
./deploy.sh up
```

This does:

1. preflight check
2. host directory creation
3. `docker compose up -d --build`

Check service status:

```bash
./deploy.sh ps
```

Expected services:

- `gateway`
- `service-java`
- `service-python`
- `mysql`

## 11. View logs

All services:

```bash
./deploy.sh logs
```

Single service:

```bash
./deploy.sh logs gateway
./deploy.sh logs service-java
./deploy.sh logs service-python
./deploy.sh logs mysql
```

Java, Python, Nginx, and MySQL write operational logs to container stdout/stderr. Docker rotates each service log at `10m` with at most `3` files. Use `docker compose logs` for troubleshooting; no host log directory is required.

## 12. Health checks after startup

Check Nginx:

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/healthz
```

Expected:

```text
ok
```

Check Java:

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/api/health
```

Expected JSON:

```json
{"code":"OK","message":"java ok"}
```

Check Python via Java:

```bash
curl http://127.0.0.1:${PUBLIC_HTTP_PORT}/api/health/python
```

Inside Docker, Java should also be able to reach Python directly:

```bash
docker compose exec service-java curl -fsS http://service-python:8000/internal/ping
```

## 13. Shared storage check

Both Java and Python must see the same in-container storage root:

- `WORKFLOW_STORAGE_ROOT`

The important storage subpaths are:

- `model-uploads`
- `models`
- `validation-cache`
- `federated-models`
- `python/results`
- `python/result-samples`
- `tmp/java-multipart`

Quick verification:

```bash
docker compose exec service-java sh -lc "test -d \"$WORKFLOW_STORAGE_ROOT\" && test -w \"$WORKFLOW_STORAGE_ROOT\""
docker compose exec service-python sh -lc "test -d \"$WORKFLOW_STORAGE_ROOT\" && test -w \"$WORKFLOW_STORAGE_ROOT\""
```

## 14. Smoke test after deployment

Run:

```bash
./deploy.sh smoke
```

The smoke test checks:

- Nginx responds
- frontend home returns HTML
- Java health endpoint returns OK
- Python health endpoint returns OK
- Java container can reach Python
- Java and Python containers can access shared storage
- register a temporary SERVER user
- register a temporary CLIENT user
- login as the temporary CLIENT user
- workflow list endpoint is reachable
- model list endpoint is reachable
- server list endpoint is reachable
- upload-init endpoint is reachable

If `./deploy.sh smoke` passes, the single-host deployment is ready for a first business test.

## 15. Manual minimal business test

After smoke test passes:

1. Open `http://your-server-ip/`
2. Register one SERVER account and one CLIENT account
3. Login as CLIENT
4. Upload or bind a local model
5. Create a workflow and select a SERVER participant
6. Login as SERVER
7. Bind the server dataset
8. Accept the workflow
9. Verify that decryption, model import, federated aggregation, and validation progress are visible

## 16. Common operations

Stop all services:

```bash
./deploy.sh down
```

Restart everything:

```bash
./deploy.sh restart
```

Show rendered Compose config:

```bash
./deploy.sh config
```

Restart one service only:

```bash
docker compose restart gateway
docker compose restart service-java
docker compose restart service-python
docker compose restart mysql
```

## 17. Update and redeploy

When code changes:

```bash
git pull
./deploy.sh preflight
./deploy.sh up
```

If images must be rebuilt from scratch:

```bash
docker compose build --no-cache
./deploy.sh up
```

## 18. Backup recommendations

Back up these host directories:

- `${HOST_STORAGE_ROOT}`
- `${HOST_MYSQL_DATA_ROOT}`

Recommended backup methods:

- MySQL: `mysqldump`
- Storage: `tar`, `rsync`, or snapshot backup

## 19. Change domain name or public IP

If you change the access domain or public IP:

1. update `.env`
2. set `NGINX_SERVER_NAME`
3. restart the stack

```bash
./deploy.sh restart
```

For HTTPS, also set:

- `SESSION_COOKIE_SECURE=true`

## 20. Troubleshooting

### 20.1 502 or 504 from Nginx

Check:

```bash
docker compose ps
docker compose logs gateway
docker compose logs service-java
docker compose logs service-python
```

Typical causes:

- Java container not healthy
- Python container not healthy
- wrong upstream address
- long-running request hitting timeout

### 20.2 Java cannot connect to MySQL

Check:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- whether `mysql` is healthy

Useful command:

```bash
docker compose logs service-java | tail -n 100
```

### 20.3 Java cannot call Python

Check:

- `PYTHON_SERVICE_BASE_URL`
- `service-python` container health
- Java logs
- Python logs

Recommended internal value:

```text
http://service-python:8000
```

### 20.4 Python callback to Java fails

Check:

- `JAVA_CALLBACK_BASE_URL`
- `PYTHON_CALLBACK_SECRET`
- `service-java` health

Recommended internal value:

```text
http://service-java:8080
```

### 20.5 Upload fails or times out

Check all four limits together:

- `VITE_API_TIMEOUT_MS`
- `CLIENT_MAX_BODY_SIZE`
- `MAX_UPLOAD_SIZE`
- `MAX_REQUEST_SIZE`

Also verify:

- shared storage is writable
- Java multipart temp directory exists
- host disk has enough free space

### 20.6 Database is empty but tables are missing

If the MySQL volume was not empty on first startup, auto-init scripts may not run.

Use:

```bash
./deploy.sh init-db
```

### 20.7 Session login works locally but not through the public address

Recommended deployment mode is same-origin through Nginx:

- frontend and API from the same domain
- `VITE_API_BASE_URL=/api`
- `APP_ALLOWED_ORIGINS=` left empty

For HTTPS:

- set `SESSION_COOKIE_SECURE=true`

## 21. Current limitations of this fast cloud edition

This version is intentionally pragmatic, not final-form distributed infrastructure.

Known limitations:

- Java and Python still share one host-mounted storage directory
- Python jobs still run in-process, not through a dedicated queue system
- authentication is still session-based, not JWT/OAuth
- this is a single-host architecture, not multi-host or Kubernetes

## 22. Recommended upgrade path later

If the project grows beyond the single-host cloud edition, the next upgrades should be:

1. move model/result exchange to object storage
2. separate Python job execution into queue plus worker
3. move from session auth to a more centralized auth model if needed
4. add metrics, tracing, and retention policies
5. split deployment into multi-host or container orchestration only after the single-host flow is stable
