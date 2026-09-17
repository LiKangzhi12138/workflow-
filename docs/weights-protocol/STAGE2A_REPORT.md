# Stage 2A Report

## 1. Stage 2A Code Readiness

PASS. The code/configuration is ready for the manual Docker upload test. Docker
was not started, rebuilt, restarted, or entered during this implementation.

## 2. Current Legacy Upload Chain

```text
Client selects an existing client ModelAsset
  -> POST /api/workflow-uploads/init
  -> Java issues upload token plus AES-256 key/IV
  -> browser computes plaintext SHA256 and encrypts the selected checkpoint
  -> POST /api/workflow-uploads/{uploadId}/file
  -> workflow_model_upload stores encrypted path and upload state
  -> SERVER decrypt action reads model-uploads/{workflowId}/encrypted
  -> Java decrypts and verifies SHA256
  -> decrypted checkpoint is written below the server model root
  -> WORKFLOW_DECRYPT ModelAsset is registered
  -> existing readiness logic may trigger legacy FedAvg
```

The AES implementation, key/IV lifecycle, old ModelAsset registration, legacy
FedAvg loader, and validation loader were not changed by Stage 2A.

## 3. New V1 Upload Chain

```text
Definition workflow + WEIGHTS_PROTOCOL_V1_ENABLED=true
  -> GET /api/workflow-uploads/contract/{workflowId} returns WEIGHTS_V1
  -> client selects weights.pt, manifest.json, descriptor.json
  -> POST /api/workflow-uploads/init returns a V1-tagged token and existing AES context
  -> browser AES-encrypts only weights.pt and submits metadata in the same authenticated request
  -> Java stores encrypted weights and bounded JSON metadata in controlled paths
  -> SERVER decrypt action verifies plaintext SHA256
  -> Java writes only the decrypted bytes to weights-v1-quarantine/{workflowId}/{uploadId}/weights.pt
  -> service-python Safe Inspector validates protocol and actual tensor content
  -> service-python compares client claims with the trusted workflow Definition
  -> yolo-runtime builds an architecture-only model and compares keys/shapes/dtypes
  -> Java moves the accepted file to weights-assets/{workflowId}/{uploadId}/weights.pt
  -> Java writes manifest, descriptor, and inspection-report sidecars
  -> Java registers a formal WORKFLOW_WEIGHTS_V1 ModelAsset
  -> workflow_model_upload becomes COMPLETED / INSPECTED
  -> V1 returns without invoking legacy FedAvg
```

## 4. V1 Upload Content

V1 accepts only the Protocol v1 `weights.pt` wrapper plus `manifest.json` and
`descriptor.json`. It does not accept a full checkpoint or silently retry the
legacy path.

## 5. Full Checkpoint Dependency

V1 does not depend on a server-side full checkpoint. The existing
`clientModelAssetId`/upload `modelAssetId` remains a source ownership and audit
reference for compatibility with the current Workflow creation contract. A
`CLIENT_PATH_REGISTRY` record is sufficient; its file is never opened by the V1
validator. Therefore Stage 2A does not require a prior full-checkpoint upload.

The `workflow_model_upload.model_asset_id` database column is nullable, while
the current init API intentionally still requires it as this migration-stage
audit anchor. It cannot select a Definition or affect compatibility.

## 6. Trusted Identity Boundary

The only trusted model identity is:

```text
workflow.id -> workflow.model_definition_id -> model_definition row
```

Manifest/descriptor identity, filenames, browser fields, and `modelAssetId` are
untrusted declarations or audit references. The server loads the enabled
Definition by the workflow-bound numeric ID and passes that trusted JSON to the
validator.

## 7. Manifest Schema

Stage 2A reuses the Stage 1A `WeightsPackageManifest` schema:

- `schemaVersion`, `weightsFormatVersion`, `packageId`, `artifactType`
- `descriptorSha256`
- `modelDefinition.definitionId`, `modelDefinition.definitionSha256`
- `architectureSignature`, `parameterSemantics`
- `weights.format`, `wrapperKey`, `fileName`, `sizeBytes`, `sha256`
- `state.keyCount`, `tensorCount`, `elementCount`
- `state.stateDictKeyHash`, `shapeSignature`, `dtypeSignature`, `dtypeCounts`
- `aggregationContribution`, `createdBy`

`modelDefinition.definitionId` is the protocol Definition code. It is compared
with the trusted Definition; it is not the database lookup key supplied by the
client.

## 8. Manifest Trust

The manifest is not trusted. Actual file size, SHA256, state structure, counts,
ordered key/shape/dtype signatures, dtypes, and finite values are recomputed.
Repeated Definition, architecture, classes, framework, version, variant, task,
and parameter-semantics claims are compared with the trusted Definition.

## 9. Safe Inspector

The formal V1 upload calls
`POST /internal/model-packages/validate-upload`, which invokes the existing
Stage 1A Safe Inspector before model-specific compatibility dispatch.

## 10. Safe Loader

Every V1 server/tool load uses:

```python
torch.load(path, map_location="cpu", weights_only=True)
```

There is no `weights_only=False` fallback in the V1 path. The old trusted legacy
FedAvg code remains separate and unchanged.

## 11. Generic Compatibility

`service-python/app/services/model_package_validation_service.py` performs safe
inspection, trusted Definition integrity/reference comparison, architecture
comparison, descriptor metadata comparison, and extraction of ordered tensor
metadata. `weights_package_inspector.py` enforces size/resource limits, exact
top-level structure, Tensor-only values, SHA/size/signatures, dtype allowlist,
and NaN/Inf rejection.

## 12. YOLO-Specific Compatibility

`service-yolo-runtime/app/weights_compatibility.py` builds `DetectionModel` from
the trusted architecture dictionary, without a checkpoint, and compares exact
ordered keys and shapes. Floating parameters permit the Protocol v1 client
snapshot dtypes `float16` or `float32`; non-floating state must match exactly.

`service-python` does not import or add Ultralytics. YOLO-specific construction
remains isolated in `yolo-runtime`.

## 13. Base Model Requirements

- `yolov8n.pt`: not required.
- `best.pt`: not required.
- Any server base/pretrained checkpoint: not required.

## 14. Client Packaging Decision

The Vue/browser client never parses PyTorch. The repository includes
`tools/weights-package/package_weights.py`, a Python CLI that accepts an already
safe weights-only source and a trusted ModelDefinition. It outputs exactly
`weights.pt`, `manifest.json`, and `descriptor.json`.

The tool intentionally does not unsafe-load or extract arbitrary full
checkpoints. A training system that starts from a trusted full checkpoint must
export weights-only within its trusted local training environment first.

## 15. AES And Metadata Transport

The existing AES-256-CBC weights upload is retained unchanged. The two small
JSON documents are carried as bounded multipart fields in the same authenticated
request and remain untrusted. They are not separately encrypted with the same
CBC key/IV, which would introduce unsafe IV reuse. No ZIP/tar container or
decompression surface was added.

## 16. Quarantine And Cleanup

The decrypted V1 file first enters the controlled temporary root:

```text
${workflow.storage.temp-dir}/weights-v1-quarantine/{workflowId}/{uploadId}/weights.pt
```

Only explicitly named files are removed through
`ControlledArtifactDeletionService`; there is no recursive deletion. On a
pre-registration failure, quarantine, temporary metadata, and any unregistered
partial final files are cleanup targets. Existing encrypted input may remain on
failure for diagnosis/retry under the established upload policy. On success,
the encrypted input and temporary metadata are removed best effort.

After ModelAsset registration, a later upload-state failure does not delete the
registered final asset. This avoids a database asset pointing at a file that was
removed during exception cleanup.

## 17. Formal Asset

Accepted files are stored below the configured weights asset root. The formal
ModelAsset uses:

```text
source_type       = WORKFLOW_WEIGHTS_V1
import_mode       = WEIGHTS_PROTOCOL_V1
record_mode       = FORMAL_ASSET
status            = READY
last_check_status = OK
```

The file path points to `weights.pt`; its file size and upload SHA remain in the
existing model/upload records. `manifest.json`, `descriptor.json`, and
`inspection-report.json` are durable sidecars in the same asset directory.
`workflow_model_upload.server_model_asset_id` links the accepted asset and the
record status becomes `COMPLETED`, with aggregation status `INSPECTED`.

## 18. Database Decision

`DB CHANGE REQUIRED = NO`.

The current schema already supplies upload lifecycle/status/error/SHA/path
columns and ModelAsset source/import/check/path fields. Full protocol evidence
is durably stored as sidecars. The new source/import strings fit the existing
VARCHAR columns. No migration proposal was generated and no SQL was executed.

## 19. Feature Matrix

| Workflow | V1 flag | Upload behavior |
| --- | --- | --- |
| `model_definition_id IS NULL` | either | Legacy checkpoint upload |
| `model_definition_id IS NOT NULL` | false | Legacy compatibility upload |
| `model_definition_id IS NOT NULL` | true | Strict V1 weights-only upload |

The default remains `WEIGHTS_PROTOCOL_V1_ENABLED=false`. V1 errors never fall
back to legacy upload.

## 20. Golden Result

The read-only lab artifact and generated package copy both retained SHA256:

```text
aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
```

Safe Inspector result:

```text
passed              true
tensorCount         355
elementCount        3,021,500
float16 tensors     298
int64 tensors       57
NaN/+Inf/-Inf       0/0/0
```

Stage 1B.5 had already certified the same Definition/weights in the real Docker
Runtime with strict 355/355 load and GPU inference. Stage 2A additionally tests
the generic validator and expected-state dispatch contract. The complete HTTP
upload-to-runtime chain remains the manual acceptance step because Docker use
was prohibited for this implementation.

## 21. Negative Test Coverage

Automated tests pass for: extra top-level key, non-OrderedDict/empty/non-Tensor
state, SHA mismatch, size mismatch, key-hash mismatch, shape-signature mismatch,
dtype-signature mismatch, NaN, positive/negative infinity, unsupported dtype,
tensor/element/file/single-tensor/dimension/key-length resource limits, path
traversal/outside-root/symlink, descriptor hash/reference/schema mismatch, safe
loader without fallback, manifest Definition mismatch, architecture mismatch,
runtime key mismatch, missing key, extra key, wrong key order, wrong shape,
wrong dtype, and an actual unsafe pickle global rejected by `weights_only=True`.

## 22. Test Results

- `service-python`: 59 passed, 1 skipped; the skip is the platform-dependent
  symlink case. One unrelated dependency deprecation warning was emitted.
- `service-yolo-runtime`: 8 passed.
- Java with explicit JDK 21 in an isolated workspace build: 28 tests across 6
  targeted classes, 0 failures/errors/skips. Mockito emitted only its future
  dynamic-agent warning.
- Frontend targeted tests: 24 passed.
- `vue-tsc` and Vite production build: PASS. Vite reported only the existing
  large-chunk advisory.
- Python compile checks: PASS for service-python, yolo-runtime, and packaging CLI.
- Compose static parse: `docker compose ... config --quiet` PASS.

## 23. Regression Boundaries

- Upload/AES algorithm: legacy behavior preserved.
- FedAvg: not modified; V1 explicitly returns before its trigger.
- Validation: not modified.
- Global model: not modified.
- InternImage: not implemented.
- Stage 1C minimal model selection: unchanged.
- CLIENT/SERVER workflow management and progress reading: unchanged by the V1
  processing branch; legacy/Definition metadata tests and frontend build pass.

## 24. Stage 2A Files

### Java

- `service-java/src/main/java/com/workflow/config/WeightsProtocolProperties.java`
- `service-java/src/main/java/com/workflow/config/WorkflowStorageProperties.java`
- `service-java/src/main/java/com/workflow/controller/WorkflowModelUploadController.java`
- `service-java/src/main/java/com/workflow/dto/python/PythonWeightsValidationResponse.java`
- `service-java/src/main/java/com/workflow/dto/workflow/ModelUploadInitResponse.java`
- `service-java/src/main/java/com/workflow/dto/workflow/WorkflowUploadContractVO.java`
- `service-java/src/main/java/com/workflow/service/ModelAssetService.java`
- `service-java/src/main/java/com/workflow/service/WorkflowModelUploadService.java`
- `service-java/src/main/java/com/workflow/service/impl/ModelAssetServiceImpl.java`
- `service-java/src/main/java/com/workflow/service/impl/WeightsProtocolUploadService.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowAcceptAutoManageAsyncService.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowModelUploadServiceImpl.java`
- `service-java/src/main/java/com/workflow/service/impl/WorkflowModelUploadStateService.java`
- `service-java/src/main/java/com/workflow/service/python/PythonWeightsProtocolClient.java`
- `service-java/src/main/java/com/workflow/support/AssetSourceCatalog.java`
- `service-java/src/main/resources/application.yml`
- `service-java/src/test/java/com/workflow/service/impl/WeightsProtocolUploadServiceTest.java`

### Python And Runtime

- `service-python/app/api/model_packages.py`
- `service-python/app/schemas/model_protocol.py`
- `service-python/app/services/model_package_validation_service.py`
- `service-python/app/services/yolo_runtime_client.py`
- `service-python/tests/test_model_package_validation.py`
- `service-yolo-runtime/app/main.py`
- `service-yolo-runtime/app/schemas.py`
- `service-yolo-runtime/app/weights_compatibility.py`
- `service-yolo-runtime/tests/test_runtime.py`

### Frontend And Tooling

- `frontend-web/package.json`
- `frontend-web/src/api/workflow.ts`
- `frontend-web/src/utils/weightsPackageUpload.ts`
- `frontend-web/src/views/client/ClientWorkflowManageView.vue`
- `frontend-web/src/views/client/ClientWorkflowProgressView.vue`
- `frontend-web/tests/weightsPackageUpload.test.ts`
- `tools/weights-package/package_weights.py`
- `.env.example`

### Documentation

- `docs/weights-protocol/PROTOCOL_V1.md`
- `docs/weights-protocol/STAGE2A_REPORT.md`
- `docs/weights-protocol/STAGE2A_MANUAL_TEST.md`
- `docs/weights-protocol/WEIGHTS_PACKAGING_GUIDE.md`

## 25. Next Stage Recommendation

After manual Stage 2A acceptance, the next stage can be **Stage 3: weights-only
FedAvg**. Stage 2A already performs durable trusted-asset registration; the next
missing business capability is aggregation that consumes those assets without
entering the legacy full-checkpoint loader. No Stage 3 work is included here.
