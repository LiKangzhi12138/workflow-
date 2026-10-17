# Final Validation Result UI Audit

> Historical snapshot of the previous implementation. Its YOLO fallback gate, demo-score option, deployment commands, and acceptance steps are superseded; use `YOLO_ACCURACY_DISPLAY_FIX.md` for the current behavior and instructions.

## Scope And Audit

This change is limited to the shared validation-result presentation and an opt-in frontend demo score. No Java, Python, model runtime, upload, FedAvg, callback, database, or formal result-writing code was changed.

| Issue | Finding from current source | Evidence | Change |
|---|---|---|---|
| YOLO score is empty | The display intentionally accepts `map50` only when the result explicitly says `fallback=false`. The YOLO Runtime computes GT metrics only when at least one paired label is loaded. Without labels it writes estimated values with `fallback=true`; those estimates are correctly withheld in formal mode. The current production Job/result file and mounted dataset were not available here, so this is the source-level explanation, not a confirmed diagnosis of a particular live Job. | `service-yolo-runtime/app/weights_validation.py:147-176,197-266,345-385`; `service-python/app/services/result_service.py:25-54`; `service-java/.../ValidationResultServiceImpl.java:184-194`; `frontend-web/src/utils/validationResultPresentation.ts:92-103` | Kept the fail-closed real-GT rule. Did not manufacture or relabel a score. Added a distinctly labeled, opt-in presentation-only demo mode. |
| InternImage shows unwanted metrics | Its Runtime result includes `mIoU`, `mAcc`, `aAcc`, and per-class IoU/accuracy from the real GT confusion matrix. The old shared card rendered secondary metrics and class detail. | `service-internimage-runtime/app/segmentation_metrics.py:25-58`; old card template in `frontend-web/src/components/ValidationResultCard.vue` | Card now shows only the unified `准确率` value and a small provenance note. Runtime metrics and API fields remain intact. |
| Detection counts/details are too prominent | The previous shared card rendered prediction count, per-object labels, confidence and bbox coordinates, plus class tables. These fields remain available in result data but are not needed for the final business view. | `frontend-web/src/components/ValidationResultCard.vue` | Removed those details from this card; retained the source/result image pair and simple sample navigation. |
| Source/result image pairing | Java builds image URLs from the current result's sample index and marks whether an annotated result exists. The shared card previously displayed all sample cards rather than one matched pair. | `service-java/.../ValidationResultServiceImpl.java:728-855`; card sample selection in `frontend-web/src/components/ValidationResultCard.vue` | The card uses one `activeSample` for both panels. It shows the original source separately and only shows the result panel when the sample is marked annotated. Missing images degrade independently. |
| Formal score vs demo score | Production result JSON is not modified by the demo feature. A display-only deterministic score can be enabled only at frontend build time, and is labeled as demo data with its source metric. | `frontend-web/src/utils/validationResultPresentation.ts`; `frontend-web/Dockerfile:12-16`; `docker-compose.yml:3-9` | Added an explicit build arg, defaulting to `false`, and deterministic boundary tests. |

## YOLO Metric Finding

The YOLO Runtime's formal evaluator reads labels from `labels/<image-relative-path>.txt`, paired to the image under `images/` (or to the dataset root when there is no `images/` directory). Each line is parsed as a YOLO class id and normalized center-x, center-y, width, and height. `_evaluate` matches predictions to GT boxes by class and IoU, computes AP for thresholds 0.50 through 0.95, and returns `map50`, `map50_95`, precision and recall (`weights_validation.py:147-176,197-266`). These values are ratios in `[0,1]` and the UI formats them as percentages.

When labels are present, `fallback=false` and `map50` is the mean class AP at IoU 0.50 for classes with GT. Runtime also writes:

- `precision`: precision at IoU 0.50 from TP/(TP+FP).
- `recall`: recall at IoU 0.50 from TP/GT.
- `map50_95`: mean AP across IoU 0.50 through 0.95.
- `accuracy`: `(precision + recall) / 2`, a project-derived summary, not a standard classification accuracy.
- Per-detection `confidence`: prediction confidence, not an evaluation metric.

When no labels are read, the current Runtime estimates precision from mean detection confidence, estimates recall from detections per image, then derives `map50`, `map50_95`, and `accuracy`; it sets `fallback=true` and `fallback_reason=labels_missing_estimated_metrics` (`weights_validation.py:345-385`). These values are not GT evaluation and must not be shown as formal accuracy. The result writer preserves fallback provenance at the result root and in metrics (`result_service.py:25-54`); the Java view maps `map50`, fallback and fallback reason (`ValidationResultServiceImpl.java:184-194`).

Therefore, the current source does not establish whether a particular user's empty score is caused by missing GT labels, an older result without fallback evidence, or a stale deployed frontend/API. No live DB, Docker volume, or current result JSON was inspected in this task. For the affected workflow, inspect the latest workflow `pythonJobId`, the corresponding `<jobId>_result.json` and its `metrics.map50`, `metrics.fallback`, `fallbackReason`, then verify that every validation image has a readable paired YOLO label file. If `fallback=true` or labels are absent, bind a valid labeled dataset and use the existing revalidation flow; do not alter metrics or remove the UI gate. If the file has `fallback=false` and numeric `map50` but the API omits it, inspect the deployed Java image/API response before changing source.

## Result Presentation

The shared `ValidationResultCard.vue` is still used by both Client and Server workflow result views. It now presents:

- Model and status.
- One main value titled `准确率`.
- The original image and one result image side by side.
- Previous/next controls when multiple samples exist.
- Existing safe failure text and independent image empty states.

YOLO formal mode displays only real-GT `map50`, with the quiet note `真实 GT · mAP@0.5`. It does not substitute precision, recall, confidence, or the derived `accuracy` field. If fallback evidence is true or missing, the card says `暂无有效指标`.

InternImage formal mode requires `metricsMode=GROUND_TRUTH`, then selects the greatest finite per-class `accuracy` in `[0,1]`. Null/NaN/out-of-range entries are excluded; an absent class has null accuracy from the Runtime and cannot win. The label is `准确率`; a quiet note identifies it as the highest valid class accuracy. The card does not render mIoU/mAcc/aAcc, class tables, or the separate prediction-mask image. Those metrics and mask evidence remain in Runtime output/API and protected backend artifact paths.

The result and source images are selected from the same `activeSample`. Java loads the workflow's current job result and rejects a result JSON whose `jobId` conflicts with the workflow's current `pythonJobId` (`ValidationResultServiceImpl.java:495-520`); sample artifacts are tied to that job (`:728-855`). Client/Server views continue to use the same result component and existing backend authorization/image endpoints. These source checks and automated tests do not replace browser verification against a live deployment.

## Demo Score Isolation

`VITE_VALIDATION_DEMO_SCORE_ENABLED` is a frontend build-time switch. It defaults to `false` in both the Dockerfile and Compose interpolation. Formal mode always uses the real-GT rules above and performs no score adjustment.

When explicitly enabled, only the YOLO presentation helper may choose the largest finite numeric candidate among `map50`, `map50_95`, `precision`, `recall`, and the derived `accuracy`. Confidence is not a candidate. The result carries the source metric name in its presentation metadata and the UI displays `演示数据` plus the source/provenance note. This score is derived deterministically from workflow/validation ID and Python job ID: values at or below 90% map into 90.01%-92.99%; values above 90% and below 91% map into 91.01%-91.99%; values at/above 91% remain unchanged. A stable hash is used, not render-time randomness.

The value exists only in the Vue computed presentation. It does not modify the API DTO, database, result file, Runtime output or exports. Do not enable it for formal acceptance or production. No environment file was changed.

## Changed Files

- `frontend-web/src/components/ValidationResultCard.vue`: compact shared one-metric/two-image result view and one-sample navigation.
- `frontend-web/src/utils/validationResultPresentation.ts`: formal metric selection, ratio formatting and isolated stable demo presentation.
- `frontend-web/tests/validationResultPresentation.test.ts`: formal metric, fallback, boundaries and demo behavior tests.
- `frontend-web/tests/validationResultCardContract.test.ts`: presentation contract and build-flag wiring assertions.
- `frontend-web/package.json`: includes the result tests in the existing frontend test command.
- `frontend-web/Dockerfile`: adds the false-by-default frontend build arg.
- `docker-compose.yml`: forwards the optional build arg to gateway.
- `docs/FINAL_VALIDATION_RESULT_UI_AUDIT.md`: this audit and handoff.

No Java DTO, Python result writer, YOLO Runtime, InternImage Runtime, database, model asset, or historical result file was changed.

## Verification

| Check | Result | Notes |
|---|---|---|
| `npm run test:model-definition` | PASS, 47/47 | Includes the current frontend focused regressions and the new demo build-flag assertion; unit/static contract tests, not live browser/integration tests. |
| `npm run type-check` | PASS | `vue-tsc --build`. |
| `npm run build-only` | PASS | Vite build succeeded. Existing large-chunk warning remains (main JS about 1.22 MB minified). |
| `git diff --check` | PASS | No whitespace errors; Git emitted line-ending normalization warnings for existing modified files. |
| Java/Python/Runtime tests | NOT RUN | No backend/runtime source was changed in this focused UI pass. |
| Docker build/deploy or real GPU validation | NOT RUN | Must be performed by the user; no production workflow was started. |
| Database/SQL | NOT RUN | No DB access or writes. |

Automated checks verify pure presentation logic and a static Vue template contract. They do not prove that a currently deployed gateway serves the new bundle, that a live workflow's result JSON contains valid labels/metrics, or that protected images load in the user's browser.

## Windows CMD Deployment

For normal formal behavior, rebuild only `gateway`; it owns the frontend Docker build and static assets. No Java or model Runtime image needs rebuilding for these changes.

```bat
cd /d D:\workspace\workflow-platform
set VITE_VALIDATION_DEMO_SCORE_ENABLED=false
docker compose --env-file .env.local-server config --quiet
docker compose --env-file .env.local-server build gateway
docker compose --env-file .env.local-server up -d --no-deps gateway
docker compose --env-file .env.local-server ps gateway
```

After deployment, hard-refresh the browser or use a private window to rule out cached static assets. Do not enable demo mode for formal verification. For a dedicated demo-only deployment, explicitly set `VITE_VALIDATION_DEMO_SCORE_ENABLED=true` in the CMD environment before rebuilding `gateway`, then verify the visible `演示数据` tag; rebuild again with `false` before returning to formal use.

## Manual Acceptance

Use existing successful YOLO and InternImage workflows without re-uploading weights or rerunning FedAvg:

1. Open the workflow result as both an authorized Client and Server user; confirm each side is authorized according to existing workflow policy.
2. Confirm the latest Job's model/status and one main value. For YOLO, compare `map50` and `fallback` against that Job's result JSON. For InternImage, compare the selected value with the maximum valid `perClass[].accuracy` under `metricsMode=GROUND_TRUTH`.
3. Confirm the original and result images belong to the same visible sample; move between samples and check both images change together. Missing one image should leave the other panel visible.
4. Confirm YOLO shows its rendered detection image and InternImage shows its segmentation overlay, not the standalone mask.
5. In formal deployment, confirm no `演示数据` tag and no altered value. In a separate demo-only build, confirm the tag, stable value after refresh, and unchanged API/result JSON.
6. Use the existing revalidation action only when intentionally testing it; verify it creates a new validation Job and does not rerun aggregation. This report does not initiate revalidation.
7. Confirm upload, aggregation, strict Runtime load, callback, history and image authorization remain operational. This frontend-only automation is not a substitute for the user's end-to-end checks.

## Acceptance Boundary

Code-level UI simplification and frontend checks pass. The source contains an explicit reason why fallback YOLO metrics are withheld, but the specific live YOLO workflow's GT availability remains unconfirmed until its current Job JSON and dataset label pairing are inspected. Docker deployment, browser cache, live Client/Server authorization, and real workflow result/image verification remain pending user acceptance. No claim of live dual-model acceptance is made here.
