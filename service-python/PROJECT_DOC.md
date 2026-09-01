# Workflow Python Service Documentation

## 项目概述

这是一个基于 FastAPI 的 Python 服务，名为 "workflow-python-service"。该服务主要用于处理联邦学习（Federated Learning）相关的作业（Jobs），模拟训练过程，并与 Java 项目进行回调集成。服务支持作业的创建、状态查询、异步执行，并通过回调机制通知 Java 端作业进度和结果。

### 主要功能
- 提供健康检查接口
- 支持作业创建和状态查询
- 模拟联邦学习训练流程
- 持久化作业状态和结果
- 与 Java 项目进行签名回调

### 技术栈
- **框架**: FastAPI
- **服务器**: Uvicorn
- **数据模型**: Pydantic
- **存储**: JSON 文件持久化
- **回调**: HTTP POST 请求，支持签名验证

## 接口说明

### 健康检查接口
- **GET /health**
  - 描述: 检查服务健康状态
  - 响应:
    ```json
    {
      "code": "OK",
      "message": "python ok"
    }
    ```

- **GET /internal/ping**
  - 描述: 内部 ping 接口
  - 响应:
    ```json
    {
      "code": "OK",
      "message": "python ok"
    }
    ```

### 作业管理接口

#### 创建作业
- **POST /internal/jobs**
  - 描述: 创建一个新的作业
  - 请求体: `CreateJobRequest`
    ```json
    {
      "jobId": "string",
      "workflowId": "integer",
      "modelPath": "string",
      "datasetPath": "string",
      "algorithmType": "string",
      "callbackUrl": "string",
      "callbackSecret": "string"
    }
    ```
  - 响应: `CreateJobResponse`
    ```json
    {
      "code": "OK",
      "message": "job accepted",
      "data": {
        "jobId": "string",
        "status": "ACCEPTED"
      }
    }
    ```

#### 查询作业状态
- **GET /internal/jobs/{jobId}**
  - 描述: 获取指定作业的状态
  - 参数: jobId (路径参数)
  - 响应: `JobStatusResponse`
    ```json
    {
      "code": "OK",
      "message": "success",
      "data": {
        "jobId": "string",
        "workflowId": "integer",
        "status": "string",
        "progress": "integer",
        "message": "string",
        "resultFilePath": "string (optional)",
        "errorMessage": "string (optional)"
      }
    }
    ```

### 回调接口 (Java 端)
Java 项目需要提供回调接口接收作业状态更新。回调请求格式:
- **POST {callbackUrl}**
  - 请求体:
    ```json
    {
      "jobId": "string",
      "workflowId": "integer",
      "status": "string",
      "progress": "integer",
      "message": "string",
      "metrics": "object (optional)",
      "resultFile": "object (optional)",
      "errorMessage": "string (optional)",
      "sign": "string (signature)"
    }
    ```
  - 签名生成: SHA256(JSON.dumps(payload, sort_keys=True) + secret)

## 项目架构

### 目录结构
```
service-python/
├── README.md (空)
├── requirement.txt (依赖文件)
├── test_env.py (环境测试脚本)
├── test_pressure.py (压力测试脚本)
└── app/
    ├── __init__.py
    ├── main.py (应用入口)
    ├── api/
    │   ├── health.py (健康检查路由)
    │   └── jobs.py (作业管理路由)
    ├── core/
    │   ├── config.py (配置管理)
    │   ├── constants.py (常量定义)
    │   └── logger.py (日志配置)
    ├── models/
    │   ├── request_models.py (请求数据模型)
    │   └── response_models.py (响应数据模型)
    ├── services/
    │   ├── callback_service.py (回调服务)
    │   ├── job_service.py (作业服务)
    │   ├── mock_federated_service.py (模拟联邦学习服务)
    │   ├── persistence_service.py (持久化服务)
    │   └── result_service.py (结果服务)
    ├── storage/
    │   └── results/ (结果文件存储目录)
    └── workers/
        └── task_runner.py (异步任务执行器)
```

### 核心组件说明

#### app/main.py
- 应用入口文件
- 创建 FastAPI 实例
- 注册路由器 (health, jobs)
- 定义内部 ping 接口

#### app/api/health.py
- 健康检查路由
- 提供 /health 端点

#### app/api/jobs.py
- 作业管理路由
- 提供创建作业和查询状态的端点
- 使用 Pydantic 模型进行数据验证

#### app/models/request_models.py
- 定义请求数据模型
- `CreateJobRequest`: 作业创建请求结构

#### app/models/response_models.py
- 定义响应数据模型
- `CreateJobResponse`: 作业创建响应
- `JobStatusResponse`: 作业状态查询响应
- `JobStatusData`: 作业状态数据结构

#### app/services/job_service.py
- 作业状态管理服务
- 提供作业创建、查询、更新功能
- 实现状态转换校验和幂等性检查
- 使用内存存储 + 磁盘持久化

#### app/services/persistence_service.py
- 数据持久化服务
- 将作业数据保存到 JSON 文件
- 从磁盘加载作业数据

#### app/services/result_service.py
- 结果文件生成服务
- 保存训练结果到 JSON 文件
- 生成模拟的评估指标

#### app/services/callback_service.py
- 回调服务
- 向 Java 项目发送 HTTP POST 回调
- 支持签名验证和重试机制

#### app/services/mock_federated_service.py
- 模拟联邦学习服务
- 模拟训练过程的不同阶段
- 更新作业状态

#### app/core/config.py
- 配置管理
- 从环境变量加载配置
- 包括应用设置、回调配置、存储路径等

#### app/core/constants.py
- 常量定义
- 作业状态转换规则

#### app/core/logger.py
- 日志配置
- 基础日志设置

#### app/workers/task_runner.py
- 异步任务执行器
- 使用线程异步执行作业
- 模拟训练流程，更新状态，生成结果，发送回调

#### app/storage/results/
- 结果文件存储目录
- 保存每个作业的训练结果 JSON 文件

### 作业状态流转
- ACCEPTED → PREPARING → TRAINING_RUNNING → VALIDATING → COMPLETED/FAILED
- 状态转换有校验机制

### 持久化机制
- 作业数据存储在内存字典中
- 定期保存到 `jobs.json` 文件
- 应用启动时从文件加载

### 回调机制
- 作业状态更新时自动回调 Java 项目
- 使用 SHA256 签名确保安全性
- 支持重试机制 (最多 3 次)

## 环境配置
需要设置以下环境变量:
- APP_NAME: 应用名称
- APP_HOST: 应用主机
- APP_PORT: 应用端口
- JAVA_CALLBACK_BASE: Java 回调基础 URL
- JAVA_CALLBACK_SECRET: 回调签名密钥
- PY_STORAGE_ROOT: Python 服务存储根目录
- PY_LOG_LEVEL: 日志级别
- PY_JOB_TIMEOUT_SECONDS: 作业超时时间

## 运行方式
1. 安装依赖: `pip install -r requirement.txt`
2. 设置环境变量
3. 运行服务: `uvicorn app.main:app --host 0.0.0.0 --port 8000`

## 测试
- `test_env.py`: 检查 Python 环境
- `test_pressure.py`: 压力测试脚本，模拟多线程创建作业</content>
<parameter name="filePath">D:\workspace\workflow-platform\service-python\PROJECT_DOC.md
