# Weights-only Model Protocol v1

## 1. Purpose

Protocol v1 defines a model-family-neutral contract for inspecting and aggregating
weights packages. It remains additive: Definition workflows can use the v1 upload
and weights-only FedAvg paths, while legacy checkpoint upload/aggregation and
validation remain separate compatibility paths.

The long-term reconstruction contract is:

```text
trusted ModelDefinition + trusted Runtime/Adapter + weights-only
                              -> strict model reconstruction
```

The server does not manage untrained YOLO checkpoints or pretrained InternImage
checkpoints. A ModelDefinition describes how to create an empty, structurally
correct model; it is not a path to a base checkpoint.

## 2. Protocol Objects

### 2.1 ModelDefinition

`ModelDefinition` is the trusted architecture contract. Its common fields include:

- identity and version: `definitionId`, `code`, `definitionVersion`;
- model identity: `modelFamily`, `version`, `variant`, `taskType`;
- framework identity: `framework`, `frameworkVersion`;
- construction contract: `definitionType`, `definitionPayload`;
- classes and input: `classCount`, `classOrder`, `ignoreIndex`, `inputSpec`;
- runtime binding: `runtimeProfileId`;
- integrity: `architectureSignature`, `definitionSha256`;
- lifecycle: `status`.

`definitionPayload` is deliberately open to model-specific data while the outer
contract remains stable. A YOLO definition can carry the complete Ultralytics
architecture dictionary, channels, class count, names, and stride. An InternImage
definition can carry a trusted MMSeg configuration/reference, preprocessing,
custom-module requirements, classes, and decoder information. Machine-specific
checkpoint paths are not part of this schema.

### 2.2 RuntimeProfile

`RuntimeProfile` describes the trusted execution environment required by a model
definition. It contains runtime type, supported families, Python/framework
requirements, CUDA/GPU requirements, adapter type, health-check type, and status.
It does not contain a model file path.

Stage 1A defines this schema only. It does not create YOLO or InternImage runtime
containers and does not claim either runtime is healthy.

### 2.3 ModelAvailabilityResult

A model is `AVAILABLE` only when all of the following are true:

1. The ModelDefinition exists, is enabled, and validates successfully.
2. A compatible ModelAdapter is registered.
3. The bound RuntimeProfile is ready.
4. The definition self-check passes in that runtime.

Unknown checks remain `null` and the result is `NOT_EVALUATED`; they are never
silently treated as successful. Stage 1A provides the result schema and a pure
evaluation skeleton, but no registry or runtime health probe.

### 2.4 ModelDescriptor

`ModelDescriptor` is the compatibility contract between client weights and a
trusted ModelDefinition. It records model/framework identity, definition identity
and digest, class order, ordered state contract, parameter semantics, input and
preprocessing contracts, runtime requirements, and extensions.

The state contract includes:

- `keyCount`, `tensorCount`, `elementCount`;
- `stateDictKeyHash`;
- `shapeSignature`;
- `clientDtypeSignature`.

### 2.5 WeightsPackageManifest

Client upload accepts `artifactType=CLIENT_WEIGHTS`. Stage 3 also permits the
server-generated global output type `artifactType=FEDERATED_WEIGHTS`. The manifest
binds the descriptor digest and ModelDefinition digest to one weights file and
records the file size/SHA256, strict wrapper format, ordered state signatures,
dtype counts, aggregation contribution, and producer metadata.

### 2.6 InspectionReport

`InspectionReport` is stable machine-readable output. It contains pass/fail,
errors, warnings, recomputed hashes and signatures, tensor counts, dtype counts,
finite-value counts, file size, and individual checks. Errors use stable codes
such as `WEIGHTS_SHA256_MISMATCH`, `INVALID_TOP_LEVEL`, `NON_FINITE`,
`RESOURCE_LIMIT_EXCEEDED`, and `PATH_NOT_ALLOWED`.

## 3. Weights File Contract

The only accepted serialization shape is:

```python
{
    "state_dict": OrderedDict[str, torch.Tensor]
}
```

No other top-level key is accepted. Values must all be dense, non-quantized
`torch.Tensor` objects. Protocol v1 allows only:

- `float16`;
- `float32`;
- `int64`.

Non-floating tensors are counted and reported. They are not aggregated by the
Inspector. A later CompatibilityChecker/Aggregator must apply an explicit policy,
such as `REQUIRE_IDENTICAL_AND_COPY`.

## 4. Safe Inspection

Loading is fail-closed and always uses:

```python
torch.load(weights_path, map_location="cpu", weights_only=True)
```

There is no `weights_only=False` fallback. Inspection is CPU-only and does not
construct YOLO, InternImage, MMSeg, Ultralytics, or DCNv3 models.

Before and after loading, the Inspector verifies:

- path containment under configured storage roots, with traversal and symlink rejection;
- file existence, regular-file type, and configured file-size limit;
- actual size and SHA256 against the manifest;
- strict top-level and OrderedDict/Tensor structure;
- key length, tensor count, total elements, single-tensor elements, and dimensions;
- dtype allowlist and dtype counts;
- ordered key, shape, and dtype signatures;
- descriptor/definition bindings and parameter semantics;
- NaN, positive infinity, and negative infinity counts.

Default limits are:

| Limit | Value |
| --- | ---: |
| `maxFileBytes` | 524,288,000 (500 MiB) |
| `maxTensorCount` | 10,000 |
| `maxTotalElements` | 250,000,000 |
| `maxSingleTensorElements` | 100,000,000 |
| `maxTensorDimensions` | 8 |
| `maxKeyLength` | 512 |

These limits cover the experimentally verified YOLO package (355 tensors,
3,021,500 elements, about 6 MiB) and InternImage package (728 tensors,
58,963,958 elements, about 225 MiB).

## 5. Canonicalization And Signatures

Descriptor and manifest digests use `CANONICAL_JSON_SHA256_V1`:

- UTF-8 encoding;
- stable sorting of JSON object keys;
- preserved array order;
- compact JSON separators.

State signatures preserve the original OrderedDict key order. Protocol v1 uses
the algorithms already proven by the weights lab:

```text
stateDictKeyHash = SHA256(join(ordered keys, "\n"))
shapeSignature   = SHA256(join("key:(shape, ...)" records, "\n"))
dtypeSignature   = SHA256(join("key:torch.dtype" records, "\n"))
```

State keys are never sorted for these signatures.

## 6. Internal Endpoint And Feature Flag

```text
POST /internal/model-packages/inspect
```

Request fields are `weightsPath`, `manifest`, and `descriptor`; the response is an
`InspectionReport`. The endpoint is controlled by
`WEIGHTS_PROTOCOL_V1_ENABLED`, which defaults to `false`. While disabled it
returns HTTP 404 and no existing business path calls it.

The Inspector's default allowed root is the shared storage root inferred as the
parent of `PYTHON_STORAGE_ROOT`. Deployments can explicitly set
`WEIGHTS_PROTOCOL_ALLOWED_ROOTS`. This configuration must name only trusted
server-controlled storage roots.

## 7. Client And Server Responsibilities

The browser frontend never parses PyTorch checkpoints. The future client flow is:

```text
local full checkpoint
    -> trusted local extraction tool / local ModelAdapter
    -> weights-only + manifest + descriptor
    -> SHA256 + AES + upload
```

The server accepts the resulting package only after inspection and compatibility
checks against the workflow's trusted ModelDefinition.

## 8. Future Workflow Binding

Future workflows must bind `modelDefinitionId`. Values such as `modelFamily`,
`version`, `variant`, `taskType`, framework, and classes must be derived from the
trusted definition rather than accepted as free-form client claims.

Stage 1A does not modify the existing workflow table/entity/API. Registry storage,
runtime binding, a nullable `workflow.model_definition_id`, real YOLO definition
registration, and available-definition queries belong to Stage 1B.

## 9. Stage 2A Formal Upload Binding

For a workflow with a non-null `workflow.model_definition_id`, Protocol v1 is
selected only when `WEIGHTS_PROTOCOL_V1_ENABLED=true`. The formal upload is:

```text
weights.pt + manifest.json + descriptor.json
    -> existing browser AES workflow for weights.pt
    -> Java-controlled encrypted storage
    -> Java decrypts into a controlled quarantine file
    -> POST /internal/model-packages/validate-upload
    -> generic safe inspection
    -> runtime-specific expected-state compatibility
    -> formal WORKFLOW_WEIGHTS_V1 asset and immutable sidecars
```

`workflow.model_definition_id` is the only trusted model identity. Manifest,
descriptor, original filename, and the legacy `modelAssetId` are declarations or
audit references and cannot select or override the trusted definition.

The browser does not parse PyTorch. It reads the two JSON sidecars only to append
them to the multipart request. The weights file retains the existing AES-256-CBC
upload flow. Metadata is sent in the same authenticated request as untrusted JSON;
the server validates its schema, hash bindings, and all repeated claims. Protocol
v1 deliberately does not reuse one CBC IV for multiple separately encrypted files
and does not introduce a ZIP or tar container.

The generic validator runs in `service-python` and checks the safe package,
resource limits, actual SHA/size, ordered signatures, dtypes, and finite values.
Expected keys, shapes, element count, and dtype policy are dispatched by the
trusted Definition's `runtimeProfileId`: `YOLO_RUNTIME_V1` builds an
architecture-only `DetectionModel`, while `INTERNIMAGE_RUNTIME_V1` builds the
trusted InternImage-T + UPerNet template and derives `model.state_dict()`.
Neither Runtime reads a base or pretrained checkpoint.

On success, `weights.pt`, `manifest.json`, `descriptor.json`, and
`inspection-report.json` are stored together under the controlled weights asset
root. The existing database columns record lifecycle, SHA, source type, import
mode, file path, and inspection status; no Stage 2A schema migration is required.
Protocol v1 returns before the legacy FedAvg trigger. Legacy workflows and
definition workflows while the flag is disabled continue to use the existing
checkpoint upload path.

## 10. Stage 3 Weights-only FedAvg

Definition workflows use the Stage 3 path only when both
`WEIGHTS_PROTOCOL_V1_ENABLED=true` and `WEIGHTS_FEDAVG_V1_ENABLED=true`. Both
flags default to `false`. The internal endpoint is:

```text
POST /internal/federated/aggregate-weights-v1
```

Only formal, ready, successfully inspected `WORKFLOW_WEIGHTS_V1` assets may be
submitted. Java derives the trusted model identity from
`workflow.model_definition_id`, rechecks each file SHA256 and sidecar identity,
and supplies server-controlled file paths. `service-python` then reruns Safe
Inspector and loads every input exclusively with `weights_only=True`.

The frozen type-safe aggregation policy is:

| Input dtype | Accumulator | Output | Rule |
| --- | --- | --- | --- |
| `float16` | `float32` | `float32` | weighted mean |
| `float32` | `float64` | `float32` | weighted mean |
| `int64` | none | `int64` | all clients must be identical, then copy |

Stage 3 uses equal server-generated weights (`1/N`). Client-declared
`aggregationContribution` never controls the result. Ordered keys, shapes, and
input dtypes must match exactly. The output remains:

```python
{
    "state_dict": OrderedDict[str, torch.Tensor]
}
```

It is stored as `global_weights.pt` and re-inspected before Java registers a
formal `FEDERATED_WEIGHTS_V1` / `WEIGHTS_PROTOCOL_V1` asset. The same directory
contains `aggregation-manifest.json`, `descriptor.json`,
`inspection-report.json`, and `aggregation-report.json`. Stage 3 does not build a
YOLO model, create a full checkpoint, or trigger Validation.

## 11. Stage 4 Weights-only Validation

Validation of a Definition workflow is enabled only when
`WEIGHTS_VALIDATION_V1_ENABLED=true`; the default is `false`. A completed formal
`FEDERATED_WEIGHTS_V1` asset is routed through the existing asynchronous job and
callback lifecycle using `validationMode=WEIGHTS_PROTOCOL_V1`.

Before job creation, Java reloads the trusted ModelDefinition from
`workflow.model_definition_id`, rechecks Definition availability, validates the
global asset state and controlled path, recomputes the weights SHA256, and
cross-checks `aggregation-manifest.json`, `descriptor.json`,
`inspection-report.json`, and `aggregation-report.json`. Database numeric IDs
are never compared directly with protocol Definition IDs.

`service-python` reruns the generic Safe Inspector and dispatches by
`runtimeProfileId`. It does not import a model-family library on the v1 path.
`YOLO_RUNTIME_V1` handles the model-specific operation:

```text
trusted ModelDefinition architecture
    + {"state_dict": OrderedDict[str, Tensor]}
    -> DetectionModel(cfg=architecture, ch=inputChannels, nc=classCount)
    -> torch.load(..., map_location="cpu", weights_only=True)
    -> model.load_state_dict(state_dict, strict=True)
    -> CUDA inference/validation
```

The runtime must return evidence containing
`weightsOnly=true`, `checkpointUsed=false`, `baseCheckpointUsed=false`,
`reconstructionMode=DEFINITION_PLUS_STATE_DICT`, `strictLoad=true`, and zero
missing/unexpected keys. The normalized result remains compatible with the
existing Java callback and CLIENT/SERVER result pages. `result.json` also stores
`validationEvidence`; annotated samples are rendered by the model runtime and
materialized by `service-python` under the configured shared sample directory.

Stage 4 does not automatically start Validation after FedAvg. Legacy
`FEDERATED_OUTPUT` continues through the legacy checkpoint validator, while
`FEDERATED_WEIGHTS_V1` cannot fall back to that loader. No base or pretrained
checkpoint is part of the v1 contract.
