# Stage 5C final fix: code handoff (not production acceptance)

## 1. InternImage HTTP 422

Workflow 40/job `793bd40fb1094005a3b0c1a84267aa87` reached `internimage-runtime:8020`, but the original HTTP 422 response body was not retained. Therefore its historical `detail.reasonCode` **cannot be proven from the old log**. The exact first rejection for the reported dataset is, however, reproducible in the Runtime's own validation order:

1. `service-internimage-runtime/app/weights_validation.py:108` pairs files by exact stem. `ADE_train_00000007.jpg_1_1.png` and `ADE_train_00000007.png_1_1.png` have different stems. `collect_dataset()` raises `MASK_MISSING` before weights loading or inference.
2. With names corrected but original indexed mask unchanged, the `P` PNG has indices `[0,1,2,3]`; the trusted Definition permits `[0,1,2,255]`. It raises `MASK_LABEL_INVALID`. A one-pair dataset is valid if it passes these checks. No class 3 remapping, fake GT, or synthetic metric is introduced.
3. The service-python serialized request was parsed by the actual Runtime Pydantic model in a regression test; no known request-field mismatch was found. Without the old response body, another historical 422 cause cannot be ruled out. A new isolated dataset-check endpoint can diagnose the asset without creating a validation Job or modifying workflow/model data.

The existing `validate-weights-v1` FastAPI route maps `WeightsValidationError` to HTTP 422 with `{reasonCode,message}`. A FastAPI request-schema rejection instead has the framework's list-shaped `detail`. `service-python/app/services/internimage_runtime_client.py` previously called `raise_for_status()` then collapsed either response to `HTTPError`, losing the detail. It now parses only allowlisted business codes, distinguishes schema 422 from unknown 422/500/transport errors, and does not forward raw response text or paths. `service-python` includes `[reasonCode]` plus safe Chinese text in the signed failure callback; Java persists that text and the SERVER progress view can display it. An unknown error stays generic and is never mislabeled as a dataset error.

The Runtime now reads indexed/grayscale PNG labels via Pillow (`P`/`L` raw uint8 indices). OpenCV's unchanged decode of a palette PNG can become 3-channel BGR, so it was not a reliable way to validate class indices. The same strict mask reader is used in dataset preflight and metric inference. Runtime still requires exact pairs, matching image/mask dimensions, readable images, labels in `{0,1,2,255}`, controlled paths, safe `weights_only=True` loading, trusted architecture, strict state load, GPU inference and real GT metrics. These later stages have **not** been rerun against workflow 40.

For a read-only isolated diagnosis after deployment, use the trusted Definition JSON shipped inside service-python with `POST /internal/runtime/check-dataset-v1` and dataset 15's controlled path. This request only scans image/mask files and constructs the trusted architecture; it does not create a Job, write workflow state, load global weights, or run FedAvg. Expect `MASK_MISSING`; after importing a new archive with matching names but the same original mask values, expect `MASK_LABEL_INVALID`. Do not mutate asset 15 in place. User-run Windows CMD command after the four services are healthy:

```cmd
docker compose --env-file .env.local-server --profile model-runtime exec service-python python -c "import json,requests;from pathlib import Path;d=json.loads(Path('/app/app/model_definitions/INTERNIMAGE_T_UPERNET_WHEAT_V1.json').read_text());r=requests.post('http://internimage-runtime:8020/internal/runtime/check-dataset-v1',json={'modelDefinition':d,'runtimeProfileId':'INTERNIMAGE_RUNTIME_V1','datasetPath':'/opt/workflow-platform/storage/datasets/DA202610161658357039/content'},timeout=120);print(r.status_code,r.json())"
```

## 2. Retry validation

New SERVER API: `POST /api/workflows/{id}/validation/retry`. It requires the assigned SERVER owner, a failed V1 validation (or an uncertain retry submission), a completed FedAvg, the existing formal `FEDERATED_WEIGHTS_V1` global asset, a trusted enabled Definition and compatible server-owned dataset. The global asset is rechecked by the existing Stage 4/5C preparation service. Segmentation dataset preflight runs before any new Job claim. YOLO and InternImage use the same retry orchestration and their trusted runtime profile.

For a fresh FAILED job, a conditional database update claims the workflow and assigns a new Job ID only if the old Job ID, FAILED status, completed aggregation and global asset still match. It does not invoke upload, decrypt or FedAvg. A second click/instance loses the claim. Python Job creation is idempotent by Job ID. If submission times out after the database claim, the workflow is marked `重新验证任务提交待确认`; retry resends **the same ID**, preventing an ambiguous submit from spawning a second Job. The callback verifies its signature and Job ID. Persistence is additionally conditional on the current database Job ID, so a late callback loaded before the retry claim cannot overwrite the new Job. A failed audit-step insert does not strand an already claimed Job.

The previous `workflow_step` entries and the old persisted Python Job remain; the workflow pointer changes to the newest Job. Previous result files are not deleted by retry. Existing `workflow`/`workflow_step`/Python Job storage suffice: **DB schema change: NO; SQL executed: NO**. A failed V1 workflow with completed aggregation may bind a different server-owned dataset; binding itself is conditional on the previously observed workflow status and Job ID, so it cannot overwrite a concurrent retry. The first-validation entry remains unchanged.

Workflow 40/global asset 86 is a candidate **only if** it is still FAILED, aggregation COMPLETED and the formal asset remains READY/OK. Dataset 15 is not valid as observed. Import a genuinely valid images/masks dataset, bind it via SERVER UI, then click `重新验证`. Do not alter workflow 40, asset 86 or dataset 15 by SQL, and do not re-upload weights or rerun FedAvg. If no legitimate GT exists, stop; validation must not be marked successful.

## 3. SERVER dataset selection

`frontend-web/src/views/server/ServerWorkflowProgressView.vue` polled workflow detail every four seconds and assigned `selectedDatasetId` from the persisted value on every response. That overwrote a user's unsubmitted choice. Dataset options were also re-fetched during page transitions; older detail responses could arrive after newer choices. The backend log for workflow 40 shows bind asset 15 succeeded and validation used 15, so the observed disappearance was a UI display/state issue, not evidence that the database binding vanished.

The selection now separates an unsaved draft from the persisted binding. Polling retains a dirty draft; after successful binding, the page reloads the server value. A failed bind restores the last server value. Number/string IDs are normalized; sequence checks discard stale detail responses; workflow switches reset the draft; polling stays enabled but pauses while a bind request is in flight. A true server unbind or invalid persisted value is reflected on subsequent refresh when no draft is active. UI tests simulate multiple polling cycles, reload, failure, response reordering and workflow switch. They do not substitute for browser/Docker acceptance.

## 4. Automated checks

| Check | Result |
| --- | --- |
| InternImage Runtime full tests | PASS: 42 |
| service-python full tests | PASS: 94, SKIPPED: 1, plus 3 subtests |
| YOLO Runtime regression | PASS: 17, plus 3 subtests |
| Java 21 focused workflow/callback/client tests | PASS: 25 |
| Java 21 full regression excluding Spring context smoke test | PASS: 154, SKIPPED: 2 |
| Java 21 Spring context smoke test | ERROR: sandbox blocks JDK loopback socket (`Permission denied: connect` in `RestTemplateConfig`); not classified as a Stage 5C assertion failure |
| Frontend tests | PASS: 31 |
| `vue-tsc` and Vite production build | PASS; existing large-chunk warning |
| `git diff --check` | PASS; Git only reports CRLF conversion warnings |
| Runtime HTTP route test on local host | NOT RUN: local Python lacks FastAPI; function-level and cross-schema tests ran, Docker HTTP certification pending |
| Docker deploy / real GPU validation / browser acceptance | NOT RUN by Codex; user action required |

The Java full suite with the context smoke test returned 1 environment error, 0 assertion failures. The context error is caused by this managed Windows sandbox denying the loopback socket needed by JDK `HttpClient`, not by a V1 route. No test that did not run is reported as passed.

## 5. Changed files for this fix

- InternImage Runtime: `service-internimage-runtime/app/{main.py,schemas.py,weights_validation.py}` and `tests/test_weights_validation.py`.
- Generic Python: `service-python/app/{api/model_definitions.py,schemas/model_protocol.py,services/internimage_runtime_client.py,services/weights_validation_v1_service.py}` and `tests/{test_weights_validation_v1.py,test_internimage_runtime_registry.py}`.
- Java: `service-java/src/main/java/com/workflow/{controller/WorkflowController.java,mapper/WorkflowMapper.java,service/WorkflowService.java,service/impl/WorkflowServiceImpl.java,service/python/PythonJobClient.java}` and tests `WorkflowServiceImplStartPythonJobTest.java`, `WorkflowServiceImplCallbackTest.java`, `PythonJobClientTest.java`.
- Frontend: `frontend-web/src/{api/workflow.ts,views/server/ServerWorkflowProgressView.vue,utils/datasetSelectionState.ts}`, `frontend-web/tests/datasetSelectionState.test.ts`, and `frontend-web/package.json`.
- This report and a minimal correction to `docs/weights-protocol/STAGE5C_MANUAL_TEST.md`.

Other pre-existing uncommitted Stage 5C files were preserved. No YOLO Runtime, model weights, real dataset, database row, or existing formal asset was edited. No Docker command, SQL, or real validation was executed by Codex.

## 6. User deployment and acceptance (Windows CMD)

With the existing `.env.local-server` flags preserved, rebuild **only** the four changed images. `gateway` serves the changed Vue bundle; YOLO Runtime and MySQL need no rebuild. Run from Windows CMD:

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build --progress=plain internimage-runtime service-python service-java gateway
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps internimage-runtime
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps service-python
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps service-java
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps gateway
docker compose --env-file .env.local-server --profile model-runtime ps
docker compose --env-file .env.local-server --profile model-runtime logs --tail 100 internimage-runtime service-python service-java gateway
```

Confirm all four are healthy. In SERVER Dataset Management import a **new formal** ZIP with at least one readable image and same-stem indexed/grayscale PNG mask. The mask must contain only original, genuinely annotated labels `0,1,2,255`; neither rename nor remap a semantically invalid label 3 to force a pass. Bind that asset to workflow 40 if its failed state and global asset pass the UI/server readiness checks. Wait through multiple four-second polls, refresh the browser, verify the binding remains, then click `重新验证` once. Confirm a new Job ID, no new A/B upload or FedAvg, `internimage-runtime:8020` call, strict GPU validation, signed COMPLETED callback, real mIoU/mAcc/aAcc and result/sample on CLIENT and SERVER. Separately, test a failed bind restoring the persisted selection. Preserve the old failed Job for audit.

**Current status:** code changes and relevant automated checks passed; Docker deployment, HTTP error-code observation on live containers, browser polling and real GPU validation are PENDING. Stage 5C FINAL PASS is **not** claimed.
