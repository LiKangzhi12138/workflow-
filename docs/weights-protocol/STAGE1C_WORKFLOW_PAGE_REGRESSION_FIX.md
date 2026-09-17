# Stage 1C Workflow Page Regression Fix

## 1. Final Result

Code readiness: PASS. The repaired image has not been rebuilt or started by
Codex; the final HTTP verification is intentionally left to the user.

## 2. Runtime Evidence

Root Cause 1:

- Evidence: `service-java` logged `java.lang.NullPointerException` from
  `java.util.ImmutableCollections$MapN.get`, followed by
  `WorkflowServiceImpl.lambda$pageWorkflows$1(WorkflowServiceImpl.java:237)`.
- Code evidence: a page containing only legacy workflows produced an empty
  Definition ID set. `findDefinitionsByIds` returned `Map.of()`, after which the
  list mapper called `definitionMap.get(workflow.getModelDefinitionId())` with a
  null key. JDK immutable maps reject null keys.
- Files: `WorkflowServiceImpl.java`.
- Affected pages: CLIENT/SERVER workflow management and CLIENT/SERVER workflow
  progress, because all four pages load `GET /api/workflows` first.

Root Cause 2:

- Evidence: the Stage 1C diff contained duplicate `ModelDefinition` display
  blocks in `ClientWorkflowProgressView.vue` and duplicate list/detail blocks in
  `ServerWorkflowManageView.vue`.
- Files: `ClientWorkflowProgressView.vue` and
  `ServerWorkflowManageView.vue`.
- Impact: redundant UI fields after a successful response. This was not the
  exception that prevented the pages from opening, but it was a confirmed Stage
  1C presentation regression and has been removed.

## 3. API Failure Versus Frontend Rendering

The failure originated in the Java API, not Vue rendering. Gateway access logs
recorded these browser requests as HTTP 200 because the project exception
handler wraps runtime exceptions in an `ApiResponse` instead of changing the
transport status:

```text
GET /api/workflows?pageNum=1&pageSize=10   HTTP 200
GET /api/workflows?pageNum=1&pageSize=100  HTTP 200
```

At the same timestamps Java logged the NPE above. The response interceptor sees
the non-`OK` business code and rejects the request, so the four pages enter their
load-error paths. No `map is not a function`, undefined-property render error,
HTTP 500, 502, or 422 was present in the captured Gateway evidence.

When evidence was collected, `docker compose ps -a` showed all five containers
had exited approximately 13 minutes earlier. A new authenticated curl could not
be issued without restarting containers, which this task forbids. No runtime
response has been invented; post-fix HTTP confirmation is listed in the manual
acceptance steps below.

## 4. Four Read Chains

CLIENT workflow management:

```text
/client/workflows
ClientWorkflowManageView.vue
GET /api/workflows?pageNum&pageSize&status
GET /api/workflows/{id} for the detail drawer
WorkflowController -> WorkflowServiceImpl -> WorkflowMapper -> VO -> Vue
```

SERVER workflow management:

```text
/server/workflows
ServerWorkflowManageView.vue
GET /api/workflows?pageNum&pageSize&status
GET /api/workflows/{id} for the detail drawer
WorkflowController -> WorkflowServiceImpl -> WorkflowMapper -> VO -> Vue
```

CLIENT workflow progress:

```text
/client/progress?id={workflowId}
ClientWorkflowProgressView.vue
GET /api/workflows (all pages)
GET /api/workflows/{id}
GET /api/workflow-uploads/progress/{id}
```

SERVER workflow progress:

```text
/server/progress?id={workflowId}
ServerWorkflowProgressView.vue
GET /api/workflows (all pages)
GET /api/workflows/{id}
GET /api/workflow-uploads/progress/{id}
```

Both progress pages accept `route.query.id` or `route.params.id`, convert it with
`Number`, and use it only when finite and greater than zero. No 422 route-ID
regression was found.

## 5. Backend Repair

- Definition IDs are filtered for null before batch lookup.
- An empty ID set returns an empty map without querying the Definition service.
- Map lookup is now guarded before `get`, so `modelDefinitionId=null` is safe for
  immutable and mutable maps.
- A non-null ID whose Definition is absent logs a warning and keeps the stored
  ID plus legacy `yoloVersion`; list/detail reads do not fail.
- Workflow reads use only database Definition metadata. They do not call Python,
  Runtime health, self-check, or availability.
- There is no `INNER JOIN model_definition`; MyBatis Plus reads workflows first
  and performs an optional Java-side batch lookup.

The strict create-time path was not relaxed. New workflow creation still reloads
the Definition and re-checks availability.

## 6. Frontend Repair

- Workflow DTOs now explicitly permit null Definition ID/code/display name.
- Workflow list and detail responses pass through one null-safe metadata
  normalizer.
- All four pages use `getWorkflowModelDisplayName`:
  Definition display name, then legacy `yoloVersion`, then `未配置模型`.
- Duplicate Stage 1C display blocks were removed.
- The existing Available Definition selector and `modelDefinitionId` submission
  remain unchanged.

## 7. Tests

Java targeted run: PASS, 20 tests across the new read regression suite and the
Stage 1C create/availability suites. New read coverage includes:

- CLIENT legacy list;
- SERVER Definition list;
- CLIENT legacy detail/progress data;
- SERVER Definition detail/progress data;
- missing Definition list fallback;
- missing Definition detail fallback.

Frontend targeted run: PASS, 10 tests. It covers legacy and Definition display,
null IDs, null display-name fallback, invalid list normalization, progress detail
normalization, Available API behavior, and ID-only create payload.

Frontend `vue-tsc` and Vite production build: PASS. Compose static config:
PASS. `git diff --check`: PASS.

## 8. Protected Boundaries

No database operation or schema change was made. Upload, AES, FedAvg, global
model, validation, ModelDefinition create-time validation, yolo-runtime,
RuntimeProfile, and dependencies were not changed. No Docker build, restart, or
start was performed.

## 9. Manual Acceptance

After rebuilding only through the user's normal deployment procedure:

1. Log in as CLIENT and open `/client/workflows`.
2. Open `/client/progress?id={legacyWorkflowId}`.
3. Log in as SERVER and open `/server/workflows`.
4. Open `/server/progress?id={legacyWorkflowId}`.
5. Open both list/progress views for a Definition workflow with
   `model_definition_id=1`.
6. Confirm `GET /api/model-definitions/available` still returns
   `YOLOV8N_SHEEP_V1`.
7. Confirm all workflow GET responses have `code=OK`; no FedAvg or validation run
   is required.
