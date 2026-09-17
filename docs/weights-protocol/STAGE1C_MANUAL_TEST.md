# Stage 1C Manual Test (Windows CMD)

This check validates only model selection and Workflow binding. Do not run a
full upload, FedAvg, or validation workflow for Stage 1C acceptance.

## 1. Open CMD And Back Up Configuration

```cmd
cd /d D:\workspace\workflow-platform
copy /Y .env.local-server .env.local-server.stage1c.bak
```

Confirm `.env.local-server` contains:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=true
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
```

Do not use `docker compose down -v`; it can remove database data.

## 2. Static Configuration Check

```cmd
docker compose --env-file .env.local-server --profile model-runtime config --quiet
```

## 3. Build Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime build service-java yolo-runtime gateway
```

## 4. Start Changed Services

```cmd
docker compose --env-file .env.local-server --profile model-runtime up -d service-java yolo-runtime gateway
docker compose --env-file .env.local-server --profile model-runtime ps
```

Wait until `service-java`, `service-python`, `yolo-runtime`, `mysql`, and
`gateway` are healthy/running.

## 5. Verify Available Definitions

Log in as a SERVER user in the browser. In browser developer tools, call or
inspect:

```text
GET /api/model-definitions/available
```

Expected response contains:

```text
code = OK
id = 1
code = YOLOV8N_SHEEP_V1
displayName = YOLOv8n 羊群检测
availability.available = true
```

## 6. Create A Definition-Based Workflow

1. Log in as a CLIENT user.
2. Open the client Workflow management page.
3. Click `新建工作流`.
4. Confirm the model selector shows `YOLOv8n 羊群检测`.
5. Confirm the model details show `YOLO / YOLOv8 / yolov8n`, target detection,
   and class `sheep`.
6. Select the existing client model asset and server user.
7. Create the workflow. Do not upload a model after creation in this test.

In browser developer tools, inspect `POST /api/workflows`. Its model identity
must contain:

```json
{"modelDefinitionId": 1}
```

It must not submit `modelFamily`, `taskType`, `classes`, `framework`, or
`runtimeProfileId`.

## 7. Verify Database Binding

Run these read-only statements manually in the MySQL client of your choice:

```sql
SELECT id, workflow_code, workflow_name, model_definition_id, yolo_version
FROM workflow
ORDER BY id DESC
LIMIT 5;

SELECT id, code, display_name, model_version, task_type, status
FROM model_definition
WHERE id = 1;
```

For the new workflow, expect:

```text
model_definition_id = 1
yolo_version = YOLOv8
```

## 8. Verify Display Compatibility

1. Open the new workflow list, detail, and progress pages. They should prefer
   `YOLOv8n 羊群检测`.
2. Open a historical workflow whose `model_definition_id` is NULL. It should
   still display its old `yolo_version` and remain readable.

## 9. Verify Create-Time Race Protection

After loading the create dialog, stop or make `yolo-runtime` unavailable only
if you intentionally want to test failure handling. Submitting must be rejected
with a model availability/runtime message, and no workflow row should be
created. Restore the Runtime afterwards.

## 10. Verify Runtime Cache Directory

After rebuilding `yolo-runtime`, inspect its logs:

```cmd
docker compose --env-file .env.local-server --profile model-runtime logs --tail 100 yolo-runtime
```

Expected: no permission warning for
`/tmp/Ultralytics/persistent_cache.json`, and Runtime health remains ready.

## 11. Registry-Disabled Compatibility Check

For a separate compatibility test, set the flag to false, rebuild/restart Java,
Python, and gateway as appropriate, then reopen the create dialog. The legacy
YOLO selector should appear and an old-style workflow should still be creatable.
Restore the flag to true for Stage 1C operation.

## 12. Acceptance

Stage 1C manual verification passes when the available API contains the real
Definition, the UI submits `modelDefinitionId=1`, the new database row binds ID
1 with server-derived `YOLOv8`, and historical NULL-bound workflows remain
readable.
