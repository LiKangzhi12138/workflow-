from __future__ import annotations

import base64
import hashlib
import logging
import os
import threading
from collections import OrderedDict
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, UnidentifiedImageError

from app.definition_check import check_definition
from app.model_template import build_model
from app.schemas import DatasetCheckRequest, DatasetCheckResult, WeightsValidationRequest, WeightsValidationResult
from app.segmentation_metrics import ConfusionMetrics, SegmentationMetricError


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
_VALIDATION_SLOT = threading.BoundedSemaphore(1)
logger = logging.getLogger("internimage-runtime.validation")


class WeightsValidationError(RuntimeError):
    def __init__(self, code: str, message: str, phase: str | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.phase = phase


def require(condition: bool, code: str, message: str) -> None:
    if not condition:
        raise WeightsValidationError(code, message)


def _diagnostic_context(payload) -> tuple[int, str, int, str]:
    return (
        payload.workflowId,
        payload.jobId,
        payload.globalWeights.assetId,
        payload.runtimeProfileId,
    )


def _phase(payload, name: str, action, *, failure_code: str, message: str):
    context = _diagnostic_context(payload)
    logger.info(
        "validation phase started: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s stage=%s",
        *context,
        name,
    )
    try:
        result = action()
    except WeightsValidationError as exc:
        if exc.phase is None:
            exc.phase = name
        logger.warning(
            "validation phase rejected: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s stage=%s reasonCode=%s",
            *context,
            name,
            exc.code,
        )
        raise
    except Exception as exc:
        logger.exception(
            "validation phase crashed: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s stage=%s exceptionType=%s",
            *context,
            name,
            type(exc).__name__,
        )
        code = failure_code
        try:
            import torch

            if isinstance(exc, torch.cuda.OutOfMemoryError):
                code = "CUDA_OUT_OF_MEMORY"
            elif name in {"MODEL_FORWARD", "CUDA_SETUP"} and isinstance(exc, RuntimeError):
                message_lower = str(exc).lower()
                if "out of memory" in message_lower:
                    code = "CUDA_OUT_OF_MEMORY"
                elif any(token in message_lower for token in (
                    "cuda", "cudnn", "cublas", "dcnv3", "device-side assert", "kernel image"
                )):
                    code = "CUDA_INFERENCE_FAILED" if name == "MODEL_FORWARD" else "CUDA_SETUP_FAILED"
        except ImportError:
            pass
        raise WeightsValidationError(code, message, name) from exc
    logger.info(
        "validation phase completed: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s stage=%s",
        *context,
        name,
    )
    return result


def controlled_path(raw: str, area: str, *, directory: bool, must_exist: bool = True) -> Path:
    root = (Path(os.getenv("WORKFLOW_STORAGE_ROOT", "/opt/workflow-platform/storage")) / area).resolve()
    unresolved = Path(raw).absolute()
    require(unresolved != root and unresolved.is_relative_to(root), "PATH_NOT_ALLOWED", "Validation path is outside managed storage.")
    current = root
    for part in unresolved.relative_to(root).parts:
        current = current / part
        require(not current.is_symlink(), "PATH_NOT_ALLOWED", "Linked validation paths are forbidden.")
    path = unresolved.resolve(strict=False)
    require(path != root and path.is_relative_to(root), "PATH_NOT_ALLOWED", "Validation path is outside managed storage.")
    if must_exist:
        require(path.is_dir() if directory else path.is_file(), "DATASET_INVALID" if directory else "GLOBAL_ASSET_INVALID", "Validation input is missing.")
    return path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def state_signatures(state: OrderedDict) -> dict[str, str]:
    keys = list(state)
    digest = lambda text: hashlib.sha256(text.encode("utf-8")).hexdigest()
    return {
        "stateDictKeyHash": digest("\n".join(keys)),
        "shapeSignature": digest("\n".join(f"{key}:{tuple(state[key].shape)}" for key in keys)),
        "dtypeSignature": digest("\n".join(f"{key}:{state[key].dtype}" for key in keys)),
    }


def safe_load(path: Path):
    import torch

    require(path.stat().st_size <= 500 * 1024 * 1024, "GLOBAL_ASSET_INVALID", "Global weights exceed the protocol limit.")
    wrapper = torch.load(path, map_location="cpu", weights_only=True)
    require(isinstance(wrapper, dict) and set(wrapper) == {"state_dict"}, "GLOBAL_ASSET_INVALID", "Weights wrapper is invalid.")
    state = wrapper["state_dict"]
    require(isinstance(state, OrderedDict) and bool(state), "GLOBAL_ASSET_INVALID", "State must be a nonempty OrderedDict.")
    require(len(state) <= 10_000, "GLOBAL_ASSET_INVALID", "Tensor count exceeds protocol limits.")
    total_elements = 0
    for key, tensor in state.items():
        require(isinstance(key, str) and len(key) <= 512 and isinstance(tensor, torch.Tensor), "GLOBAL_ASSET_INVALID", "State contains an invalid entry.")
        require(tensor.layout == torch.strided and not tensor.is_quantized and tensor.ndim <= 8, "GLOBAL_ASSET_INVALID", "Tensor layout or rank is unsupported.")
        require(tensor.numel() <= 100_000_000, "GLOBAL_ASSET_INVALID", "A tensor exceeds protocol limits.")
        total_elements += tensor.numel()
        require(total_elements <= 250_000_000, "GLOBAL_ASSET_INVALID", "State exceeds protocol element limits.")
        require(str(tensor.dtype).removeprefix("torch.") in {"float16", "float32", "int64"}, "WEIGHTS_DTYPE_UNSUPPORTED", "State dtype is unsupported.")
        require(not tensor.is_floating_point() or bool(torch.isfinite(tensor).all()), "WEIGHTS_NON_FINITE", "Global weights contain nonfinite values.")
    return state


def read_label_mask(path: Path) -> np.ndarray:
    try:
        with Image.open(path) as image:
            require(image.format == "PNG" and image.mode in {"P", "L"},
                    "DATASET_INVALID", "Mask must be a single-channel indexed or grayscale PNG.")
            require(0 < image.width <= 4096 and 0 < image.height <= 4096,
                    "DATASET_INVALID", "Mask dimensions exceed validation limits.")
            image.load()
            mask = np.asarray(image)
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise WeightsValidationError("DATASET_INVALID", "Mask PNG cannot be read.") from exc
    require(mask.ndim == 2 and mask.dtype == np.uint8,
            "DATASET_INVALID", "Mask must contain uint8 class indices.")
    return mask


def collect_dataset(dataset: Path, class_count: int, ignore_index: int) -> list[tuple[Path, Path]]:
    images_root, masks_root = dataset / "images", dataset / "masks"
    require(images_root.is_dir() and masks_root.is_dir(), "DATASET_INVALID", "Dataset requires images/ and masks/ directories.")
    require(not images_root.is_symlink() and not masks_root.is_symlink(), "PATH_NOT_ALLOWED", "Linked dataset directories are forbidden.")
    images = sorted(path for path in images_root.iterdir() if path.suffix.lower() in IMAGE_EXTENSIONS)
    require(0 < len(images) <= 1000, "DATASET_INVALID", "Dataset image count is invalid.")
    require(len({path.stem for path in images}) == len(images), "DATASET_INVALID", "Image basenames are duplicated.")
    masks = [path for path in masks_root.iterdir() if path.suffix.lower() == ".png"]
    mask_by_stem = {path.stem: path for path in masks}
    require(len(mask_by_stem) == len(masks), "DATASET_INVALID", "Mask basenames are duplicated.")
    require({path.stem for path in images} == set(mask_by_stem), "MASK_MISSING", "Every image needs exactly one PNG mask.")
    pairs = []
    for image in images:
        mask_path = mask_by_stem[image.stem]
        require(not image.is_symlink() and not mask_path.is_symlink(), "PATH_NOT_ALLOWED", "Linked dataset files are forbidden.")
        source = cv2.imread(str(image), cv2.IMREAD_COLOR)
        mask = read_label_mask(mask_path)
        require(source is not None, "DATASET_INVALID", "Dataset image cannot be read.")
        require(source.shape[0] <= 4096 and source.shape[1] <= 4096, "DATASET_INVALID", "Dataset image exceeds validation limits.")
        require(source.shape[:2] == mask.shape, "DATASET_INVALID", "Image and mask dimensions differ.")
        allowed = (mask == ignore_index) | (mask < class_count)
        require(bool(np.all(allowed)), "MASK_LABEL_INVALID", "Mask contains an unknown class label.")
        pairs.append((image, mask_path))
    return pairs


def check_dataset(payload: DatasetCheckRequest) -> DatasetCheckResult:
    definition = payload.modelDefinition
    availability = check_definition(definition, payload.runtimeProfileId)
    require(availability.available, availability.reasonCode, availability.message)
    dataset = controlled_path(payload.datasetPath, "datasets", directory=True)
    pairs = collect_dataset(dataset, len(definition["classOrder"]), definition["ignoreIndex"])
    return DatasetCheckResult(
        passed=True,
        reasonCode="DATASET_VALID",
        message="Segmentation image/mask pairs match the trusted Definition.",
        imageCount=len(pairs),
    )


def strict_rebuild(definition: dict, state: OrderedDict, payload=None):
    if payload is None:
        model = build_model(definition)
        expected = model.state_dict()
    else:
        def build_architecture():
            built = build_model(definition)
            return built, built.state_dict()

        model, expected = _phase(
            payload,
            "MODEL_REBUILD",
            build_architecture,
            failure_code="MODEL_REBUILD_FAILED",
            message="Trusted InternImage architecture could not be rebuilt.",
        )

    def load_strictly():
        require(list(state) == list(expected), "STRICT_LOAD_FAILED", "Global weights keys do not match the trusted architecture.")
        for key, target in expected.items():
            actual = state[key]
            require(tuple(actual.shape) == tuple(target.shape), "STRICT_LOAD_FAILED", "Global weights shape does not match the trusted architecture.")
            require(actual.dtype == target.dtype, "WEIGHTS_DTYPE_UNSUPPORTED", "Global weights dtype does not match the trusted architecture.")
        incompatible = model.load_state_dict(state, strict=True)
        require(not incompatible.missing_keys and not incompatible.unexpected_keys, "STRICT_LOAD_FAILED", "Strict state loading returned missing or unexpected keys.")
        return incompatible

    if payload is None:
        load_strictly()
        model = model.eval().cuda()
    else:
        _phase(payload, "STRICT_LOAD", load_strictly,
               failure_code="STRICT_LOAD_FAILED", message="Strict state loading failed.")
        model = _phase(payload, "CUDA_SETUP", lambda: model.eval().cuda(),
                       failure_code="CUDA_SETUP_FAILED", message="InternImage could not be placed on the GPU.")
    return model, len(expected), sum(int(t.numel()) for t in expected.values())


def preprocess(image: np.ndarray, definition: dict):
    import torch

    config = definition["definitionPayload"]["preprocessing"]
    width, height = config["imageSize"]
    resized = cv2.resize(image, (width, height), interpolation=cv2.INTER_LINEAR)
    if config["normalization"]["toRgb"]:
        resized = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    mean = np.asarray(config["normalization"]["mean"], dtype=np.float32)
    std = np.asarray(config["normalization"]["std"], dtype=np.float32)
    normalized = (resized.astype(np.float32) - mean) / std
    tensor = torch.from_numpy(normalized.transpose(2, 0, 1).copy()).unsqueeze(0).cuda()
    metadata = {
        "filename": "", "ori_filename": "", "ori_shape": image.shape,
        "img_shape": (height, width, 3), "pad_shape": (height, width, 3),
        "scale_factor": np.asarray([width / image.shape[1], height / image.shape[0]] * 2, dtype=np.float32),
        "flip": False, "flip_direction": None,
        "img_norm_cfg": {"mean": mean, "std": std, "to_rgb": config["normalization"]["toRgb"]},
    }
    return tensor, [metadata]


def encode_png(mask: np.ndarray) -> str:
    ok, encoded = cv2.imencode(".png", mask.astype(np.uint8))
    require(ok, "SAMPLE_RENDER_FAILED", "Sample mask rendering failed.")
    return base64.b64encode(encoded.tobytes()).decode("ascii")


def run_validation(payload: WeightsValidationRequest) -> WeightsValidationResult:
    import torch

    definition = payload.modelDefinition
    def verify_definition():
        result = check_definition(
            definition,
            payload.runtimeProfileId,
            diagnostic_context=_diagnostic_context(payload),
        )
        if not result.available:
            code = "MODEL_REBUILD_FAILED" if result.reasonCode == "SELF_CHECK_FAILED" else result.reasonCode
            raise WeightsValidationError(code, result.message)
        return result

    _phase(
        payload,
        "DEFINITION_CHECK",
        verify_definition,
        failure_code="MODEL_REBUILD_FAILED",
        message="Trusted InternImage Definition check failed.",
    )
    _phase(
        payload,
        "CUDA_SETUP",
        lambda: require(bool(torch.cuda.is_available()), "RUNTIME_UNAVAILABLE", "CUDA is required for InternImage validation."),
        failure_code="CUDA_SETUP_FAILED",
        message="InternImage GPU setup failed.",
    )
    def verify_global_asset():
        weights_path = controlled_path(payload.globalWeights.weightsPath, "federated-models", directory=False)
        require(weights_path.name == "global_weights.pt", "GLOBAL_ASSET_INVALID", "Global weights filename is invalid.")
        digest = sha256(weights_path)
        require(digest.lower() == payload.globalWeights.expectedSha256.lower(), "WEIGHTS_SHA_MISMATCH", "Global weights SHA mismatch.")
        return weights_path, digest

    weights, actual_sha = _phase(
        payload, "GLOBAL_ASSET_VERIFY", verify_global_asset,
        failure_code="GLOBAL_ASSET_INVALID", message="Global weights asset verification failed.",
    )
    dataset = _phase(
        payload,
        "DATASET_PREFLIGHT",
        lambda: controlled_path(payload.datasetPath, "datasets", directory=True),
        failure_code="DATASET_INVALID",
        message="Segmentation dataset preflight failed.",
    )
    _phase(
        payload,
        "OUTPUT_SETUP",
        lambda: controlled_path(payload.sampleOutputDirectory, "python/result-samples", directory=True, must_exist=False),
        failure_code="PATH_NOT_ALLOWED",
        message="Validation sample output location is invalid.",
    )
    pairs = _phase(
        payload,
        "DATASET_PREFLIGHT",
        lambda: collect_dataset(dataset, len(definition["classOrder"]), definition["ignoreIndex"]),
        failure_code="DATASET_INVALID",
        message="Segmentation dataset preflight failed.",
    )
    require(_VALIDATION_SLOT.acquire(blocking=False), "RUNTIME_BUSY", "InternImage validation is already running.")
    model = None
    try:
        state = _phase(
            payload, "WEIGHTS_LOAD", lambda: safe_load(weights),
            failure_code="WEIGHTS_LOAD_FAILED", message="Global weights could not be safely loaded.",
        )
        evidence = payload.globalWeights

        def verify_state_evidence():
            current_signatures = state_signatures(state)
            current_element_count = sum(int(t.numel()) for t in state.values())
            require(
                len(state) == evidence.expectedTensorCount
                and current_element_count == evidence.expectedElementCount
                and current_signatures == {
                    "stateDictKeyHash": evidence.expectedStateDictKeyHash,
                    "shapeSignature": evidence.expectedShapeSignature,
                    "dtypeSignature": evidence.expectedDtypeSignature,
                }, "WEIGHTS_STATE_MISMATCH", "Global weights state evidence changed.",
            )
            return current_signatures, current_element_count

        signatures, element_count = _phase(
            payload,
            "WEIGHTS_CONTRACT",
            verify_state_evidence,
            failure_code="WEIGHTS_STATE_MISMATCH",
            message="Global weights state evidence changed.",
        )
        model, expected_count, expected_elements = strict_rebuild(definition, state, payload)
        metrics_acc = ConfusionMetrics(definition["classOrder"], definition["ignoreIndex"])
        samples = []
        with torch.inference_mode():
            for index, (image_path, mask_path) in enumerate(pairs, start=1):
                def load_input():
                    source = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
                    require(source is not None, "DATASET_INVALID", "Dataset image cannot be read.")
                    return source, read_label_mask(mask_path)

                image, mask = _phase(
                    payload,
                    "IMAGE_PREPROCESS",
                    load_input,
                    failure_code="INPUT_PREPROCESS_FAILED",
                    message="Validation image or mask could not be prepared.",
                )
                tensor, metadata = _phase(
                    payload,
                    "IMAGE_PREPROCESS",
                    lambda: preprocess(image, definition),
                    failure_code="INPUT_PREPROCESS_FAILED",
                    message="Validation image preprocessing failed.",
                )

                def forward():
                    result = model.simple_test(tensor, metadata, rescale=True)
                    torch.cuda.synchronize()
                    return result

                output = _phase(
                    payload,
                    "MODEL_FORWARD",
                    forward,
                    failure_code="MODEL_FORWARD_FAILED",
                    message="InternImage segmentation inference failed.",
                )

                def decode_output():
                    require(isinstance(output, list) and len(output) == 1,
                            "OUTPUT_DECODE_FAILED", "Runtime prediction structure is invalid.")
                    prediction_map = np.asarray(output[0])
                    require(prediction_map.ndim == 2 and np.issubdtype(prediction_map.dtype, np.integer),
                            "OUTPUT_DECODE_FAILED", "Runtime prediction is not an integer label map.")
                    require(prediction_map.shape == mask.shape,
                            "OUTPUT_DECODE_FAILED", "Prediction did not align with original ground-truth size.")
                    valid_labels = (prediction_map >= 0) & (prediction_map < len(definition["classOrder"]))
                    require(bool(np.all(valid_labels)),
                            "OUTPUT_DECODE_FAILED", "Runtime prediction contains an unknown class label.")
                    return prediction_map

                prediction = _phase(
                    payload,
                    "OUTPUT_DECODE",
                    decode_output,
                    failure_code="OUTPUT_DECODE_FAILED",
                    message="InternImage prediction output could not be decoded.",
                )
                _phase(
                    payload,
                    "METRICS_COMPUTE",
                    lambda: metrics_acc.add(prediction, mask),
                    failure_code="METRICS_FAILED",
                    message="Ground-truth segmentation metrics could not be computed.",
                )
                if len(samples) < 6:
                    def render_sample():
                        palette = np.asarray([[0, 0, 0], [48, 180, 72], [44, 95, 220]], dtype=np.uint8)
                        color = palette[prediction.astype(np.int64)]
                        overlay = cv2.addWeighted(image, 0.6, color, 0.4, 0)
                        ok, jpeg = cv2.imencode(".jpg", overlay, [cv2.IMWRITE_JPEG_QUALITY, 85])
                        require(ok, "SAMPLE_RENDER_FAILED", "Sample overlay rendering failed.")
                        return {
                            "image_name": image_path.name, "image_path": str(image_path),
                            "ground_truth_mask_path": str(mask_path),
                            "prediction_mask_base64": encode_png(prediction),
                            "rendered_image_base64": base64.b64encode(jpeg.tobytes()).decode("ascii"),
                        }

                    samples.append(_phase(
                        payload,
                        "SAMPLE_RENDER",
                        render_sample,
                        failure_code="SAMPLE_RENDER_FAILED",
                        message="Segmentation sample rendering failed.",
                    ))
                logger.info(
                    "validation sample completed: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s sampleIndex=%s totalSamples=%s",
                    *_diagnostic_context(payload),
                    index,
                    len(pairs),
                )
        metrics = _phase(
            payload,
            "METRICS_COMPUTE",
            metrics_acc.result,
            failure_code="METRICS_FAILED",
            message="Ground-truth segmentation metrics could not be finalized.",
        )
        metrics["totalImages"] = len(pairs)
        reconstruction = {
            "weightsOnly": True, "checkpointUsed": False, "baseCheckpointUsed": False,
            "pretrainedUsed": False, "constructionMode": "TRUSTED_CONFIG_TEMPLATE",
            "strictLoad": True, "missingKeyCount": 0, "unexpectedKeyCount": 0,
            "expectedTensorCount": expected_count, "actualTensorCount": len(state),
            "expectedElementCount": expected_elements, "actualElementCount": element_count,
        }
        result = {"model_path": str(weights), "dataset_path": str(dataset),
                  "total_images": len(pairs), "visualization_mode": "SEGMENTATION_OVERLAY",
                  "has_annotated_images": bool(samples), "sample_results": samples}
        validation_evidence = {
            "validationMode": "WEIGHTS_PROTOCOL_V1", "taskType": "SEMANTIC_SEGMENTATION",
            "metricsMode": "GROUND_TRUTH", "workflowId": payload.workflowId,
            "globalWeightsAssetId": evidence.assetId, "modelDefinitionId": definition["definitionId"],
            "modelDefinitionCode": definition["code"],
            "definitionSha256": definition["definitionSha256"],
            "architectureSignature": definition["architectureSignature"],
            "globalWeightsSha256": actual_sha, "runtimeProfileId": payload.runtimeProfileId,
            **reconstruction, "tensorCount": len(state), "elementCount": element_count,
            **signatures, "validationCompleted": True,
        }
        return WeightsValidationResult(
            passed=True, reasonCode="WEIGHTS_VALIDATION_COMPLETED",
            message="InternImage ground-truth segmentation validation completed.",
            runtimeProfileId=payload.runtimeProfileId,
            modelDefinitionId=definition["definitionId"], modelDefinitionCode=definition["code"],
            definitionSha256=definition["definitionSha256"],
            architectureSignature=definition["architectureSignature"], weightsSha256=actual_sha,
            reconstruction=reconstruction, metrics=metrics, validationResult=result,
            validationEvidence=validation_evidence,
        )
    finally:
        del model
        if torch.cuda.is_available():
            try:
                torch.cuda.empty_cache()
            except Exception:
                logger.exception(
                    "validation cleanup failed: workflowId=%s jobId=%s assetId=%s runtimeProfileId=%s stage=CUDA_CLEANUP",
                    *_diagnostic_context(payload),
                )
        _VALIDATION_SLOT.release()
