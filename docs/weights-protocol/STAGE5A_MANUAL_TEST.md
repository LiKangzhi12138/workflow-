# Stage 5A Manual Test (Windows CMD)

This procedure validates only InternImage Runtime, trusted ModelDefinition,
architecture-only self-check, and AVAILABLE. Do not upload weights or run
FedAvg/Validation in Stage 5A.

## 1. Open The Project And Back Up Configuration

Open Windows CMD:

```cmd
cd /d D:\workspace\workflow-platform
copy .env.local-server .env.local-server.stage5a.bak
```

Do not print the file. Open it locally:

```cmd
notepad .env.local-server
```

Add or update these lines:

```text
INTERNIMAGE_RUNTIME_V1_ENABLED=true
INTERNIMAGE_RUNTIME_PIP_INDEX_URL=https://pypi.org/simple
INTERNIMAGE_RUNTIME_BASE_URL=http://internimage-runtime:8020
INTERNIMAGE_RUNTIME_CONNECT_TIMEOUT_SECONDS=2
INTERNIMAGE_RUNTIME_READ_TIMEOUT_SECONDS=60
INTERNIMAGE_RUNTIME_CUDA_ARCH_LIST=12.0
INTERNIMAGE_RUNTIME_MAX_JOBS=4
MODEL_DEFINITION_WORKFLOW_SELECTABLE_RUNTIME_PROFILES=YOLO_RUNTIME_V1
```

The last line is intentional. It allows InternImage to appear in the SERVER
AVAILABLE response while preventing workflow creation until Stage 5B. Do not
change existing secrets.

If official PyPI is unreachable from Docker, only the InternImage build index
may be temporarily changed to the verified mirror:

```text
INTERNIMAGE_RUNTIME_PIP_INDEX_URL=https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple
```

## 2. Parse Compose Without Starting Containers

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
```

Expected: no output and exit code 0.

## 3. Build Only Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime build --progress=plain internimage-runtime
```

The InternImage build compiles MMCV CUDA ops and DCNv3, so it can take much
longer than the other services. It must not download a pretrained model or copy
anything from `federated-weights-lab`.

Before native compilation, the output must contain:

```text
MMCV source audit passed: effectiveCpp14Flags=0, cpp17Flags=6, cpuExtensionCxx17=true, cudaExtensionCxx17=true, scatterTorch27=true
timm source audit passed: timmVersion=0.6.11, python311CompatibilityPatch=true, mutableDataclassDefaults=0, defaultFactories=2
```

The later MMCV CPU and CUDA compile commands must contain `-std=c++17` and must
not contain an effective `-std=c++14`. Ninja should report `MAX_JOBS (4)` unless
you intentionally override `INTERNIMAGE_RUNTIME_MAX_JOBS`.

After that succeeds, build the dispatch/control services:

```cmd
docker compose --env-file .env.local-server --profile model-runtime build service-python service-java gateway
```

## 4. Start The Updated Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d internimage-runtime service-python service-java gateway
```

Do not run `docker compose down -v`.

Check status:

```cmd
docker compose --env-file .env.local-server --profile model-runtime ps
```

Expected: `internimage-runtime`, `service-python`, `service-java`, `yolo-runtime`,
`mysql`, and `gateway` are healthy. The InternImage health start period is 180
seconds because CUDA extension initialization can be slower on first start.

## 5. Verify Dependency And GPU Health

Run the check inside the runtime:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec internimage-runtime python -c "import torch,torchvision,mmcv,mmseg,timm,DCNv3; print('python runtime OK'); print('torch=',torch.__version__); print('torchvision=',torchvision.__version__); print('cudaBuild=',torch.version.cuda); print('cudaAvailable=',torch.cuda.is_available()); print('gpu=',torch.cuda.get_device_name(0)); print('mmcv=',mmcv.__version__); print('mmseg=',mmseg.__version__); print('timm=',timm.__version__); print('DCNv3=',DCNv3.__name__)"
```

Expected core values:

```text
torch=2.7.1+cu128
torchvision=0.22.1+cu128
cudaBuild=12.8
cudaAvailable=True
mmcv=1.6.2
mmseg=0.27.0
timm=0.6.11
DCNv3=DCNv3
```

Get the formal health response through the Docker service network:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,requests; r=requests.get('http://internimage-runtime:8020/internal/runtime/health',timeout=30); print(r.status_code); print(json.dumps(r.json(),indent=2))"
```

Required evidence:

```text
runtimeProfileId=INTERNIMAGE_RUNTIME_V1
probeContext=CONTAINER_RUNTIME
runtimeReady=true
cudaAvailable=true
gpuAvailable=true
dcnv3Available=true
dcnv3SelfCheckPassed=true
minimalForwardCheckPassed=true
python311CompatibilityPatch=true
```

`mmengineAvailable=false` is expected for this MMCV 1.6.2 / MMSeg 0.27.0
stack. The response must explain that in `warnings`, not `errors`.

## 6. Run Definition Self-check Without A Checkpoint

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,requests; d=json.load(open('/app/app/model_definitions/INTERNIMAGE_T_UPERNET_WHEAT_V1.json',encoding='utf-8')); r=requests.post('http://internimage-runtime:8020/internal/model-definitions/check',json={'modelDefinition':d,'runtimeProfileId':'INTERNIMAGE_RUNTIME_V1'},timeout=180); print(r.status_code); print(json.dumps(r.json(),indent=2,ensure_ascii=False))"
```

Required evidence:

```text
available=true
selfCheckPassed=true
tensorCount=728
elementCount=58963958
checkpointUsed=false
baseCheckpointUsed=false
pretrainedUsed=false
constructionMode=TRUSTED_CONFIG_TEMPLATE
```

No `.pth`, `.pt`, pretrained file, or base checkpoint is used by this request.

## 7. Insert The Definition Manually

Codex did not execute SQL. Copy the idempotent seed into the MySQL container:

```cmd
docker compose --env-file .env.local-server cp docs\weights-protocol\STAGE5A_SQL.sql mysql:/tmp/STAGE5A_SQL.sql
```

Execute it using the database credentials already inside the container:

```cmd
docker compose --env-file .env.local-server exec -T mysql sh -c "mysql --user=$MYSQL_USER --password=$MYSQL_PASSWORD $MYSQL_DATABASE < /tmp/STAGE5A_SQL.sql"
```

Read the inserted row without changing data:

```cmd
docker compose --env-file .env.local-server exec mysql sh -c "mysql --user=$MYSQL_USER --password=$MYSQL_PASSWORD $MYSQL_DATABASE -e 'SELECT id,code,model_family,model_version,variant,task_type,framework,framework_version,runtime_profile_id,class_count,ignore_index,status,architecture_signature,definition_sha256 FROM model_definition WHERE code IN (\"YOLOV8N_SHEEP_V1\",\"INTERNIMAGE_T_UPERNET_WHEAT_V1\") ORDER BY id;'"
```

Expected InternImage hashes:

```text
architecture_signature = be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f
definition_sha256       = f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700
```

## 8. Verify Java AVAILABLE

Log in as SERVER in the normal frontend and inspect the model request in browser
developer tools. Direct address-bar navigation may not carry the bearer token.
The API URL is:

```text
http://127.0.0.1/api/model-definitions/available
```

If the configured public port is not 80, append that port. For a direct Windows
CMD request, use the SERVER bearer token from the normal login response:

```cmd
curl -s -H "Authorization: Bearer <SERVER_ACCESS_TOKEN>" http://127.0.0.1/api/model-definitions/available
```

Expected entries:

```text
YOLOV8N_SHEEP_V1
  availability.available=true
  workflowSelectable=true

INTERNIMAGE_T_UPERNET_WHEAT_V1
  availability.available=true
  workflowSelectable=false
```

`workflowSelectable=false` is the Stage 5A boundary. The client creation page
must not offer InternImage yet.

## 9. Verify YOLO Isolation

Check YOLO health from service-python:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import requests; r=requests.get('http://yolo-runtime:8010/internal/runtime/health',timeout=30); print(r.status_code,r.json().get('runtimeReady'))"
```

Expected: `200 True`.

Stop only InternImage Runtime:

```cmd
docker compose --env-file .env.local-server --profile model-runtime stop internimage-runtime
```

Call `/api/model-definitions/available` again. Expected:

```text
YOLOV8N_SHEEP_V1 remains AVAILABLE
INTERNIMAGE_T_UPERNET_WHEAT_V1 is absent / NOT AVAILABLE
```

Start it again:

```cmd
docker compose --env-file .env.local-server --profile model-runtime start internimage-runtime
```

Wait until healthy and repeat the availability call.

## 10. Optional Golden Strict-load Certification

This is separate from daily AVAILABLE. It does not make the health endpoint
load weights. Use the frozen safe weights-only artifact only if it is still at
the documented lab path:

```cmd
docker compose --env-file .env.local-server cp D:\workspace\federated-weights-lab\internimage\artifacts\client_a\weights_only.pth internimage-runtime:/tmp/weights_only.pth
docker compose --env-file .env.local-server cp service-python\app\model_definitions\INTERNIMAGE_T_UPERNET_WHEAT_V1.json internimage-runtime:/tmp/model_definition.json
docker compose --env-file .env.local-server exec internimage-runtime python /app/certify_runtime.py --definition /tmp/model_definition.json --weights /tmp/weights_only.pth
```

Expected:

```text
728/728 keys
58,963,958 elements
missing keys = 0
unexpected keys = 0
strict load = PASS
weights_only = true
checkpoint/base/pretrained used = false
```

Remove only these manually copied temporary certification files afterward:

```cmd
docker compose --env-file .env.local-server exec --user root internimage-runtime rm -f /tmp/weights_only.pth /tmp/model_definition.json
```

## 11. Logs And Acceptance

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 internimage-runtime
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 service-python
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 service-java
```

The acceptance evidence must show:

```text
InternImage runtimeReady=true
DCNv3 CUDA forward PASS
architecture-only build PASS
728 tensors / 58,963,958 elements
checkpointUsed=false
baseCheckpointUsed=false
pretrainedUsed=false
InternImage AVAILABLE
YOLO AVAILABLE
InternImage hidden/rejected for workflow creation
```

Stop here. Do not upload InternImage weights, run InternImage FedAvg, or start
InternImage validation. Those belong to Stage 5B and Stage 5C.

## Rollback

Set the feature flag back to false and rebuild/restart only service-python:

```text
INTERNIMAGE_RUNTIME_V1_ENABLED=false
```

```cmd
docker compose --env-file .env.local-server --profile model-runtime build service-python
docker compose --env-file .env.local-server --profile model-runtime up -d service-python
```

The seeded Definition may remain ENABLED in the database; with the runtime
profile disabled it fails closed and is not returned as AVAILABLE. No schema or
historical workflow rollback is needed.
