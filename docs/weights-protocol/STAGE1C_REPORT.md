# Stage 1C Report

## 1. Result

Stage 1C code readiness: PASS, pending the documented manual Docker and database verification.

The workflow model identity path is now:

```text
GET /api/model-definitions/registry-status
        -> registry enabled
GET /api/model-definitions/available
        -> user selects an AVAILABLE definition
POST /api/workflows { modelDefinitionId }
        -> Java reloads model_definition by ID
        -> Java calls Python availability again
        -> trusted definition metadata is mapped to workflow
```

## 2. Creation Contract

When `MODEL_DEFINITION_REGISTRY_V1_ENABLED=true`, the frontend submits only
`modelDefinitionId` as the model identity. Java does not trust a submitted model
family, version, task, class list, framework, runtime profile, or architecture
signature. It reloads the registered Definition and repeats the Python Runtime
availability check at create time.

The existing `workflow` table has no `model_type`, `task_type`, framework, or
classes columns. A new Definition-based workflow therefore stores:

- `workflow.model_definition_id`: the trusted Definition ID;
- `workflow.yolo_version`: the compatibility value derived from
  `ModelDefinition.model_version` (`YOLOv8` for `YOLOV8N_SHEEP_V1`).

No schema change or historical backfill is part of Stage 1C.

## 3. Legacy Compatibility

When the Registry flag is false, the UI and Java service use the existing legacy
YOLO version path. Historical workflows keep `model_definition_id = NULL` and
continue to display `yoloVersion`. Definition-based workflows prefer
`ModelDefinition.displayName` in list, detail, and progress views.

The two selection modes are deliberately exclusive. A Definition request is
rejected while the Registry is disabled; when it is enabled, a missing or
unavailable Definition is rejected without falling back to the legacy list.

## 4. Fail-Closed Errors

- `MODEL_DEFINITION_NOT_FOUND`
- `MODEL_DEFINITION_DISABLED`
- `MODEL_DEFINITION_NOT_AVAILABLE`
- `MODEL_RUNTIME_CHECK_FAILED`
- `MODEL_DEFINITION_REGISTRY_DISABLED`

Runtime exceptions are translated to a stable Chinese business message; Python
stack traces are not returned to the frontend.

## 5. UI Behavior

The Definition selector is populated only by
`GET /api/model-definitions/available`. It displays name, family, version,
variant, task, and classes. An empty list disables creation and shows
`当前服务器暂无可用模型`. API failure enters an error state and does not reveal
the legacy hard-coded options.

The static YOLOv8/YOLOv10/YOLOv11 selector remains only in the explicit
Registry-disabled compatibility mode. It is not a source for new-protocol model
selection.

## 6. Ultralytics Runtime Cache Hygiene

The `yolo-runtime` image now sets `HOME=/home/runtime` and
`XDG_CONFIG_HOME=/home/runtime/.config`, creates
`/home/runtime/.config/Ultralytics` with mode `0750`, and assigns it to the
non-root `runtime` user. Runtime health also verifies that this directory exists
and is writable. This prevents the root-owned `/tmp/Ultralytics` fallback that
caused `persistent_cache.json` warnings.

No checkpoint, model logic, dependency version, CUDA setting, or certification
logic changed.

## 7. Verification

- Frontend model-selection tests: 5 passed.
- Frontend type-check and production build: PASS.
- Java Stage 1C targeted tests: 14 passed. The normal Maven resource path is
  locked on this Windows host, so resources were skipped and tests ran with a
  temporary JDK 25 process compatible with the project's Java 21 target.
- The broader pre-existing workflow regression run was attempted but did not
  complete cleanly: JUnit could not close Windows temporary directories, and
  legacy callback-signature fixtures failed independently of the Stage 1C
  creation path. No unrelated production code was changed to mask those
  failures.
- `service-yolo-runtime`: 5 passed.
- Existing `service-python` tests: 53 passed, 1 skipped.
- Docker Compose static config: PASS; no containers were built or started.

## 8. Protected Boundaries

Not changed: model upload, AES, FedAvg, global model output, validation,
database schema/data, Python dependencies, and InternImage support.

Stage 2 may introduce the formal weights-only plus manifest upload path. It must
not begin until the manual Stage 1C create/binding checks pass.
