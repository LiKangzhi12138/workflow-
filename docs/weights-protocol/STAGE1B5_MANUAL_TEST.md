# Stage 1B.5 Manual Docker Test (Windows CMD)

Run every command below in **Windows CMD**, not PowerShell. Do not run `docker compose down -v`; that command can delete database data.

## 1. Open The Project

```cmd
cd /d D:\workspace\workflow-platform
```

## 2. Back Up The Active Environment File

```cmd
copy /Y .env.local-server .env.local-server.stage1b5.backup
```

Do not publish either file. Open the active file:

```cmd
notepad .env.local-server
```

Add or update these non-secret settings, then save:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS=2
YOLO_RUNTIME_READ_TIMEOUT_SECONDS=10
```

Leave `WEIGHTS_PROTOCOL_V1_ENABLED` disabled. Do not change database credentials or callback secrets.

## 3. Validate Compose Without Starting Containers

```cmd
docker compose --env-file .env.local-server --profile model-runtime config > NUL
```

The command must return exit code 0:

```cmd
echo %ERRORLEVEL%
```

## 4. Build Updated Services

This build includes the new Runtime, the updated Python dispatcher, and the Stage 1B Java code:

```cmd
docker compose --env-file .env.local-server --profile model-runtime build yolo-runtime service-python service-java
```

## 5. Start Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d yolo-runtime service-python service-java gateway
```

Do not use `down -v`.

## 6. Check Containers

```cmd
docker compose --env-file .env.local-server --profile model-runtime ps
```

Expected services include `mysql`, `service-java`, `service-python`, `yolo-runtime`, and `gateway`. The Runtime may take about one minute to become healthy.

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 100 yolo-runtime
```

## 7. Check Runtime GPU And Versions

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec yolo-runtime python -c "import platform,torch,torchvision,ultralytics; print('python=',platform.python_version()); print('torch=',torch.__version__); print('torchvision=',torchvision.__version__); print('ultralytics=',ultralytics.__version__); print('cuda_build=',torch.version.cuda); print('cuda_available=',torch.cuda.is_available()); print('gpu=',torch.cuda.get_device_name(0) if torch.cuda.is_available() else None)"
```

Required values:

```text
Python            3.11.x
PyTorch           2.7.1+cu128 (or equivalent 2.7.1 CUDA 12.8 build)
torchvision       0.22.1+cu128 (or equivalent)
Ultralytics       8.4.41
CUDA build        12.8
CUDA available    True
GPU               non-empty NVIDIA GPU name
```

## 8. Check Service-to-Service Runtime Health

Run the request from `service-python`, proving Docker DNS/network connectivity:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import requests; r=requests.get('http://yolo-runtime:8010/internal/runtime/health',timeout=10); print(r.status_code); print(r.text); r.raise_for_status(); assert r.json()['runtimeReady'] is True; assert r.json()['probeContext']=='CONTAINER_RUNTIME'"
```

## 9. Check Definition Availability Through service-python

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,requests; d=json.load(open('/app/app/model_definitions/YOLOV8N_SHEEP_V1.json',encoding='utf-8')); r=requests.post('http://127.0.0.1:8000/internal/model-definitions/check',json={'modelDefinition':d,'runtimeProfileId':'YOLO_RUNTIME_V1'},timeout=20); print(r.status_code); print(r.text); r.raise_for_status(); assert r.json()['available'] is True; assert r.json()['selfCheckPassed'] is True"
```

Expected: `available=true`, `runtimeAvailable=true`, `selfCheckPassed=true`, and `probeContext=CONTAINER_RUNTIME`.

## 10. Check Java Available Models API

Set your existing SERVER username. The next command prompts for a password only through the placeholder in the command, so avoid sharing the resulting CMD history or cookie file:

```cmd
set SERVER_USERNAME=replace_with_server_username
set SERVER_PASSWORD=replace_with_server_password
curl -sS -c stage1b5-cookie.txt -H "Content-Type: application/json" -d "{\"username\":\"%SERVER_USERNAME%\",\"password\":\"%SERVER_PASSWORD%\"}" http://127.0.0.1:18080/api/auth/login
curl -sS -b stage1b5-cookie.txt http://127.0.0.1:18080/api/model-definitions/available
```

The response must contain both:

```text
YOLOV8N_SHEEP_V1
YOLOv8n 羊群检测
```

It must also show `availability.available=true`. Delete the temporary cookie file after the check:

```cmd
del stage1b5-cookie.txt
set SERVER_PASSWORD=
```

## 11. Prepare Golden Certification Files

Create a temporary directory inside the Runtime:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec yolo-runtime mkdir -p /tmp/certification
```

Check the original weights SHA before copying:

```cmd
certutil -hashfile D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt SHA256
```

It must be:

```text
aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
```

Copy the read-only lab artifacts temporarily. These commands do not modify the originals:

```cmd
docker compose --env-file .env.local-server --profile model-runtime cp D:\workspace\federated-weights-lab\yolo\handoff\model_definition.json yolo-runtime:/tmp/certification/model_definition.json
docker compose --env-file .env.local-server --profile model-runtime cp D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt yolo-runtime:/tmp/certification/weights_only.pt
docker compose --env-file .env.local-server --profile model-runtime cp D:\workspace\federated-weights-lab\yolo\handoff\data\images\DJI_0004_0256_jpg.rf.5970132c91eda5a2fa1451e6a0b51419.jpg yolo-runtime:/tmp/certification/test.jpg
```

Check the copied weights SHA:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec yolo-runtime sha256sum /tmp/certification/weights_only.pt
```

## 12. Run Golden Certification

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec yolo-runtime python /app/certify_runtime.py --definition /tmp/certification/model_definition.json --weights /tmp/certification/weights_only.pt --image /tmp/certification/test.jpg --output /tmp/certification/certification-result.json
```

Display the report:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec yolo-runtime cat /tmp/certification/certification-result.json
```

Required result:

```text
passed                     true
runtime.passed             true
runtime.cudaAvailable      true
definition.passed          true
weights.passed             true
weights.weightsOnlyLoader  true
strictLoad.passed          true
strictLoad.strict          true
strictLoad.modelKeyCount   355
strictLoad.weightsKeyCount 355
missingKeys                []
unexpectedKeys             []
shapeMismatches            []
inference.passed           true
invalidDetectionCount      0
checkpointUsed             false
```

## 13. Check The Old Python Service

This confirms the old service remains alive independently of the new Runtime:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python curl -fsS http://127.0.0.1:8000/internal/ping
```

Expected response includes `python ok`.

## 14. Failure And Rollback

If the Runtime build, GPU check, or certification fails, do not enter Stage 1C. Restore the feature flag configuration:

```cmd
copy /Y .env.local-server.stage1b5.backup .env.local-server
docker compose --env-file .env.local-server stop yolo-runtime
docker compose --env-file .env.local-server up -d service-python service-java gateway
```

This rollback does not modify the database and does not use `down -v`. Keep the certification output and Runtime logs for diagnosis.
