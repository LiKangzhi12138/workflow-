# Stage 2A Manual Test (Windows CMD)

This procedure stops after trusted V1 asset registration. Do not run FedAvg or
Validation as part of Stage 2A acceptance. Never use `docker compose down -v`.

## 1. Back Up And Enable The Feature

```cmd
cd /d D:\workspace\workflow-platform
copy /Y .env.local-server .env.local-server.stage2a.backup
notepad .env.local-server
```

Keep existing secrets unchanged. Confirm these non-secret settings:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
WEIGHTS_PROTOCOL_V1_ENABLED=true
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
WORKFLOW_WEIGHTS_ASSET_ROOT=/opt/workflow-platform/storage/weights-assets
```

The code default for `WEIGHTS_PROTOCOL_V1_ENABLED` remains false; this explicit
setting is only for the manual acceptance environment.

## 2. Validate, Build, And Start Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build yolo-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime up -d yolo-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime ps
```

Wait for `mysql`, `service-java`, `service-python`, `yolo-runtime`, and `gateway`
to be healthy/running.

## 3. Prepare The Golden Package

Use a new output directory:

```cmd
python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt --definition service-python\app\model_definitions\YOLOV8N_SHEEP_V1.json --output-dir .runtime\stage2a-manual-package --created-by stage2a-manual-test
certutil -hashfile .runtime\stage2a-manual-package\weights.pt SHA256
```

Required SHA256:

```text
aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
```

## 4. Create Or Select A Definition Workflow

1. Log in as CLIENT in the browser.
2. Create a workflow using the available `YOLO / YOLOv8` Definition, or select
   an existing Stage 1C workflow that has not completed its upload quota.
3. The existing client ModelAsset selection is an ownership/audit source record;
   a `CLIENT_PATH_REGISTRY` record is sufficient. Do not upload a full checkpoint
   merely to create this reference.
4. Confirm the workflow pages still use the minimal model UI and do not show
   classes, framework, runtime profile, or architecture signature.

## 5. Verify The Upload Contract

Open browser developer tools while opening the upload dialog. Verify:

```text
GET /api/workflow-uploads/contract/{workflowId}
HTTP 200
uploadProtocol = WEIGHTS_V1
manifestRequired = true
descriptorRequired = true
acceptedArtifactType = CLIENT_WEIGHTS
```

An unavailable contract must disable upload without breaking the Workflow page.

## 6. Upload And Server Acceptance

Select these three files in the CLIENT upload dialog:

```text
.runtime\stage2a-manual-package\weights.pt
.runtime\stage2a-manual-package\manifest.json
.runtime\stage2a-manual-package\descriptor.json
```

Start upload. Confirm the browser performs SHA calculation and the existing AES
step. Then log in as SERVER and use the existing receive/decrypt action for this
upload. Stop after the V1 safe inspection/registration succeeds; do not start
FedAvg.

Expected upload state:

```text
upload_status      = COMPLETED
aggregation_status = INSPECTED
error_message      = NULL
```

## 7. Read-Only Database Verification

Open MySQL interactively without placing a password in command history:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec mysql mysql -u root -p
```

Run only these read-only statements, replacing IDs as needed:

```sql
SELECT id, workflow_code, model_definition_id, client_model_asset_id
FROM workflow
WHERE id = <workflowId>;

SELECT id, workflow_id, model_asset_id, server_model_asset_id,
       upload_status, aggregation_status, file_sha256,
       encrypted_file_path, decrypted_file_path, error_message
FROM workflow_model_upload
WHERE workflow_id = <workflowId>
ORDER BY id DESC;

SELECT id, asset_code, owner_role_code, source_type, import_mode,
       record_mode, file_path, file_size, last_check_status,
       last_check_message, status, is_deleted
FROM model_asset
WHERE id = <serverModelAssetId>;
```

Required values include:

```text
workflow.model_definition_id = 1
source_type                  = WORKFLOW_WEIGHTS_V1
import_mode                  = WEIGHTS_PROTOCOL_V1
record_mode                  = FORMAL_ASSET
last_check_status            = OK
status                       = READY
```

Exit MySQL:

```sql
exit
```

## 8. Verify Files

Use the `file_path` returned above. For the default local-server storage layout:

```cmd
dir .runtime\local-server\storage\weights-assets\<workflowId>\<uploadId>
```

Required files:

```text
weights.pt
manifest.json
descriptor.json
inspection-report.json
```

Verify temporary artifacts are absent after successful best-effort cleanup:

```cmd
dir .runtime\local-server\storage\tmp\weights-v1-quarantine\<workflowId>\<uploadId>
dir .runtime\local-server\storage\model-uploads\<workflowId>\encrypted
dir .runtime\local-server\storage\model-uploads\<workflowId>\protocol-v1
```

The commands may report that a path/file is absent. Do not recursively delete
anything during verification.

## 9. Representative Failure Checks

Make copies of metadata for negative testing; do not edit the positive package.

```cmd
copy .runtime\stage2a-manual-package\manifest.json .runtime\stage2a-manual-package\manifest-bad-sha.json
copy .runtime\stage2a-manual-package\manifest.json .runtime\stage2a-manual-package\manifest-bad-definition.json
notepad .runtime\stage2a-manual-package\manifest-bad-sha.json
notepad .runtime\stage2a-manual-package\manifest-bad-definition.json
```

For the first copy, replace only `weights.sha256` with 64 zeroes. For the second,
replace only `modelDefinition.definitionId` with `OTHER_DEFINITION`. Upload each
through a fresh upload slot. Both must be rejected, no formal
`WORKFLOW_WEIGHTS_V1` asset may be created, and V1 must not fall back to legacy.

## 10. Regression Checks

Open these pages without running aggregation:

```text
CLIENT Workflow management
SERVER Workflow management
CLIENT Workflow progress
SERVER Workflow progress
```

Check one legacy workflow (`model_definition_id IS NULL`) and one Definition
workflow. Both must remain readable. The Stage 1C selector must still show only
the minimum model information (`YOLO`, optionally `YOLOv8`).

## 11. Logs

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 service-java
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 service-python
docker compose --env-file .env.local-server --profile model-runtime logs --tail 200 yolo-runtime
```

Expected evidence includes safe inspection and compatibility success without
AES keys, stack traces in API responses, or full state_dict key dumps.

## 12. Acceptance

Stage 2A manual acceptance passes when the Golden package becomes a formal
`WORKFLOW_WEIGHTS_V1` asset with all four files, temporary successful-upload
artifacts are cleaned according to policy, representative mismatches fail closed,
legacy pages/uploads remain compatible, and no FedAvg or Validation is invoked.
