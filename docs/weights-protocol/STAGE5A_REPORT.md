# Stage 5A InternImage Runtime / ModelDefinition Report

## Status

```text
Stage 5A Code/Config Readiness: PASS
InternImage Docker Runtime Certification: PASS (user-run Docker acceptance)
InternImage Runtime direct Definition check: PASS
Unified service-python / SERVER AVAILABLE acceptance: PENDING MANUAL RE-TEST
```

This stage implements only the InternImage runtime, trusted ModelDefinition,
architecture-only self-check, and existing availability pipeline integration. It
does not implement InternImage upload, FedAvg, global weights, validation, or
result rendering.

## Experiment Audit

The implementation was derived from the read-only handoff under
`D:\workspace\federated-weights-lab\internimage`, especially:

- `handoff/docs/environment.txt`
- `handoff/docs/model_descriptor.json`
- `handoff/docs/config_dependencies.txt`
- `handoff/docs/mmcv_source_audit.txt`
- `handoff/configs/wheat/upernet_internimage_t_wheat_tv95_480.py`
- `handoff/configs/ade20k/upernet_internimage_t_512_160k_ade20k.py`
- `handoff/configs/_base_/models/upernet_r50.py`
- `handoff/code/mmseg_custom/models/backbones/intern_image.py`
- `handoff/code/ops_dcnv3`
- `outputs/phase1/PHASE1_REPORT.md`
- `outputs/phase2/PHASE2_REPORT.md`
- `outputs/phase3/PHASE3_REPORT.md`

The experiment directory remains read-only and is not a runtime dependency.

| Item | Verified experiment fact | Formal runtime decision |
| --- | --- | --- |
| Model | InternImage-T + UPerNet | Same complete encoder/decoder architecture |
| Task | Semantic segmentation | `SEMANTIC_SEGMENTATION` |
| Classes | `background`, `healthy_wheat`, `lodged_wheat` | Preserved in the trusted Definition |
| Ignore index | `255` | Preserved |
| Input | `480 x 480`, BCHW | Preserved |
| Normalization | mean `123.675,116.28,103.53`; std `58.395,57.12,57.375`; RGB | Preserved |
| Python | `3.10.20` | Docker contract is Python `3.11.x`; actual version is read by health |
| PyTorch | `2.7.1+cu128` | `2.7.1`, CUDA 12.8 base image |
| torchvision | `0.22.1+cu128` | `0.22.1` |
| CUDA | `12.8` | `12.8`, GPU required |
| MMCV | `1.6.2` with CUDA ops | Source-built `mmcv-full==1.6.2` with the verified compatibility patches |
| MMEngine | Not installed | Not installed and not required by MMCV 1.x / MMSeg 0.x; reported as a warning |
| MMSegmentation | `0.27.0` | `0.27.0` |
| MMDetection | `2.28.1` present in the lab | Not installed; the trusted segmentation builder does not import it |
| timm | `0.6.11` | `0.6.11` |
| InternImage source | `OpenGVLab/InternImage`, master commit `31c962dc6c1ceb23e580772f7daaa6944694fbe6` | The required backbone source is vendored and pinned to that handoff |
| DCNv3 | Compiled CUDA op, package version `1.0` | Source-built against the runtime Torch/CUDA pair |
| State contract | 728 tensors, 58,963,958 elements, float32 | Architecture-only self-check asserts the same contract |

The Python major/minor difference is deliberate rather than hidden. The lab
proved Python 3.10.20. The selected official PyTorch 2.7.1 CUDA 12.8 image is
contracted as Python 3.11.x, matching the already observed image family in this
project. A real InternImage container must report its exact patch version and
pass DCNv3 plus architecture construction before it can become AVAILABLE.

## Trusted ModelDefinition

```text
code:                  INTERNIMAGE_T_UPERNET_WHEAT_V1
displayName:           InternImage-T 小麦倒伏分割
modelFamily:           INTERNIMAGE
version:               master@31c962dc
variant:               InternImage-T + UPerNet
taskType:              SEMANTIC_SEGMENTATION
framework:             MMSegmentation
frameworkVersion:      0.27.0
classCount:            3
classOrder:            [background, healthy_wheat, lodged_wheat]
ignoreIndex:           255
runtimeProfileId:      INTERNIMAGE_RUNTIME_V1
definitionType:        MMSEG_TRUSTED_CONFIG_TEMPLATE
architectureSignature: be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f
definitionSha256:       f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700
status:                ENABLED
```

The canonical algorithm is the existing platform rule: UTF-8 JSON with sorted
object keys and separators `,` and `:`, with no insignificant whitespace. The
architecture signature hashes only the canonical trusted architecture object.
The Definition SHA hashes the complete canonical Definition after setting
`definitionSha256` to an empty string. A local independent recomputation matched
both values.

The Definition does not contain a host path or executable Python config. It
contains `templateId=INTERNIMAGE_T_UPERNET_WHEAT_V1` and the canonical resolved
model config. The runtime accepts only an exact match to its trusted template
registry. Arbitrary paths, imports, code, `eval`, and `exec` are not accepted.

## Architecture Construction

`service-internimage-runtime/app/model_template.py` registers the pinned
InternImage backbone and calls the MMSegmentation segmentor builder with the
trusted resolved config:

```text
EncoderDecoder
  InternImage-T backbone
  UPerHead decode head
  FCNHead auxiliary head
  3 classes
```

`pretrained`, backbone `init_cfg`, `load_from`, and `resume_from` are absent or
fixed to null. The Definition validator rejects any non-empty checkpoint or
pretrained construction field. The self-check builds the complete model and
checks 728 state entries, 58,963,958 elements, and both three-class heads. It
does not load a checkpoint or weights file.

The architecture signature is based on the canonical resolved architecture,
not a config path, file timestamp, or Python source path.

## Runtime Service

```text
service:          internimage-runtime
directory:        service-internimage-runtime
internal port:    8020
profile:          model-runtime
runtimeProfileId: INTERNIMAGE_RUNTIME_V1
health:           GET /internal/runtime/health
self-check:       POST /internal/model-definitions/check
host exposure:    none
GPU Compose:      gpus: all
storage mount:    shared storage, read-only
```

The Docker build uses two stages from the unchanged official PyTorch image
family:

- builder: `pytorch/pytorch:2.7.1-cuda12.8-cudnn9-devel`
- runtime: `pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime`

`mmcv-full==1.6.2` and DCNv3 are compiled in the builder against the same
Torch/CUDA pair. The build applies the two compatibility changes documented by
the experiment: C++17 and the CUDA-device stream conversion. The source audit
checks the exact upstream and patched flag counts, all CPU/NVCC extension flag
sites, the complete source tree for effective C++14 flags, and the Scatter
replacement before native compilation. Runtime installation uses the generated wheels
with `--no-deps`; pre/post assertions protect Torch 2.7.1, torchvision 0.22.1,
and CUDA 12.8 from replacement. All package versions are pinned. The pip index
defaults to official PyPI and can be overridden only for this runtime.

Native compilation uses build-only `MAX_JOBS`, configurable through
`INTERNIMAGE_RUNTIME_MAX_JOBS` and conservatively defaulted to 4. It does not
affect service-python or the YOLO Runtime.

The pinned `timm==0.6.11` sdist is also built into a local wheel after a
deterministic Python 3.11 compatibility patch. Runtime health reports both
`timmVersion=0.6.11` and `python311CompatibilityPatch=true`; the latter also
checks that separate `MaxxVitCfg` instances receive separate convolution and
transformer config objects.

The final process runs as uid `10002`, and no checkpoint, pretrained model, or
lab artifact is copied into the image.

## Health And Self-check

Health requires all of the following before `runtimeReady=true`:

- Python 3.11.x
- PyTorch 2.7.1 and torchvision 0.22.1
- CUDA build 12.8, `torch.cuda.is_available()`, and a visible GPU
- MMCV 1.6.2, MMSegmentation 0.27.0, and timm 0.6.11
- pinned InternImage source import
- compiled DCNv3 import
- a finite CUDA DCNv3 forward on a small `1 x 8 x 8 x 16` tensor

The minimal forward is intentionally limited to the required custom CUDA op to
control memory use. The complete InternImage-T + UPerNet model is constructed
on CPU during Definition self-check. Stage 5A does not run segmentation
validation or a full-model synthetic benchmark.

MMEngine availability and version are returned in the health schema. It is
expected to be absent for this verified MMCV 1.x/MMSeg 0.x stack and therefore
does not make readiness fail. Installing a guessed MMEngine version would alter
the proven dependency family.

Every self-check result explicitly reports:

```text
checkpointUsed=false
baseCheckpointUsed=false
pretrainedUsed=false
constructionMode=TRUSTED_CONFIG_TEMPLATE
```

## Generic Dispatch And Availability

The existing service-python availability flow is retained:

```text
DB ENABLED ModelDefinition
  -> canonical integrity check
  -> RuntimeProfile registry
  -> profile-based runtime HTTP client
  -> runtime health
  -> runtime Definition self-check
  -> normalized availability
  -> Java GET /api/model-definitions/available
```

The profile registry maps:

```text
YOLO_RUNTIME_V1        -> yolo-runtime
INTERNIMAGE_RUNTIME_V1 -> internimage-runtime
```

service-python imports neither MMCV nor MMSegmentation nor InternImage nor
DCNv3. YOLO-specific code and dependencies were not changed. A failed or stopped
InternImage runtime removes only the InternImage Definition from AVAILABLE;
YOLO uses an independent client, profile, health result, and self-check.

`INTERNIMAGE_RUNTIME_V1_ENABLED` defaults to `false`. When disabled, the profile
is not registered. When enabled, a runtime or self-check failure remains
fail-closed.

## Workflow Boundary

The existing client UI consumes the AVAILABLE API dynamically. Stage 5A must
not expose an unfinished Stage 5B upload path, so the Java response now includes
`workflowSelectable`. Its default allowlist remains:

```text
MODEL_DEFINITION_WORKFLOW_SELECTABLE_RUNTIME_PROFILES=YOLO_RUNTIME_V1
```

The SERVER AVAILABLE API can therefore expose the verified InternImage runtime,
while workflow creation rejects it and the frontend filters it from creation
choices until Stage 5B explicitly expands the allowlist. No hard-coded
InternImage variants were added to the UI.

## Safe-loading Audit

Searches over the new runtime found:

- no `torch.load(..., weights_only=False)` in first-party Stage 5A code
- no pickle/joblib loader
- no `eval()` or `exec()` code execution
- no non-null pretrained/load/resume value in the trusted template
- one optional certification load using `torch.load(..., weights_only=True)`

The vendored upstream backbone retains an unused third-party `init_weights`
checkpoint branch. The trusted builder always supplies `init_cfg=None`, and the
Definition validator rejects a non-null value. AVAILABLE never reads a weights
or checkpoint file.

`service-internimage-runtime/certify_runtime.py` is a manual deployment
certification utility only. It checks the strict 728-tensor contract with
`weights_only=True` and `strict=True`; it is not called by health or AVAILABLE.
The lab previously proved the real weights strict load, but this Codex run did
not execute the Docker certification or read the large artifact into the formal
runtime.

## First Docker Build Failure And Correction

The first real user-run Docker build reached the fixed Torch 2.7.1/CUDA 12.8
builder but failed while compiling `mmcv-full==1.6.2`: the emitted CPU command
still contained `-std=c++14`, which PyTorch 2.7 rejects because ATen requires
C++17. This disproved the report's earlier statement that the original static
patch check guaranteed C++17 compilation.

Root cause: upstream MMCV 1.6.2 `setup.py` contains one unrelated MPS
`-std=c++17` flag in addition to five effective `-std=c++14` extension flags.
The old helper returned early whenever the replacement token appeared anywhere
in the file, so that unrelated MPS token caused all five CPU/CUDA C++14 sites to
remain untouched. Its validation checked token presence, not the effective
extension configuration or elimination of C++14.

The corrected preparation step now:

- accepts only the audited upstream state: five C++14 and one pre-existing C++17 flag;
- replaces all five C++14 flags and requires the exact patched state of six C++17 flags;
- verifies the Parrots, PyTorch CPU `cxx`, and PyTorch CUDA `nvcc` flag paths explicitly;
- recursively rejects `-std=c++14`, `-std=gnu++14`, or `/std:c++14` before compilation;
- preserves and verifies the PyTorch 2.7 `_get_stream(torch.device('cuda', device))` patch;
- supports only the exact audited original or exact idempotent patched state;
- runs in a separate Docker layer before the expensive MMCV/DCNv3 wheel builds.

The actual PyPI `mmcv-full-1.6.2` sdist was patched locally first. A subsequent
user-run Docker build confirmed that the C++17 patch is effective and produced
both `mmcv_full-1.6.2-cp311-cp311-linux_x86_64.whl` and
`dcnv3-1.0-cp311-cp311-linux_x86_64.whl`. Runtime certification and Definition
AVAILABLE acceptance are not yet claimed.

## Second Docker Build Failure And Correction

That same real build then failed in the final image because the Dockerfile used
the case-sensitive glob `DCNv3-*.whl`, while Python wheel normalization emitted
the lowercase filename `dcnv3-1.0-cp311-cp311-linux_x86_64.whl`. The runtime
install now uses `/tmp/dcnv3-wheels/dcnv3-*.whl` and fails before pip unless the
glob resolves to exactly one existing file. A static regression test derives the
normalized wheel stem from `ops_dcnv3/setup.py` and verifies that the Dockerfile
uses the matching lowercase glob. No package version or native build behavior
changed.

## Third Docker Build Failure And Correction

The next user-run build confirmed all of these real gates:

- `mmcv-full==1.6.2` wheel build and runtime install passed;
- `DCNv3==1.0` wheel build and normalized-wheel runtime install passed;
- `mmsegmentation==0.27.0` and `timm==0.6.11` installed at their pinned versions.

The final version assertion then failed at `import timm` under Python 3.11.
Official timm 0.6.11 defines `MaxxVitCfg.conv_cfg` and
`MaxxVitCfg.transformer_cfg` with instantiated dataclass defaults. Python 3.11
rejects those mutable defaults. A full official-sdist AST audit found exactly
those two incompatible dataclass fields. Other `MaxxVit*Cfg()` occurrences are
ordinary function argument defaults, while the other mutable timm dataclass
fields already use `field(default_factory=...)`.

The runtime keeps the experiment-pinned `timm==0.6.11` and Python 3.11. The new
`prepare_timm_source.py` accepts only the exact upstream or exact fully patched
state, adds the `field` import, converts both fields to `default_factory`, parses
the result, and audits every timm `@dataclass` for remaining call/list/dict/set
defaults. Token drift, partial patches, or any additional incompatible field
fail before the timm wheel is built. The Dockerfile installs only that patched
wheel and then performs a real `import timm`, `import timm.models`, version
assertion, and independent-default-factory check in the Python 3.11 image.

The actual fixed timm 0.6.11 sdist passed the source audit with:

```text
timmVersion=0.6.11
python311CompatibilityPatch=true
mutableDataclassDefaults=0
defaultFactories=2
```

No MMCV/DCNv3 source, wheel logic, dependency version, base image, or YOLO
Runtime was changed for this correction. Real Docker runtime certification is
still pending the next manual build at this historical point; the subsequent
user-run build and runtime checks passed.

## Fourth Acceptance Failure And Correction

The user-run InternImage container subsequently passed its complete health gate
and direct Definition check. The runtime reported CUDA/GPU, MMCV 1.6.2,
MMSegmentation 0.27.0, timm 0.6.11, DCNv3 import/forward, architecture-only
construction, 728 tensors, and 58,963,958 elements. However, the same trusted
Definition sent through service-python was incorrectly converted to
`RUNTIME_CHECK_FAILED` with the compressed message `ValidationError`.

Root cause: `service-internimage-runtime` returned six explicit construction
evidence fields that were absent from service-python's strict
`ModelAvailabilityResult`. Because the shared protocol model uses
`extra="forbid"`, Pydantic correctly rejected the otherwise successful HTTP
response. The reproduced `ValidationError.errors()` was:

| Field | Type | Message | Actual value |
| --- | --- | --- | --- |
| `checkpointUsed` | `extra_forbidden` | Extra inputs are not permitted | `false` |
| `baseCheckpointUsed` | `extra_forbidden` | Extra inputs are not permitted | `false` |
| `pretrainedUsed` | `extra_forbidden` | Extra inputs are not permitted | `false` |
| `constructionMode` | `extra_forbidden` | Extra inputs are not permitted | `TRUSTED_CONFIG_TEMPLATE` |
| `tensorCount` | `extra_forbidden` | Extra inputs are not permitted | `728` |
| `elementCount` | `extra_forbidden` | Extra inputs are not permitted | `58963958` |

The old Stage 5A test used a stub client that directly returned
service-python's own old `ModelAvailabilityResult`; it never exercised the real
JSON response or `InternImageRuntimeClient._request()` parsing boundary. The
replacement regression uses a mock HTTP session with the real Runtime response
shape and runs it through both the client and unified Definition check.

The fix adds a dedicated strict `InternImageModelAvailabilityResult` response
contract. Its no-checkpoint fields and trusted construction mode are required
literal values, while tensor and element counts remain optional for legitimate
Runtime failure responses. Unknown fields are still forbidden; missing required
evidence and invalid types still fail closed. The client now logs only schema
name plus Pydantic field path, error type, and message. It never logs the
response value/body, credentials, or model payload.

No Runtime, dependency, Java, database, or YOLO code changed. Java's existing
AVAILABLE implementation was rechecked: an available InternImage Definition is
returned with `workflowSelectable=false`; that flag does not filter it from the
SERVER AVAILABLE list. Rebuilding service-python and repeating the unified
Docker/API check remains pending.

## Database

```text
DB schema change required: NO
Definition seed required: YES
SQL executed by Codex: NO
```

The model-neutral Stage 1B schema already has all required columns, including
JSON payload, class metadata, ignore index, hashes, and runtime profile. The
manual, idempotent seed is `docs/weights-protocol/STAGE5A_SQL.sql`. It inserts no
workflow row and never backfills historical workflows.

## Automated Verification

| Check | Result |
| --- | --- |
| InternImage runtime/Definition/seed/build-contract unit tests | `24 passed` |
| service-python focused runtime/registry tests | `26 passed` |
| service-python full regression | `87 passed, 1 skipped, 3 subtests passed`; existing local pressure-test thread warnings only |
| YOLO runtime regression | `17 passed, 3 subtests passed` |
| Java Stage 5A AVAILABLE targeted tests, JDK 21 isolated copy | `11 tests, 0 failures, 0 errors, 0 skipped` |
| Java full regression, JDK 21 with isolated temp/storage | `141 tests, 0 failures, 0 errors, 2 skipped` |
| Frontend targeted tests | `27 passed` |
| vue-tsc and Vite production build | PASS; existing large-chunk warning only |
| Definition hash recomputation | PASS |
| Compose `config --quiet` | PASS |
| `git diff --check` | PASS; line-ending notices only |
| Docker build/start/runtime health | PASS in user-run acceptance; NOT EXECUTED by Codex |
| Golden strict-load in the new Docker image | NOT EXECUTED by Codex |

The six MMCV/build-contract regressions cover the audited C++14-to-C++17
conversion, upstream token drift fail-closed behavior, residual effective C++14
rejection, the PyTorch 2.7 Scatter conversion, idempotent repeat execution, and
normalized DCNv3 wheel installation. The seven timm regressions cover both
default factories, exact upstream matching, residual and additional mutable
dataclass defaults, idempotency, Python 3.11+ package/model import, and patched
wheel-only Docker installation. Local runs against the fixed official MMCV and
timm 0.6.11 sdists passed both source audits; they did not compile a wheel or
execute Docker.

An initial Java full-suite invocation used the Windows user temp directory and
produced ACL-related cleanup failures. It was rerun with JDK 21 plus project-local
`java.io.tmpdir` and storage, matching the established project test procedure;
the valid result is the clean 141-test run above.

## Stage 5A Files

Runtime and Definition:

- `service-internimage-runtime/Dockerfile`
- `service-internimage-runtime/requirements.txt`
- `service-internimage-runtime/THIRD_PARTY_NOTICES.md`
- `service-internimage-runtime/certify_runtime.py`
- `service-internimage-runtime/app/__init__.py`
- `service-internimage-runtime/app/main.py`
- `service-internimage-runtime/app/schemas.py`
- `service-internimage-runtime/app/runtime_probe.py`
- `service-internimage-runtime/app/model_template.py`
- `service-internimage-runtime/app/definition_check.py`
- `service-internimage-runtime/build_support/prepare_mmcv_source.py`
- `service-internimage-runtime/build_support/prepare_timm_source.py`
- `service-internimage-runtime/runtime_vendor/__init__.py`
- `service-internimage-runtime/runtime_vendor/intern_image.py`
- `service-internimage-runtime/ops_dcnv3/**`
- `service-internimage-runtime/tests/test_runtime.py`
- `service-internimage-runtime/tests/test_prepare_mmcv_source.py`
- `service-internimage-runtime/tests/test_prepare_timm_source.py`
- `service-python/app/model_definitions/INTERNIMAGE_T_UPERNET_WHEAT_V1.json`

Dispatch/configuration:

- `.env.example`
- `docker-compose.yml`
- `service-python/app/core/config.py`
- `service-python/app/schemas/model_protocol.py`
- `service-python/app/services/internimage_runtime_client.py`
- `service-python/app/services/runtime_client_registry.py`
- `service-python/app/services/runtime_profile_registry.py`
- `service-python/app/services/runtime_capability_probe.py`
- `service-python/app/services/runtime_definition_self_checker.py`
- `service-python/app/services/model_definition_check_service.py`
- `service-python/app/services/model_definition_integrity_service.py`
- `service-python/tests/test_internimage_runtime_registry.py`

Availability and workflow guard:

- `service-java/src/main/java/com/workflow/config/ModelDefinitionRegistryProperties.java`
- `service-java/src/main/java/com/workflow/dto/modeldefinition/AvailableModelDefinitionVO.java`
- `service-java/src/main/java/com/workflow/service/impl/ModelDefinitionServiceImpl.java`
- `service-java/src/main/resources/application.yml`
- `service-java/src/test/java/com/workflow/service/impl/ModelDefinitionServiceImplTest.java`
- `frontend-web/src/api/modelDefinition.ts`
- `frontend-web/src/utils/modelDefinitionSelection.ts`
- `frontend-web/tests/modelDefinitionSelection.test.ts`

Documentation/seed:

- `docs/weights-protocol/STAGE5A_SQL.sql`
- `docs/weights-protocol/STAGE5A_REPORT.md`
- `docs/weights-protocol/STAGE5A_MANUAL_TEST.md`

## Final Answers

1. The real model is InternImage-T + UPerNet for wheat lodging segmentation.
2. Task type is `SEMANTIC_SEGMENTATION`.
3. Class count is 3.
4. Class order is `background`, `healthy_wheat`, `lodged_wheat`.
5. Ignore index is 255.
6. Experiment Python is 3.10.20; Docker target is 3.11.x and must report its actual patch version.
7. Torch is 2.7.1, with the experiment reporting `2.7.1+cu128`.
8. CUDA is 12.8.
9. MMCV is 1.6.2 with CUDA ops and the verified source compatibility changes.
10. MMEngine is not installed and is not required by this verified legacy OpenMMLab stack.
11. MMSegmentation is 0.27.0.
12. InternImage source is OpenGVLab master commit `31c962dc6c1ceb23e580772f7daaa6944694fbe6`.
13. DCNv3 is mandatory.
14. DCNv3 load and minimal CUDA forward are mandatory and passed the user-run Docker acceptance.
15. Runtime service is `internimage-runtime`.
16. Runtime internal port is 8020.
17. RuntimeProfile is `INTERNIMAGE_RUNTIME_V1`.
18. ModelDefinition code is `INTERNIMAGE_T_UPERNET_WHEAT_V1`.
19. Definition SHA is `f27ae8cbd68caea86e945e54de8aacbf5f208f3cd52aab8719ecef631b70c700`.
20. Architecture signature is `be74760c062cc3c6b137c72ca2911710b45ffec36efd8ddf71d6836efaf1750f`.
21. The Definition uniquely selects an exact trusted template and canonical resolved config.
22. Pretrained checkpoint required: NO.
23. Base checkpoint required: NO.
24. AVAILABLE reads a checkpoint: NO.
25. First-party formal V1 code uses `torch.load(weights_only=False)`: NO.
26. service-python installs MMCV/MMSeg: NO.
27. YOLO Runtime was polluted: NO.
28. InternImage Runtime is independent: YES.
29. Health checks Python, Torch, torchvision, CUDA/GPU, MMCV, MMSeg, timm, InternImage source, DCNv3 import, and DCNv3 CUDA forward.
30. Self-check validates Definition integrity and builds the complete architecture without weights.
31. Minimal forward: YES, for DCNv3 on CUDA; full segmentation forward is intentionally not part of health.
32. Golden strict load: lab PASS; formal Docker certification script ready; not executed by Codex.
33. ModelDefinition AVAILABLE: direct Runtime check PASS; unified service-python/SERVER AVAILABLE re-test is pending after this schema fix.
34. YOLO Definition remains independently available when its own runtime is healthy.
35. InternImage Runtime down does not affect YOLO availability.
36. DB schema change: NO.
37. Manual INSERT seed required: YES.
38. SQL executed: NO.
39. Docker executed by Codex: NO. The user-run Runtime and direct Definition checks passed.
40. Files are listed in the Stage 5A Files section.
41. Automated test results are listed above and all code-level gates pass.
42. Stage 5A Code/Config Readiness: PASS. Final unified service-python/SERVER AVAILABLE acceptance remains pending user re-test.

Stage 5B and Stage 5C are not implemented.
