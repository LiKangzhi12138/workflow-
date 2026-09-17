# Stage 1C Minimal Model Selection

| Field | Backend required | Why | Derived from Definition | Client must choose | Default display |
| ----- | ---------------- | --- | ----------------------- | ------------------ | --------------- |
| `modelDefinitionId` | Yes | Trusted model identity and workflow binding | No; it identifies the Definition | The client must resolve one unique AVAILABLE Definition and submit this ID | Indirectly through the selection controls |
| `modelFamily` | Yes | Adapter and runtime routing | Yes | Only as a UI discriminator when multiple families are AVAILABLE | Yes, as the primary model type |
| `version` | Yes as nullable metadata | Model identification and runtime/Definition contract; current FedAvg does not aggregate by this label | Yes | Only when it is required to distinguish candidates | Only when non-empty; static if it does not require a choice |
| `variant` | Yes | Identifies architecture/parameter-scale variants | Yes | Only when family and version do not uniquely identify a candidate | No; shown only for real ambiguity |
| `taskType` | Yes | Adapter output semantics and validation/result interpretation | Yes | Only when earlier dimensions still leave multiple candidates | No; shown only for real ambiguity |
| `classes` | Yes | Output head/class mapping, result interpretation, and future weights compatibility | Yes | No; it cannot be edited or submitted independently | No |
| `framework` | Yes | Runtime framework selection and integrity checks | Yes | No | No |
| `frameworkVersion` | Yes | Runtime compatibility and fail-closed availability checks | Yes | No | No |
| `runtimeProfileId` | Yes | Trusted server-side runtime dispatch | Yes | No | No |
| `architectureSignature` | Yes | Definition integrity and weights structure compatibility | Yes | No | No |
| `displayName` | Yes | Management, audit, and final human-readable disambiguation | Yes | Only the Definition choice represented by it, never the string as identity | Only as the final discriminator |

## Design conclusion

The client does not configure protocol metadata. Its responsibility is to select exactly one Definition from `GET /api/model-definitions/available`. The create request continues to submit only `modelDefinitionId`; Java reloads the trusted Definition and performs the existing create-time availability check.

Metadata that the backend needs is not automatically user input. `modelFamily`, `version`, `variant`, `taskType`, classes, framework requirements, runtime binding, and architecture signature remain in ModelDefinition and are not duplicated in the request.

The legacy hard-coded YOLO version selector remains only behind the existing registry-disabled compatibility path. It is not a source of candidates for the ModelDefinition path, and an empty or failed AVAILABLE response never falls back to it.

## Minimal disambiguation algorithm

1. Group AVAILABLE Definitions by non-empty `modelFamily`.
2. If one family exists, show it statically; otherwise require a family choice.
3. Within the selected family, add a version selector only when all remaining candidates have versions and distinct versions are needed to narrow the set. A single shared non-empty version is informational and is not another user action.
4. If candidates remain, add `variant` only when distinct non-empty variants can narrow the set.
5. If candidates remain, add `taskType` only when distinct non-empty task types can narrow the set.
6. If candidates are still ambiguous, show a final human-readable Definition selector using `displayName`. Duplicate names include `code` to keep the choice unambiguous.
7. A workflow cannot be created until the candidate set resolves to one Definition ID.

The algorithm is data-driven. It contains no YOLO- or InternImage-specific display branch. A null version is omitted rather than rendered as `null`, `undefined`, or `-`. Classes never become an editable or default selection dimension; class-specific models are distinguished through trusted Definition identity and, when necessary, `displayName`.

With the current single `YOLOV8N_SHEEP_V1` Definition, the UI automatically binds its ID and shows only:

```text
模型类型  YOLO
模型版本  YOLOv8
```

It does not show task, classes, framework, framework version, runtime profile, or architecture signature.

## Workflow read display

Definition-backed workflow list/detail/progress responses now include nullable `modelDefinitionFamily` and `modelDefinitionVersion`, both loaded from the registry record without an availability/runtime call. The shared frontend helper renders `family / version`, omits an empty version, and falls back to `modelDefinitionDisplayName` and then legacy `yoloVersion`.

Historical workflows with `model_definition_id = NULL` remain valid. Missing referenced Definitions also retain the established graceful legacy fallback. CLIENT/SERVER management and progress pages all use the same helper.

## Protocol and compatibility boundary

- ModelDefinition schema and database columns were not removed or weakened.
- Future weights-only compatibility still has access to architecture signature, ordered classes, framework/runtime metadata, and state contracts.
- Current FedAvg, upload/AES, global model, and validation paths were not changed.
- Create-time Definition availability re-check remains unchanged.
- No InternImage implementation or model-family-specific UI data was added.
- No database schema or data was changed.
- Stage 2 was not started.

## Tests

Frontend targeted tests cover:

- one family/one Definition automatic resolution;
- version disambiguation;
- variant disambiguation;
- task type disambiguation;
- final `displayName` disambiguation;
- classes excluded as a default selection field;
- null InternImage version omission;
- multiple-family selection;
- final request identity remaining `modelDefinitionId`;
- AVAILABLE filtering/fail-closed behavior;
- legacy and Definition workflow display normalization.

Results:

```text
npm run test:model-definition: 20 passed
npm run type-check: PASS
npm run build: PASS
```

Java targeted tests cover trusted create selection, available filtering, and Legacy/Definition/missing-Definition workflow read behavior, including nullable family/version fields:

```text
WorkflowServiceImplReadModelDefinitionTest
WorkflowServiceImplCreateWorkflowTest
WorkflowModelDefinitionSelectionServiceTest
ModelDefinitionServiceImplTest

Result: PASS
```

Compose static parsing:

```text
docker compose --env-file .env.local-server --profile model-runtime config --quiet
Result: PASS
```

No Docker container was built, started, stopped, or restarted.

## Modified files

```text
frontend-web/package.json
frontend-web/src/api/modelDefinition.ts
frontend-web/src/api/workflow.ts
frontend-web/src/utils/minimalModelSelection.ts
frontend-web/src/utils/workflowModelDisplay.ts
frontend-web/src/views/client/ClientWorkflowManageView.vue
frontend-web/tests/minimalModelSelection.test.ts
frontend-web/tests/workflowModelDisplay.test.ts
service-java/src/main/java/com/workflow/dto/workflow/WorkflowListItemVO.java
service-java/src/main/java/com/workflow/dto/workflow/WorkflowDetailVO.java
service-java/src/main/java/com/workflow/service/impl/WorkflowServiceImpl.java
service-java/src/test/java/com/workflow/service/impl/WorkflowServiceImplReadModelDefinitionTest.java
docs/weights-protocol/STAGE1C_MINIMAL_MODEL_SELECTION.md
```

## Manual deployment

Run from Windows CMD after reviewing the local changes:

```cmd
cd /d D:\workspace\workflow-platform
docker compose --env-file .env.local-server --profile model-runtime build service-java gateway
docker compose --env-file .env.local-server --profile model-runtime up -d --no-deps service-java gateway
```

Manual acceptance should confirm the current single Definition is automatically selected, the request contains `modelDefinitionId`, multi-dimension controls appear only for synthetic/real ambiguous AVAILABLE data, and Legacy plus Definition workflows still render on CLIENT/SERVER management and progress pages.
