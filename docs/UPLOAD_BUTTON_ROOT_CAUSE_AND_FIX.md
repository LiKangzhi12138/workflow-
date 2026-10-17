# Upload Button: Root-Cause Audit and Diagnostic Fix

## Scope and evidence

The reported failure is on the CLIENT workflow progress page. The user confirmed that the workflow is `CREATED`, an asset and the three package files were selected, and the weights filename appears in the page's file table. They could not provide the displayed selected-file count or remaining-slot count. No live browser tab, Docker container, or database was available to this task. Therefore the exact false operand in that running page is **not yet proven**. In particular, the uploaded filename makes a total loss of the weights selection less likely, but does not prove that the Manifest/Descriptor refs, asset option membership, protocol, and server slot request all passed.

| Area | Source evidence | Conclusion |
| --- | --- | --- |
| Progress button | `frontend-web/src/views/client/ClientWorkflowProgressView.vue`, upload action bar and `canStartClientUpload` | `:disabled` is driven by readiness; the old computed used early returns and did not reveal which prerequisite failed. |
| File selection | Progress page `handleUploadFileChange`; `frontend-web/src/utils/workflowUploadSelection.ts` | Element Plus sends a queued file list; the previous deferred `clearFiles` fix retains the selected raw `File`. The user's visible filename confirms at least one file reached local state. |
| Metadata | Progress page `handleManifestFileChange` / `handleDescriptorFileChange` | Separate native file inputs populate separate refs; their presence cannot be inferred from the weights table. |
| Asset | Progress page `selectedUploadModelAssetId`, `resolvedClientModelAssetId`, `uploadAssetOptions` | V1 must use the current client's selected asset, not the first uploader's workflow binding. Membership is checked against the current account's validated asset list. |
| Protocol and quota | `getWorkflowUploadContract` and `getWorkflowUploadProgress`; Java `WorkflowModelUploadServiceImpl` | Backend does not require a pre-bound asset to return the trusted protocol. `initUpload` still checks asset ownership, workflow state, and an available slot. |
| Other entrance | `ClientWorkflowManageView.vue` | The old upload dialog code remains, but `openUploadDialog` has no caller in the current view; the management page routes users to progress. It is not the reported entrance. |
| Deployment | `frontend-web/Dockerfile`, `docker-compose.yml` gateway build | Gateway bundles the frontend. A Healthy gateway does not prove that its running image contains the latest source. No running-image evidence was supplied. |

## What changed

`frontend-web/src/utils/workflowUploadReadiness.ts` now evaluates each prerequisite independently and returns one shared `{ ready, reasons }` result. The progress page uses **that exact result** both for the button and for the visible Chinese reasons. It also exposes non-sensitive reason codes on the button as `data-upload-reasons` for browser diagnosis. Missing/stale upload-progress responses fail closed with a specific reason instead of silently appearing as zero slots. A single-model replacement is shown only when the server's `replaceAllowed` is true; multi-model remaining slots use the server's active upload count. The click handler rechecks the same readiness before encryption and `initModelUpload`, so this does not just remove `disabled`.

The management page's existing dialog uses the same evaluator if that legacy entrance is wired again. No upload protocol, encryption, descriptor/manifest validation, Java authorization, FedAvg, Runtime, or database code changed in this fix. The first and second client still submit their own selected `modelAssetId`; the server remains authoritative for compatibility and quota. One V1 upload is one `.pt` plus Manifest and Descriptor, not a batch of multiple weights files.

The prior fix addressed the Element Plus queue and inherited-asset hazards, but its tests did not mount the live progress page and did not expose all disabled conditions. It could not establish the actual reason in the user's running gateway. The new diagnostics make the next real observation decisive; they are not a claim that the already-running page was fixed without that observation.

## Tests

- `cd frontend-web && npm run test:model-definition`: **PASS, 62/62**. Includes reactive readiness transitions, every blocked reason, valid second-client selection, V1/legacy contract rules, and static wiring from button guard to `initModelUpload`. These use synthetic `File` objects and do not execute a real upload.
- `cd frontend-web && npm run type-check`: **PASS**.
- `cd frontend-web && npm run build-only`: **PASS**; Vite reported a large-bundle warning, not a failure.
- Component DOM/browser upload test: **NOT RUN**. The repository has no installed Vue DOM test runner or DOM environment; dependencies were not installed under this task's constraints.
- Java upload test: **NOT RUN**; Java upload code was not changed. The previous Maven offline Surefire gap remains unverified here.
- Real Docker/browser/A+B/FedAvg: **PENDING USER ACCEPTANCE**. No Docker, SQL, or business task was run by Codex.

## Windows CMD deployment and exact diagnosis

Only `gateway` needs rebuilding for this frontend change. Run in Windows CMD:

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server --profile model-runtime config --quiet
docker compose --env-file .env.local-server --profile model-runtime build gateway
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps gateway
docker compose --env-file .env.local-server --profile model-runtime ps gateway
docker compose --env-file .env.local-server --profile model-runtime images gateway
```

Hard-refresh the CLIENT progress page (`Ctrl+F5`). For a `CREATED` Definition workflow with a remaining slot, select the current client's validated model asset, then `weights.pt`, Manifest, and Descriptor. The page now lists every unmet condition immediately below the upload button. In browser DevTools Console, the following reads only DOM attributes; it sends no request and reveals no file contents or credentials:

```js
const button = document.querySelector('button[data-upload-readiness-version="2"]');
console.log({ newGatewayLoaded: !!button, disabled: button?.disabled, reasons: button?.dataset.uploadReasons });
```

If `newGatewayLoaded` is false, check the gateway image/container identity and browser cache first; the running UI is not the new diagnostic build or this is a different page. If true, send back the visible reason text or `reasons` codes, plus the page's selected-file and remaining-slot counts. That identifies the **actual** failed condition without a guessed patch. For example, `MANIFEST_NOT_SELECTED` means the Manifest input ref did not retain a file; `MODEL_ASSET_NOT_AVAILABLE` means the selected ID is not in this account's validated options; `UPLOAD_SLOTS_FULL` means the server reports no open slot. Do not bypass any of these by changing the DOM.

When `reasons` is empty and the button is enabled, click once. In DevTools Network verify the `POST /api/workflow-uploads/init` request contains the current workflow ID and this client's selected `modelAssetId`; then verify upload/receive and the server upload-progress record. Repeat with CLIENT B's own asset while the original state machine still permits another slot. Check both YOLO and InternImage workflows. A successful `init` and receive, not merely an enabled button, is the acceptance condition. No part of this procedure reuses another client's asset or bypasses server validation.
