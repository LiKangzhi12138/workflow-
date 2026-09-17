# Stage 5B InternImage Weights Upload And FedAvg Report

## Result

Stage 5B Code/Config Readiness: **PASS**

Stage 5B adds InternImage-T + UPerNet to the existing Protocol v1 upload and
generic FedAvg path. It does not implement InternImage Validation, inference,
mask rendering, or metrics. Real Docker upload and FedAvg certification remain
pending user acceptance.

## Scope

Definition:

```text
INTERNIMAGE_T_UPERNET_WHEAT_V1
runtimeProfileId = INTERNIMAGE_RUNTIME_V1
taskType = SEMANTIC_SEGMENTATION
classOrder = background, healthy_wheat, lodged_wheat
architectureSignature = be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f
definitionSha256 = f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700
expected tensorCount = 728
expected elementCount = 58,963,958
```

The formal input remains exactly:

```python
{"state_dict": OrderedDict[str, Tensor]}
```

No full checkpoint, model object, optimizer, scheduler, pretrained checkpoint,
or base checkpoint is accepted by the V1 path.

## Existing Code Audit

### Already Generic

The following Stage 2A/3 components were already model-family neutral and were
reused without a second InternImage business path:

- browser upload contract: `weights.pt + manifest.json + descriptor.json`;
- AES upload, Java quarantine, controlled cleanup, and formal asset registration;
- `WeightsPackageInspector`: strict wrapper, path/SHA/resource/signature/dtype/
  finite checks and `torch.load(..., weights_only=True)`;
- Java trust root: `workflow.model_definition_id` and server-side Definition reload;
- `WORKFLOW_WEIGHTS_V1` formal input asset and immutable sidecars;
- Java Stage 3 readiness, identity, SHA, sidecar, and asset-state checks;
- `WeightsFedAvgV1Service`: ordered tensor-only equal FedAvg;
- `FEDERATED_WEIGHTS_V1` output plus aggregation evidence sidecars.

### Previous YOLO Coupling

The upload compatibility service directly held a `YoloRuntimeClient`, and only
`yolo-runtime` exposed the expected-state compatibility endpoint. This prevented
an otherwise valid InternImage package from reaching a model-specific expected
state check.

### Minimal Stage 5B Changes

1. `ModelPackageValidationService` now obtains the compatibility client from the
   existing `RuntimeClientRegistry` using the trusted Definition's
   `runtimeProfileId`.
2. Runtime transport failures share a generic `RuntimeClientError`; adding a
   future Runtime no longer changes the upload validator's exception list.
3. `internimage-runtime` exposes the same strict expected-state compatibility
   contract as the YOLO Runtime.
4. The workflow-selectable RuntimeProfile allowlist now contains both verified
   profiles.

Legacy YOLO-specific checkpoint aggregation remains isolated in the unchanged
legacy service. The only first-party `weights_only=False` occurrence found by
the audit is that trusted legacy loader; no V1 path calls it.

## InternImage Upload Chain

```text
browser selects weights.pt + manifest.json + descriptor.json
  -> existing browser AES encryption of weights.pt
  -> existing Java upload API
  -> decrypt to server-controlled quarantine
  -> service-python generic Safe Inspector
  -> Java/server trusted workflow.model_definition_id
  -> trusted INTERNIMAGE_T_UPERNET_WHEAT_V1
  -> RuntimeClientRegistry[INTERNIMAGE_RUNTIME_V1]
  -> internimage-runtime expected-state compatibility
  -> architecture-only trusted template build
  -> model.state_dict() expected contract
  -> exact key order/set, per-key shape, dtype and element-count checks
  -> WORKFLOW_WEIGHTS_V1 / WEIGHTS_PROTOCOL_V1 / FORMAL_ASSET / READY / OK
```

The Runtime compatibility endpoint does not load uploaded weights or a
checkpoint. `service-python` safely loads the uploaded wrapper only to extract
actual tensor metadata, always with `weights_only=True`, then sends metadata to
the Runtime. The Runtime builds the trusted architecture and compares:

- exact ordered state_dict keys;
- every tensor shape;
- expected and actual tensor/element counts;
- dtype compatibility (`float16` or `float32` for expected floating state;
  non-floating state requires exact dtype);
- RuntimeProfile and complete trusted Definition availability/self-check.

Duplicate keys, negative dimensions, unavailable Definitions, build failures,
missing/extra keys, shape mismatch, and dtype mismatch fail closed.

## Trust Boundary

The manifest and descriptor remain untrusted claims. They cannot select the
Definition or Runtime. The server compares their protocol Definition ID,
Definition SHA, architecture signature, framework/task/classes, parameter
semantics, and measured state evidence against:

```text
workflow.model_definition_id
  -> server DB ModelDefinition
  -> canonical trusted Definition JSON
  -> trusted runtimeProfileId
  -> architecture-only expected state
```

Client `aggregationContribution` is also untrusted and does not control FedAvg.

## Generic FedAvg Reuse

No aggregation algorithm was added for InternImage. Two compatible formal
InternImage inputs enter the Stage 3 generic engine. The frozen policy remains:

| Input | Accumulator | Output | Rule |
| --- | --- | --- | --- |
| float16 | float32 | float32 | equal mean |
| float32 | float64 | float32 | equal mean |
| int64 | none | int64 | all clients equal, then copy |

Inputs must have identical ordered keys, shapes, and input dtypes and must pass
finite/SHA/sidecar checks again. The server generates weights `1/N`; the engine
does not import InternImage, MMCV, MMSegmentation, timm, DCNv3, or Ultralytics.

The output is still:

```python
{"state_dict": OrderedDict[str, Tensor]}
```

Its formal asset is:

```text
source_type       = FEDERATED_WEIGHTS_V1
import_mode       = WEIGHTS_PROTOCOL_V1
record_mode       = FORMAL_ASSET
status            = READY
last_check_status = OK
```

The output directory contains `global_weights.pt`,
`aggregation-manifest.json`, `descriptor.json`, `inspection-report.json`, and
`aggregation-report.json`. No full InternImage checkpoint is created.

## Workflow Selectable Decision

Stage 5B enables `INTERNIMAGE_RUNTIME_V1` in
`MODEL_DEFINITION_WORKFLOW_SELECTABLE_RUNTIME_PROFILES`.

This is safe because workflow creation does not require Validation capability.
The lifecycle can complete upload and FedAvg and stop at `联邦学习完成`.
Validation is a separate explicit user action. Stage 5B acceptance must not click
Validation; InternImage Validation remains Stage 5C.

The frontend already reads AVAILABLE Definitions and `workflowSelectable`
dynamically. It submits only `modelDefinitionId`; no InternImage parameters or
PyTorch parsing were added. Definitions marked `workflowSelectable=false`
continue to be filtered.

## Checkpoint Boundary

```text
checkpointUsed     = false
baseCheckpointUsed = false
pretrainedUsed     = false
```

The expected state is derived from the trusted architecture template, and every
V1 load in service-python/package tooling uses `weights_only=True` with no unsafe
fallback. The Stage 5B InternImage Runtime compatibility endpoint performs no
`torch.load` at all.

## Database

DB Schema Change Required: **NO**

Existing `model_definition`, nullable `workflow.model_definition_id`,
`workflow_model_upload`, `model_asset`, and workflow federated fields already
represent the complete lifecycle. The generic source/import types distinguish
formal input and global weights. No SQL was executed and no migration proposal
is needed.

## Automated Tests

| Layer | Result |
| --- | --- |
| InternImage Runtime full tests | 30 passed |
| service-python Stage 5B focused upload/FedAvg | 16 passed, 3 subtests passed |
| service-python full regression | 91 passed, 1 skipped, 3 subtests passed |
| YOLO Runtime regression | 17 passed, 3 subtests passed |
| Java Stage 5B/Stage 3 targeted, JDK 21 isolated temp/storage | 30 passed |
| Java full regression, JDK 21 isolated temp/storage | 142 total, 140 passed, 0 failures, 0 errors, 2 skipped |
| Frontend targeted tests | 28 passed |
| vue-tsc + Vite production build | PASS; existing bundle-size warning only |
| Docker Compose static config | PASS |

The service-python full suite still emits the existing `test_pressure.py`
thread warning because no local HTTP service is running. It does not fail a test
and is unrelated to Stage 5B production code.

Synthetic protocol tests cover valid InternImage dispatch, trusted Definition
mismatch, exact keys/shapes/dtypes, missing/extra keys, wrong shape/dtype,
unavailable Definition, safe inspector failures, and two-client generic float32
FedAvg. Existing Stage 1A/2A/3 tests continue to cover NaN/Inf, unsafe/full
checkpoint structures, SHA tamper, path escape, resource limits, incompatible
clients, duplicate assets, and YOLO behavior.

The 225 MiB lab artifacts are not committed and are not required by CI. Manual
certification uses the existing safe files read-only:

```text
client_a/weights_only.pth SHA256 aa1570cbbc4a816efb4fcb3c96c546d95521a3cd36f093e359f01761b504a48c
client_b/weights_only.pth SHA256 bfde8cc61e44ef37eb9ac9335733b158fdd69334962e14a46ab0543447dcbf81
```

## Stage 5B Files

- `.env.example`
- `docs/weights-protocol/PROTOCOL_V1.md`
- `docs/weights-protocol/STAGE5B_REPORT.md`
- `docs/weights-protocol/STAGE5B_MANUAL_TEST.md`
- `service-internimage-runtime/app/main.py`
- `service-internimage-runtime/app/schemas.py`
- `service-internimage-runtime/app/weights_compatibility.py`
- `service-internimage-runtime/tests/test_runtime.py`
- `service-python/app/schemas/model_protocol.py`
- `service-python/app/services/runtime_client_error.py`
- `service-python/app/services/internimage_runtime_client.py`
- `service-python/app/services/yolo_runtime_client.py`
- `service-python/app/services/model_package_validation_service.py`
- `service-python/tests/test_internimage_runtime_registry.py`
- `service-python/tests/test_model_package_validation.py`
- `service-python/tests/test_weights_fedavg_v1.py`
- `service-java/src/main/java/com/workflow/config/ModelDefinitionRegistryProperties.java`
- `service-java/src/main/resources/application.yml`
- `service-java/src/test/java/com/workflow/service/impl/ModelDefinitionServiceImplTest.java`
- `service-java/src/test/java/com/workflow/service/impl/WeightsProtocolUploadServiceTest.java`
- `frontend-web/tests/modelDefinitionSelection.test.ts`

## Remaining Work

Stage 5C remains unimplemented: InternImage global weights reconstruction,
segmentation Validation, mIoU/pixel metrics, masks, samples, and result display.
Stage 5B ends at a formal `FEDERATED_WEIGHTS_V1` asset.
