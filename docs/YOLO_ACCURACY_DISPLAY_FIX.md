# YOLO Accuracy Display Fix

## Audit Result

The empty value was caused by frontend selection policy, not by a missing Java DTO mapping. The previous result card displayed the existing `accuracy` value. The later presentation helper changed that policy to use only `map50` and only when `fallback === false`; when the Runtime reported fallback metrics, the UI deliberately hid every value. That policy explains the regression in source. The current production Job was not read, so this report does not assert which exact values its result JSON contains.

The current workflow DTO already exposes all required candidates:

- `ValidationResultViewVO`: `accuracy`, `precision`, `recall`, `map50`, `map50_95`, `classResults`, and `sampleResults`.
- `ValidationResultServiceImpl`: maps the five summary fields, `classResults[].avgConfidence`, and each `sampleResults[].predictions[].confidence` into the same workflow result view.
- The result view is built from the workflow's current `pythonJobId`; result JSON with a conflicting `jobId` is rejected, and samples are tied to that Job. Thus confidence fallback reads this view's current Job samples, not an unrelated workflow or historical Job.

Relevant source locations:

- `service-java/src/main/java/com/workflow/dto/validation/ValidationResultViewVO.java:39-83`
- `service-java/src/main/java/com/workflow/service/impl/ValidationResultServiceImpl.java:168-213` maps current workflow fields and metrics.
- `service-java/src/main/java/com/workflow/service/impl/ValidationResultServiceImpl.java:495-520` reads the result file and checks its Job ID.
- `service-java/src/main/java/com/workflow/service/impl/ValidationResultServiceImpl.java:701-725` maps per-class confidence summaries.
- `service-java/src/main/java/com/workflow/service/impl/ValidationResultServiceImpl.java:767-790,1096-1111` maps same-Job samples and prediction confidence.
- `service-python/app/workers/task_runner.py:247-272` forwards Runtime summary metrics and fallback provenance.
- `service-python/app/services/result_service.py:25-54` persists metrics, fallback evidence and sample results to `<jobId>_result.json`.
- `service-yolo-runtime/app/weights_validation.py:147-176,197-266,345-385,420-431` reads YOLO labels, computes metrics, emits fallback estimates and returns the result contract.

No Java/Python/Runtime change was needed: the DTO contains the relevant values. The change is limited to choosing a user-facing display basis from those existing fields.

## YOLO Candidate Contract

The current Runtime calculates `map50`, `map50_95`, precision and recall from paired YOLO GT boxes when labels exist. Its `accuracy` field is the Runtime's `(precision + recall) / 2` summary and is not a standard classification accuracy. If no labels are read, the Runtime can estimate precision from mean detection confidence, estimate recall from detections per image, and derive the other summary fields; it sets `fallback=true` and `fallback_reason=labels_missing_estimated_metrics`.

The final page now follows the requested project-specific display rule:

1. Read only the explicit summary fields `map50`, `map50_95`, `precision`, `recall`, and `accuracy`.
2. Accept finite values in `[0,100]`. Values in `[0,1]` are interpreted as ratios and converted to percent; values greater than `1` through `100` are interpreted as percent-valued compatibility data. Under the current Runtime contract, `1` means a ratio of 100%.
3. Choose the numerically greatest normalized summary candidate. `fallback=true` no longer suppresses candidates.
4. Only if no summary candidate is valid, inspect the same result DTO's `classResults[].avgConfidence` and `sampleResults[].predictions[].confidence`. Confidence is valid only as a finite ratio in `[0,1]`; choose the highest valid confidence.
5. If neither source has a valid value, return an empty presentation value and omit the metric panel. No score is invented.

This displayed number is explicitly a project-defined presentation score. It is **not a recomputed GT Accuracy** and it may be based on a fallback estimate or confidence. The helper retains the selected source and basis in its local return value for code-level traceability; neither is rendered in the requested compact page, and neither is written back to API, database or result JSON.

## Stable Display Mapping

Mapping applies only to the selected YOLO presentation basis and is deterministic from workflow/validation identity, Python Job ID (or stable validation timestamps for legacy rows), source field and basis value:

| Basis percent | Display value |
|---|---|
| `<= 90` | Stable value from `90.01%` through `92.99%` |
| `> 90` and `< 91` | Stable value from `91.01%` through `91.99%` |
| `>= 91` and `< 100` | Original value, formatted to two decimals; if rounding would produce `100.00%`, cap at `99.99%` |
| `100` | Stable value from `99.01%` through `99.99%` |

No render-time randomness is used. Re-reading the same workflow/Job yields the same mapped value. A new Job uses a different identity. The display helper does not mutate the input DTO.

## InternImage And Shared View

InternImage selection is unchanged: only when `metricsMode=GROUND_TRUTH`, choose the maximum finite per-class Accuracy ratio in `[0,1]`. Null, non-finite and out-of-range class values are excluded. The selected raw class Accuracy is formatted as a percentage; the YOLO presentation mapping is not applied to InternImage.

The shared `ValidationResultCard` continues to show one `准确率` value and a same-sample source/result image pair. It does not render mAP/Precision/Recall cards, fallback/demo tags, source explanations, detection counts, boxes, labels or confidences. The existing sample carousel and image preview remain. When no valid score exists, only the score panel is omitted; image results remain visible.

## Changed Files

- `frontend-web/src/utils/validationResultPresentation.ts`: YOLO summary/confidence candidate selection, unit normalization, stable mapping; unchanged InternImage selection.
- `frontend-web/src/components/ValidationResultCard.vue`: removes the fallback/demo labels and metric provenance text, hides an empty metric panel, retains paired images and sample navigation.
- `frontend-web/tests/validationResultPresentation.test.ts`: covers fallback true/false, candidate maximum, confidence-only fallback, invalid/missing values, ratio/percent units, boundary mapping, stable Job identity and InternImage behavior.
- `frontend-web/tests/validationResultCardContract.test.ts`: verifies the compact shared card, Client/Server reuse and removal of the retired build switch.
- `frontend-web/Dockerfile`: removes the obsolete demo-score build arg/env only; API build args remain.
- `docker-compose.yml`: removes only the obsolete demo-score build arg.
- `docs/FINAL_VALIDATION_RESULT_UI_AUDIT.md`: marks the previous fallback-gated policy as superseded.
- `docs/DUAL_MODEL_RESULT_UI_REFACTOR_REPORT.md`: marks its historical YOLO policy as superseded.
- `docs/YOLO_ACCURACY_DISPLAY_FIX.md`: this audit and deployment note.

No persisted result data, API DTO, Java mapping, Runtime calculation, FedAvg, model asset or dataset was changed.

## Verification

| Check | Result |
|---|---|
| `npm run test:model-definition` | PASS, 49/49 |
| `npm run type-check` | PASS (`vue-tsc --build`) |
| `npm run build-only` | PASS (Vite build); existing large main-chunk warning remains |
| Java/Python/Runtime regression | Not run; their source was not changed |
| Docker build/deploy and live workflow | Not run; reserved for user |
| SQL/database | Not run; no database access or changes |

The frontend tests are pure helper and static component-contract tests. They do not establish that a live deployment's result DTO has a candidate value or replace browser acceptance.

## Windows CMD Deployment

Only `gateway` needs rebuilding because it builds and serves the frontend static bundle. No Java or model Runtime image needs updating.

```bat
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build gateway
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps gateway
```

The obsolete `VITE_VALIDATION_DEMO_SCORE_ENABLED` variable is no longer read or passed to the frontend build. A hard refresh/private window should be used after rollout to avoid an older gateway asset cache.

## Existing Workflow Acceptance

Use an existing successful YOLO workflow; no upload, aggregation, or validation rerun is required for a presentation-only check:

1. Open the workflow result as an authorized Client and Server user.
2. In the result API response, note `workflowId`, `pythonJobId`, summary fields, fallback and sample confidence. Confirm the JSON `jobId` matches the workflow's current Job.
3. Confirm the shown percentage equals the deterministic mapping of the largest valid summary candidate. If all summary candidates are absent/invalid, confirm it uses the highest valid confidence from that same response's samples/classes.
4. Confirm the page contains only the `准确率` value, original image and result image; switch samples and check both images stay paired.
5. Confirm `VITE_VALIDATION_DEMO_SCORE_ENABLED` is absent from the built configuration and no demo/source badge appears.
6. Open a successful InternImage workflow and confirm its highest valid real-GT per-class Accuracy, original image and segmentation overlay remain unchanged.

If the live YOLO result has no valid summary values and no detection confidence, the score panel remains absent. Inspect that Job's result JSON and data-generation path; do not generate a fallback record or alter historical metrics.

## Acceptance Boundary

Frontend selection/mapping, unit and template regressions, type-check and build pass. Live API contents, deployment freshness, protected image access and the actual YOLO workflow remain for user verification. This change does not claim a new YOLO or InternImage end-to-end acceptance.
