# Stage 1B.5 Report

## 1. Conclusion

- Stage 1B.5 Code/Config Readiness: **PASS**
- Docker Runtime Certification: **NOT_EXECUTED**
- Production availability: **NOT_EVALUATED** until the manual Docker and golden-artifact certification succeeds.

This stage adds an independent, internal-only `yolo-runtime`. It does not switch the existing upload, FedAvg, global checkpoint, or validation paths to Protocol v1.

## 2. Existing And New Runtimes

The existing `service-python` remains Python 3.11 with `torch==2.6.0` installed from the CPU wheel index and `ultralytics==8.3.0`. Its requirements and legacy business responsibilities are unchanged.

The new runtime uses:

- base image: `pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime`;
- target Python: 3.11.x, verified at runtime rather than assumed from the tag;
- PyTorch: 2.7.1, CUDA build 12.8;
- torchvision: 0.22.1;
- Ultralytics: 8.4.41;
- CUDA and a visible GPU: required, with no CPU fallback.

The lab certification used Python 3.10.21. Python 3.11 is an intentional Docker target supported by the selected packages, but it is not declared project-certified until the manual golden weights strict-load and CUDA inference test passes.

The PyTorch image tag is published by the official PyTorch Docker repository. Ultralytics 8.4.41 package metadata declares Python 3.11 support and `ultralytics-thop>=2.0.18`:

- https://hub.docker.com/r/pytorch/pytorch/tags
- https://pypi.org/project/ultralytics/8.4.41/

## 3. Dependency Safety

`service-yolo-runtime/Dockerfile` verifies the base image's Torch and torchvision versions before installation. It installs the explicitly listed non-Ultralytics dependencies, then installs `ultralytics==8.4.41` with `--no-deps`, and verifies all three framework versions again.

This prevents pip dependency resolution from replacing the CUDA-enabled Torch pair with another build. No pretrained model or checkpoint is copied or downloaded.

## 4. Runtime API

Internal port: `8010`.

Endpoints:

- `GET /internal/runtime/health`
- `POST /internal/model-definitions/check`

The Compose service publishes no host port. It is reachable only through the default Docker network. The health response reports Python, Torch, torchvision, Ultralytics, CUDA build, CUDA availability, GPU visibility/name, errors, warnings, and `runtimeReady` with `probeContext=CONTAINER_RUNTIME`.

The Docker healthcheck requires `runtimeReady=true`. This deliberately distinguishes an HTTP-alive process from a usable GPU runtime. `service-python` does not depend on Runtime health for startup, so the old business path remains independently startable while the Registry flag is disabled.

## 5. Definition Self-check

The Runtime validates the formal Definition SHA256 and architecture signature, verifies the YOLO family/framework/class contract, and constructs only:

```python
DetectionModel(cfg=architecture_dict, ch=3, nc=1, verbose=False)
```

It restores `names`, validates class count and stride, and returns the availability result. It does not read `yolov8n.pt`, `best.pt`, client weights, or global weights. Neither `ModelDefinition` nor `RuntimeProfile` contains a checkpoint path.

## 6. Remote Availability Orchestration

For `YOLO_RUNTIME_V1`, `service-python` no longer imports Ultralytics or builds a model in the Stage 1B availability path. It:

1. validates Definition integrity locally;
2. validates the trusted RuntimeProfile binding;
3. calls Runtime health with a 2-second connect and 10-second read timeout;
4. independently verifies Python 3.11.x, Torch 2.7.1, torchvision 0.22.1, Ultralytics 8.4.41, CUDA 12.8, CUDA availability, GPU availability, and `CONTAINER_RUNTIME`;
5. calls the Runtime Definition check;
6. returns `AVAILABLE` only when the remote architecture-only self-check passes.

Unreachable Runtime, timeout, malformed response, HOST_DEV response, version mismatch, missing CUDA/GPU, Definition mismatch, or build failure all fail closed.

The Java `GET /api/model-definitions/available` flow is unchanged: DB `ENABLED` definitions are sent to Python and only an explicit `available=true` response is returned.

## 7. Compose And Feature Flags

`yolo-runtime` is under the Compose profile `model-runtime`, uses `gpus: all`, has no public port, and has the same bounded Docker log policy as other services.

Defaults remain safe:

```text
MODEL_DEFINITION_REGISTRY_V1_ENABLED=false
YOLO_RUNTIME_BASE_URL=http://yolo-runtime:8010
YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS=2
YOLO_RUNTIME_READ_TIMEOUT_SECONDS=10
```

`WEIGHTS_PROTOCOL_V1_ENABLED` remains disabled. The real `.env.local-server` was not modified; the user must enable the Registry manually for validation.

## 8. Golden Runtime Certification

`service-yolo-runtime/certify_runtime.py` is a deployment-only CLI, not an HTTP endpoint. It:

- verifies the frozen architecture signature;
- verifies weights SHA256 `aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c`;
- loads only `{ "state_dict": OrderedDict[str, Tensor] }` with `weights_only=True`;
- requires exactly 355 model/weights keys;
- records missing, unexpected, and shape-mismatch details;
- calls `load_state_dict(..., strict=True)`;
- restores class names;
- requires CUDA;
- performs inference on one real frozen image;
- rejects non-finite/invalid boxes, confidences outside `[0,1]`, and class IDs other than `0`.

The experimental artifacts are not copied into the image. Manual certification temporarily copies them into the running container.

## 9. Verification Results

- Python compile check: PASS.
- Existing `service-python` tests plus Stage 1B.5 tests: 54 passed, 1 skipped.
- `service-yolo-runtime` tests: 4 passed.
- Compose static parse with `--profile model-runtime`: PASS.
- Docker build/start: not executed.
- GPU health: not executed.
- Golden strict load/inference: not executed.
- Java tests: not rerun because Java sources/contracts were not modified.
- Frontend build: not run because frontend was not modified.

## 10. Compatibility Boundary

Not modified:

- existing `service-python/requirements.txt`;
- Java code;
- frontend;
- upload/AES;
- FedAvg/global model format;
- validation;
- database/schema/data;
- InternImage support.

The new Runtime does not qualify the project to enter Stage 1C until the manual Docker health, Definition availability, Java available-list, and golden certification steps all pass.
