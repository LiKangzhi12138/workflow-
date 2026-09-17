# Stage 1A Implementation Report

## 1. Result

**Stage 1A: PASS.**

The project now has additive Protocol v1 schemas, availability semantics, a
fail-closed weights-only Inspector, an internal endpoint behind a default-off
feature flag, targeted tests, and protocol documentation. Existing upload,
FedAvg, validation, Java, frontend, database, and Docker behavior was not changed.

## 2. Implemented Components

| Component | Result | Location |
| --- | --- | --- |
| ModelDefinition | Complete schema | `service-python/app/schemas/model_protocol.py` |
| RuntimeProfile | Complete schema | `service-python/app/schemas/model_protocol.py` |
| ModelAvailabilityResult | Complete schema | `service-python/app/schemas/model_protocol.py` |
| Availability evaluator skeleton | Complete, no fake probes | `service-python/app/services/model_availability_service.py` |
| ModelDescriptor | Complete schema | `service-python/app/schemas/model_protocol.py` |
| WeightsPackageManifest | Complete schema | `service-python/app/schemas/model_protocol.py` |
| InspectionReport and stable codes | Complete schema | `service-python/app/schemas/model_protocol.py` |
| Safe Inspector | Complete | `service-python/app/services/weights_package_inspector.py` |
| Internal inspect endpoint | Complete, default disabled | `service-python/app/api/model_packages.py` |
| Feature flag and limits | Complete | `service-python/app/core/config.py` |
| Targeted tests | Complete | `service-python/tests/test_weights_package_inspector.py` |

## 3. Base Checkpoint Decisions

### YOLO

The new protocol does **not** require an untrained `yolov8n.pt` checkpoint. The
future reconstruction dependency is:

```text
trusted YOLO ModelDefinition architecture
+ trusted YOLO Runtime/Adapter
+ weights-only
```

### InternImage

The new protocol does **not** require a pretrained InternImage checkpoint. The
future reconstruction dependency is:

```text
trusted InternImage ModelDefinition/config
+ trusted InternImage runtime/source/custom modules
+ weights-only
```

No InternImage Git commit or machine-specific checkpoint path was hardcoded.

## 4. Availability Semantics

`AVAILABLE` requires all of the following:

1. the definition is enabled and validates;
2. the corresponding adapter is registered;
3. the required runtime is ready;
4. the definition self-check passes in that runtime.

Stage 1A does not yet have a ModelDefinition Registry, Adapter Registry, Runtime
Registry, runtime health probe, CUDA/compiled-op probe, or runtime definition
self-check. Unknown values remain `null`, produce `NOT_EVALUATED`, and never
produce a false-positive `AVAILABLE` result.

## 5. Inspector Security Contract

- Safe loader: `torch.load(path, map_location="cpu", weights_only=True)`.
- `weights_only=False` fallback: absent.
- Accepted top-level: exactly `{"state_dict": OrderedDict[str, Tensor]}`.
- Allowed dtypes: `float16`, `float32`, `int64`.
- Path controls: configured root containment, no root target, traversal rejection,
  regular-file check, and symlink rejection.
- Integrity: actual size and SHA256 are recomputed.
- Compatibility: key count, tensor count, element count, ordered key hash, ordered
  shape signature, ordered dtype signature, and dtype counts are recomputed.
- Numeric safety: all floating tensors are checked for NaN, `+Inf`, and `-Inf`.
- Resource controls: file bytes, tensor count, total elements, single-tensor
  elements, dimensions, and key length.
- Model construction: none; the Inspector is model-independent and CPU-only.

## 6. Test Evidence

Command:

```text
python -m pytest -q -p no:cacheprovider tests/test_weights_package_inspector.py
```

Result:

```text
32 passed, 1 skipped
```

The skipped test is the symbolic-link fixture because this Windows environment
does not grant symbolic-link creation permission. The production rejection logic
is present. Covered behavior includes:

- synthetic YOLO-like `float16 + int64`: PASS;
- synthetic InternImage-like `float32`: PASS;
- strict top-level and Tensor-only validation;
- actual file SHA256 and size mismatches;
- key, shape, and dtype signature mismatches;
- NaN, positive infinity, and negative infinity;
- unsupported dtype;
- every configured resource-limit category;
- path outside root and explicit parent traversal;
- missing file;
- descriptor/definition mismatch and unsupported schema version;
- loader failure with an assertion that the single call uses `weights_only=True`;
- availability requires real definition/adapter/runtime checks.

The broader `service-python/tests` regression result is `35 passed, 1 skipped`;
the additional three passing cases are the existing logger configuration tests.

### YOLO Golden Artifact

The read-only artifact
`D:/workspace/federated-weights-lab/yolo/artifacts/client_a/weights_only.pt`
passed the new Inspector. Its ordered key signature matched the lab value
`f21d8c0bab8d5e06632ff1f14d51e650345a27b7415e2df7cefee28de36549b9`.
The test compared the file SHA256 before and after inspection and confirmed it was
unchanged.

### InternImage Golden Artifact

The 225 MiB real InternImage artifact was not loaded during this Stage 1A project
test run to avoid unnecessary memory and I/O cost. Its measured lab dimensions
(728 tensors, 58,963,958 elements, about 225 MiB, float32) fit all configured
Inspector limits. The same protocol path was covered with a synthetic
InternImage-like OrderedDict package.

## 7. Existing Business Compatibility

The new endpoint is the only integration point and is disabled by default through
`WEIGHTS_PROTOCOL_V1_ENABLED=false`. Java does not call it. Existing model upload,
AES handling, full-checkpoint FedAvg, global-model output, YOLO validation,
standalone validation, workflow validation, and frontend behavior were not
modified.

No Java file, frontend file, SQL file, database schema, Docker file, or Python
dependency declaration was changed. No Docker command was run. No experiment
artifact was modified.

## 8. Future Workflow Contract

Future workflow creation must bind `modelDefinitionId`. `modelFamily`, `version`,
`variant`, `taskType`, classes, and framework must be derived server-side from the
trusted definition. The current free-form YOLO fields remain untouched only for
backward compatibility during Stage 1A.

## 9. Stage 1B Recommendation

Stage 1B should be limited to:

1. a backward-compatible `model_definition` persistence model and migration plan;
2. runtime-profile/runtime binding persistence or trusted configuration;
3. nullable `workflow.model_definition_id` for historical YOLO compatibility;
4. registration and self-check of the real YOLO ModelDefinition;
5. an available-definitions query that returns only genuinely available models.

It should not yet migrate the existing upload/FedAvg/validation pipeline to
weights-only. That behavioral switch should occur only after registry,
compatibility, and runtime checks are operational.
