# Stage 5C Manual Docker Test (Windows CMD)

This is a **user-run** test. Codex has not built or started Docker, modified SQL data, or started validation. Do not run Stage 5D. Commands below are for **Windows CMD**, from `D:\workspace\workflow-platform`.

## 1. Preserve Config And Confirm Input

```cmd
cd /d D:\workspace\workflow-platform
copy /Y .env.local-server .env.local-server.stage5c.bak
notepad .env.local-server
```

Keep existing secrets. Confirm Stage 5B settings remain enabled and set:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
INTERNIMAGE_RUNTIME_V1_ENABLED=true
WEIGHTS_PROTOCOL_V1_ENABLED=true
WEIGHTS_FEDAVG_V1_ENABLED=true
WEIGHTS_VALIDATION_V1_ENABLED=true
```

The code default for the validation flag remains `false`; only this manual deployment turns it on. Confirm workflow 38 / asset 80 before attempting validation. No code path hardcodes these IDs.

## 2. Prepare A Real GT Dataset

`workflow-platform` contains **no deployable wheat GT dataset**. The lab handoff has 10 read-only 500x500 pairs: `images/*.jpg` and `annotations/*.png`, matching basenames, with integer mask values `0=background`, `1=healthy_wheat`, `2=lodged_wheat`, `255=ignore`. Prepare a temporary copy for formal import; neither Runtime nor service-python reads the lab path during validation.

```cmd
mkdir .runtime\stage5c-gt\images
mkdir .runtime\stage5c-gt\masks
xcopy /Y /I D:\workspace\federated-weights-lab\internimage\handoff\data\images\*.jpg .runtime\stage5c-gt\images\
xcopy /Y /I D:\workspace\federated-weights-lab\internimage\handoff\data\annotations\*.png .runtime\stage5c-gt\masks\
dir /b .runtime\stage5c-gt\images
dir /b .runtime\stage5c-gt\masks
tar -a -c -f .runtime\stage5c-gt.zip -C .runtime\stage5c-gt images masks
```

Expect **10 images and 10 masks**. No mask may be RGB/palette-color artwork; it must be a single-channel uint8 label map. Do not replace GT with model predictions. In SERVER Dataset Management, upload `.runtime\stage5c-gt.zip` as a **formal dataset asset** (for example type CROP). Check its detail: `dataFormat=IMAGE_MASK_PAIRS`, `taskType=SEMANTIC_SEGMENTATION`, `status=READY`, `lastCheckStatus=OK`, image count 10. The server imports it into managed storage. A path-registry asset is not valid for this workflow validation.

## 3. Build And Start Only Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build --progress=plain internimage-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime up -d internimage-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime ps
```

YOLO Runtime was not changed. Check InternImage health from service-python:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import requests; d=requests.get('http://internimage-runtime:8020/internal/runtime/health',timeout=30).json(); print(d)"
```

Require CUDA, DCNv3 and runtime readiness. A running container alone is insufficient.

## 4. Check Workflow 38 Without Writing SQL

Use your existing read-only DB client, or open a MySQL prompt with this CMD command (password entered interactively):

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -uroot -p
```

At the MySQL prompt, run **SELECT only** (select the platform database first if your client has no default database):

```sql
SELECT id, workflow_code, status, model_definition_id, server_dataset_asset_id,
       federated_strategy, federated_status, federated_model_asset_id,
       python_job_id, result_file_path
FROM workflow WHERE id = 38;

SELECT id, source_type, import_mode, record_mode, status, last_check_status,
       file_path, is_deleted
FROM model_asset WHERE id = 80;

SELECT id, owner_user_id, record_mode, status, last_check_status,
       task_type, data_format, image_count, file_path
FROM dataset_asset WHERE id = <newDatasetAssetId>;
```

Replace `<newDatasetAssetId>` with the ID shown in SERVER Dataset Management. Expected workflow: trusted `model_definition_id=2`, `WEIGHTS_FEDAVG_V1`, `federated_status=COMPLETED`, `federated_model_asset_id=80`, no existing Python job; asset 80 must be READY/OK `FEDERATED_WEIGHTS_V1` / `WEIGHTS_PROTOCOL_V1` / `FORMAL_ASSET`. Expected asset SHA: `66aa3808a352983cc9bc295b26620e4e0a3a87b055df2cc842c0ee4e5a13185e`.

If workflow 38 has no dataset or a Detection dataset, bind the newly imported segmentation asset in SERVER Workflow Progress **only if the existing status permits binding**. Do not UPDATE workflow 38 by SQL. A FAILED weights-only validation with completed aggregation and a valid formal global asset can now rebind a server-owned dataset and use `重新验证` without repeating A+B upload/FedAvg. A VALIDATING, COMPLETED, or otherwise ineligible workflow cannot use this retry path. The new dataset preflight accepts one or more exact-stem image/PNG mask pairs with genuine label values `0,1,2,255`; it rejects missing pairs and label 3. See `docs/STAGE5C_FINAL_FIX_REPORT.md` for deployment and retry acceptance.

## 5. Explicitly Start Validation

In SERVER Workflow Progress, click the existing Start Validation command once. Aggregation completion must not auto-start validation. The browser sends a workflow action only; Java reloads the trusted Definition, global asset and bound dataset. Expect one `WEIGHTS_PROTOCOL_V1` async job routed to `INTERNIMAGE_RUNTIME_V1` and `POST /internal/runtime/validate-weights-v1`.

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-python
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 internimage-runtime
```

Expect signed callback COMPLETED, result path persisted, no Legacy checkpoint/Yolo validation fallback. If the job fails, keep the asset and logs, identify its reason, and do not fabricate metrics.

## 6. Read-Only Result And Evidence Checks

At the same MySQL prompt:

```sql
SELECT id, status, python_job_id, result_file_path, metrics_json,
       federated_status, federated_model_asset_id
FROM workflow WHERE id = 38;
```

If using a new workflow, replace `38` with that ID. Use the persisted `result_file_path` in the following CMD commands, replacing `<resultPath>` with its container path:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json; d=json.load(open(r'<resultPath>',encoding='utf-8')); print(json.dumps({'taskType':d.get('taskType'),'datasetAssetId':d.get('datasetAssetId'),'metrics':d.get('metrics'),'validationEvidence':d.get('validationEvidence'),'samples':d.get('sampleResults')},indent=2,ensure_ascii=False))"

docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,os; d=json.load(open(r'<resultPath>',encoding='utf-8')); print([(s.get('imageName'),os.path.isfile(s.get('annotatedImagePath') or ''),os.path.isfile(s.get('predictionMaskPath') or ''),os.path.isfile(s.get('groundTruthMaskPath') or '')) for s in d.get('sampleResults',[])])"
```

Require `taskType=SEMANTIC_SEGMENTATION`, `metricsMode=GROUND_TRUTH`, finite real `mIoU/mAcc/aAcc` and all three named per-class entries. `accuracy` and `map50` must **not** be fabricated for segmentation. Evidence must say: `modelDefinitionId=INTERNIMAGE_T_UPERNET_WHEAT_V1`, `globalWeightsAssetId=80` (or new asset ID), expected global SHA, `runtimeProfileId=INTERNIMAGE_RUNTIME_V1`, `weightsOnly=true`, `checkpointUsed=false`, `baseCheckpointUsed=false`, `pretrainedUsed=false`, `constructionMode=TRUSTED_CONFIG_TEMPLATE`, `strictLoad=true`, `missingKeyCount=0`, `unexpectedKeyCount=0`, `tensorCount=728`, `elementCount=58963958`, `validationCompleted=true`. Verify up to six sample overlay JPEGs and prediction PNGs exist; the GT mask paths point to the imported formal dataset.

Finally inspect CLIENT and SERVER Validation Result views: mIoU/mAcc/aAcc, background/healthy_wheat/lodged_wheat IoU, sample overlay, and COMPLETED status. Detection bbox/mAP UI must not appear for InternImage. Confirm an existing YOLO Stage 4 result still displays its original detection metrics. Do not run another FedAvg or modify asset 80.

## 7. Failure / Rollback

For a negative check, a dataset with a missing PNG mask or label 17 must fail, never produce estimated accuracy. Keep this optional; no need to handcraft every negative case. On a production issue, disable `WEIGHTS_VALIDATION_V1_ENABLED` in `.env.local-server` and user-restart only affected services. Do not delete global weights, result evidence, or database history; no SQL rollback is required.
