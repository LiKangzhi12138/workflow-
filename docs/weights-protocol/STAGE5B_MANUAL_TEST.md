# Stage 5B Manual Docker Test (Windows CMD)

This procedure certifies InternImage weights-only upload and generic FedAvg only.
Do not start Validation; that belongs to Stage 5C.

## 1. Back Up And Edit Configuration

From `D:\workspace\workflow-platform` in Windows CMD:

```cmd
copy /Y .env.local-server .env.local-server.stage5b.bak
notepad .env.local-server
```

Confirm these values. Keep existing secrets unchanged:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
INTERNIMAGE_RUNTIME_V1_ENABLED=true
MODEL_DEFINITION_WORKFLOW_SELECTABLE_RUNTIME_PROFILES=YOLO_RUNTIME_V1,INTERNIMAGE_RUNTIME_V1
WEIGHTS_PROTOCOL_V1_ENABLED=true
WEIGHTS_FEDAVG_V1_ENABLED=true
WEIGHTS_VALIDATION_V1_ENABLED=false
```

The last value is intentional for Stage 5B acceptance.

## 2. Static Configuration Check

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
```

## 3. Build And Start Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime build --progress=plain internimage-runtime service-python service-java
docker compose --env-file .env.local-server --profile model-runtime up -d internimage-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime ps
```

The frontend production source did not change for Stage 5B; its existing dynamic
Definition selector is reused.

## 4. Runtime And AVAILABLE Checks

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import requests; print(requests.get('http://internimage-runtime:8020/internal/runtime/health', timeout=30).json())"
```

Log in as SERVER and call the existing `GET /api/model-definitions/available`.
Verify both Definitions are present:

```text
YOLOV8N_SHEEP_V1                       available=true workflowSelectable=true
INTERNIMAGE_T_UPERNET_WHEAT_V1         available=true workflowSelectable=true
```

## 5. Prepare Real Weights-only Packages

The following inputs are already-safe lab weights-only artifacts, not full
checkpoints. The packaging tool reads them only with `weights_only=True`.

```cmd
mkdir .runtime\stage5b-internimage-client-a
mkdir .runtime\stage5b-internimage-client-b

python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\internimage\artifacts\client_a\weights_only.pth --definition service-python\app\model_definitions\INTERNIMAGE_T_UPERNET_WHEAT_V1.json --output-dir .runtime\stage5b-internimage-client-a --created-by internimage-client-a

python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\internimage\artifacts\client_b\weights_only.pth --definition service-python\app\model_definitions\INTERNIMAGE_T_UPERNET_WHEAT_V1.json --output-dir .runtime\stage5b-internimage-client-b --created-by internimage-client-b

certutil -hashfile .runtime\stage5b-internimage-client-a\weights.pt SHA256
certutil -hashfile .runtime\stage5b-internimage-client-b\weights.pt SHA256
```

Expected source/copied SHA256 values:

```text
client A = aa1570cbbc4a816efb4fcb3c96c546d95521a3cd36f093e359f01761b504a48c
client B = bfde8cc61e44ef37eb9ac9335733b158fdd69334962e14a46ab0543447dcbf81
```

Each generated manifest must report 728 tensors, 58,963,958 elements, float32,
the trusted InternImage Definition ID/SHA, and the frozen architecture signature.

## 6. Create The Workflow

In CLIENT Workflow Management:

1. Create a new Definition workflow.
2. Select the InternImage family/Definition exposed by AVAILABLE.
3. Set required client model count to `2`.
4. Complete the existing server/model-asset business fields.
5. Confirm the created row binds the InternImage `modelDefinitionId`.

Do not reuse a failed workflow from another acceptance run.

## 7. Upload Client A

Select exactly:

```text
.runtime\stage5b-internimage-client-a\weights.pt
.runtime\stage5b-internimage-client-a\manifest.json
.runtime\stage5b-internimage-client-a\descriptor.json
```

Complete the existing receive/decrypt action as SERVER. Verify:

```text
Safe Inspector passed
tensorCount = 728
elementCount = 58963958
runtimeProfileId = INTERNIMAGE_RUNTIME_V1
expected-state compatibility = true
source_type = WORKFLOW_WEIGHTS_V1
aggregation_status = INSPECTED
FedAvg has not started because only 1/2 inputs is ready
```

## 8. Upload Client B And Aggregate

Repeat with the three files under
`.runtime\stage5b-internimage-client-b`. When 2/2 inputs are inspected, verify one
call to the existing generic endpoint:

```text
POST /internal/federated/aggregate-weights-v1
```

Expected final state:

```text
federated_strategy = WEIGHTS_FEDAVG_V1
federated_status = COMPLETED
input aggregation_status = COMPLETED
global source_type = FEDERATED_WEIGHTS_V1
global import_mode = WEIGHTS_PROTOCOL_V1
global record_mode = FORMAL_ASSET
global status = READY
global last_check_status = OK
```

Do not click Validation.

## 9. Logs

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 300 service-python
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 internimage-runtime
```

Confirm logs show trusted workflow/Definition/Runtime IDs, tensor/element counts,
compatibility success, input asset IDs, and global output SHA. They must not show
secrets, full tensors, or a full Definition architecture payload.

## 10. Read-only Database Verification

Open MySQL without placing its password in command history:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -u root -p workflow_platform
```

Run only SELECT statements, replacing `<workflowId>`:

```sql
SELECT id, workflow_code, model_definition_id, expected_model_count,
       federated_status, federated_strategy, federated_model_asset_id,
       current_step, error_message
FROM workflow
WHERE id = <workflowId>;

SELECT id, workflow_id, server_model_asset_id, upload_status,
       aggregation_status, file_sha256, error_message
FROM workflow_model_upload
WHERE workflow_id = <workflowId>
ORDER BY id;

SELECT id, asset_code, source_type, import_mode, record_mode,
       file_path, file_size, status, last_check_status, last_check_message,
       is_deleted
FROM model_asset
WHERE id IN (
  SELECT server_model_asset_id FROM workflow_model_upload WHERE workflow_id = <workflowId>
  UNION
  SELECT federated_model_asset_id FROM workflow WHERE id = <workflowId>
)
ORDER BY id;
```

Exit without modifying data:

```sql
exit
```

## 11. Formal Files

```cmd
dir /s .runtime\local-server\storage\weights-assets\weights.pt
dir /s .runtime\local-server\storage\federated-models\global_weights.pt
dir /s .runtime\local-server\storage\federated-models\aggregation-manifest.json
dir /s .runtime\local-server\storage\federated-models\descriptor.json
dir /s .runtime\local-server\storage\federated-models\inspection-report.json
dir /s .runtime\local-server\storage\federated-models\aggregation-report.json
```

Use the exact global path from `model_asset.file_path` to inspect SHA and sidecars:

```cmd
certutil -hashfile <full-host-path-to-global_weights.pt> SHA256
type <full-host-path-to-inspection-report.json>
type <full-host-path-to-aggregation-report.json>
```

The global Inspector must report 728 tensors, 58,963,958 elements, float32, and
zero NaN/+Inf/-Inf. Serialization SHA may differ from an experimental global
artifact; tensor contract and values are the correctness evidence.

## 12. Negative And Regression Checks

Automated tests cover unsafe wrapper/object, wrong Definition, missing/extra key,
wrong shape/dtype, NaN/Inf, SHA/path/resource violations, and incompatible
aggregation inputs. For a small manual fail-closed sample, edit a copy of one
manifest to claim `YOLOV8N_SHEEP_V1`; the upload must fail before formal asset
registration and must not fall back to legacy.

Also verify a YOLO Definition workflow can still upload and aggregate, and all
four CLIENT/SERVER workflow management/progress pages still open.

## 13. Pass Criteria

Stage 5B manual acceptance passes only when both real InternImage packages become
formal `WORKFLOW_WEIGHTS_V1` assets, the second input triggers exactly one generic
FedAvg, and the result is a safe formal `FEDERATED_WEIGHTS_V1` weights-only asset.
No checkpoint, pretrained/base model, InternImage Validation, or database write
outside normal application behavior is part of this procedure.

