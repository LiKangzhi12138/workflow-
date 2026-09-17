from __future__ import annotations

import base64
import hashlib
import importlib
import math
import os
from collections import OrderedDict
from pathlib import Path
from typing import Any

import numpy as np

from app.definition_check import build_detection_model, check_definition
from app.schemas import WeightsValidationRequest, WeightsValidationResult


SUPPORTED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
MAX_IMAGES = int(os.getenv("YOLO_VALIDATION_MAX_IMAGES", "1000"))
MAX_SAMPLE_RESULTS = int(os.getenv("YOLO_VALIDATION_MAX_SAMPLE_RESULTS", "6"))
CONFIDENCE_THRESHOLD = float(os.getenv("YOLO_VALIDATION_CONFIDENCE_THRESHOLD", "0.25"))


class WeightsValidationError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _require(condition: bool, code: str, message: str) -> None:
    if not condition:
        raise WeightsValidationError(code, message)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _hash_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _state_signatures(state: OrderedDict[str, Any]) -> dict[str, str]:
    keys = list(state.keys())
    return {
        "stateDictKeyHash": _hash_text("\n".join(keys)),
        "shapeSignature": _hash_text(
            "\n".join(f"{key}:{tuple(state[key].shape)}" for key in keys)
        ),
        "dtypeSignature": _hash_text(
            "\n".join(f"{key}:{state[key].dtype}" for key in keys)
        ),
    }


def _controlled_path(raw: str, *, directory: bool) -> Path:
    root = Path(os.getenv("WORKFLOW_STORAGE_ROOT", "/opt/workflow-platform/storage")).resolve()
    candidate = Path(raw).resolve(strict=False)
    _require(
        candidate != root and candidate.is_relative_to(root),
        "PATH_NOT_ALLOWED",
        "Validation input is outside the configured storage root.",
    )
    _require(
        candidate.exists()
        and (candidate.is_dir() if directory else candidate.is_file())
        and not candidate.is_symlink(),
        "WEIGHTS_VALIDATION_INPUT_INVALID",
        "Validation input is missing or linked.",
    )
    current = root
    for part in candidate.relative_to(root).parts:
        current = current / part
        _require(
            not current.is_symlink(),
            "PATH_NOT_ALLOWED",
            "Symbolic links are not allowed in validation inputs.",
        )
    return candidate


def _safe_load(path: Path):
    torch = importlib.import_module("torch")
    wrapper = torch.load(path, map_location="cpu", weights_only=True)
    _require(
        isinstance(wrapper, dict) and set(wrapper.keys()) == {"state_dict"},
        "WEIGHTS_VALIDATION_INPUT_INVALID",
        "Protocol v1 requires exactly one top-level state_dict key.",
    )
    state = wrapper["state_dict"]
    _require(
        isinstance(state, OrderedDict) and bool(state),
        "WEIGHTS_VALIDATION_INPUT_INVALID",
        "state_dict must be a non-empty OrderedDict.",
    )
    _require(
        all(isinstance(value, torch.Tensor) for value in state.values()),
        "WEIGHTS_VALIDATION_INPUT_INVALID",
        "state_dict contains a non-Tensor value.",
    )
    for tensor in state.values():
        if tensor.is_floating_point() or tensor.is_complex():
            _require(
                bool(torch.isfinite(tensor).all().item()),
                "WEIGHTS_VALIDATION_NON_FINITE",
                "Global weights contain NaN or Infinity.",
            )
    return state


def _strict_rebuild(definition: dict[str, Any], state):
    model, _ = build_detection_model(definition)
    expected = model.state_dict()
    missing = [key for key in expected if key not in state]
    unexpected = [key for key in state if key not in expected]
    shape_mismatches = [
        key for key in expected
        if key in state and tuple(expected[key].shape) != tuple(state[key].shape)
    ]
    _require(not missing, "WEIGHTS_VALIDATION_MISSING_KEY", "Global weights are missing model state keys.")
    _require(not unexpected, "WEIGHTS_VALIDATION_UNEXPECTED_KEY", "Global weights contain unexpected model state keys.")
    _require(not shape_mismatches, "WEIGHTS_VALIDATION_SHAPE_MISMATCH", "Global weights contain incompatible tensor shapes.")
    incompatible = model.load_state_dict(state, strict=True)
    _require(
        not incompatible.missing_keys and not incompatible.unexpected_keys,
        "WEIGHTS_VALIDATION_STRICT_LOAD_FAILED",
        "Strict model state loading failed.",
    )
    model.names = {index: name for index, name in enumerate(definition["classOrder"])}
    model.float().eval().cuda()
    return model, len(expected)


def _collect_images(dataset: Path) -> list[Path]:
    image_root = dataset / "images" if (dataset / "images").is_dir() else dataset
    images = sorted(
        path for path in image_root.rglob("*")
        if path.is_file() and path.suffix.lower() in SUPPORTED_IMAGE_EXTENSIONS
    )
    return images[:MAX_IMAGES]


def _label_path(dataset: Path, image_path: Path) -> Path:
    image_root = dataset / "images" if (dataset / "images").is_dir() else dataset
    try:
        relative = image_path.relative_to(image_root)
    except ValueError:
        relative = Path(image_path.name)
    return (dataset / "labels" / relative).with_suffix(".txt")


def _read_labels(dataset: Path, image_path: Path, width: int, height: int) -> list[dict[str, Any]]:
    label_path = _label_path(dataset, image_path)
    if not label_path.is_file():
        return []
    labels: list[dict[str, Any]] = []
    for raw_line in label_path.read_text(encoding="utf-8").splitlines():
        parts = raw_line.strip().split()
        if len(parts) < 5:
            continue
        class_id = int(float(parts[0]))
        x, y, w, h = (float(value) for value in parts[1:5])
        labels.append({
            "classId": class_id,
            "bbox": [
                (x - w / 2.0) * width,
                (y - h / 2.0) * height,
                (x + w / 2.0) * width,
                (y + h / 2.0) * height,
            ],
        })
    return labels


def _iou(left: list[float], right: list[float]) -> float:
    x1, y1 = max(left[0], right[0]), max(left[1], right[1])
    x2, y2 = min(left[2], right[2]), min(left[3], right[3])
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    left_area = max(0.0, left[2] - left[0]) * max(0.0, left[3] - left[1])
    right_area = max(0.0, right[2] - right[0]) * max(0.0, right[3] - right[1])
    union = left_area + right_area - intersection
    return intersection / union if union > 0 else 0.0


def _average_precision(recalls: np.ndarray, precisions: np.ndarray) -> float:
    mrec = np.concatenate(([0.0], recalls, [1.0]))
    mpre = np.concatenate(([1.0], precisions, [0.0]))
    mpre = np.flip(np.maximum.accumulate(np.flip(mpre)))
    x = np.linspace(0.0, 1.0, 101)
    return float(np.trapz(np.interp(x, mrec, mpre), x=x))


def _evaluate(records: list[dict[str, Any]], class_count: int) -> dict[str, float]:
    thresholds = np.arange(0.5, 0.96, 0.05)
    aps: list[list[float]] = []
    precision_at_50: list[float] = []
    recall_at_50: list[float] = []
    for class_id in range(class_count):
        ground_truth = {
            index: [item for item in record["labels"] if item["classId"] == class_id]
            for index, record in enumerate(records)
        }
        total_ground_truth = sum(len(items) for items in ground_truth.values())
        predictions = sorted(
            [
                (index, item)
                for index, record in enumerate(records)
                for item in record["predictions"]
                if item["classId"] == class_id
            ],
            key=lambda pair: pair[1]["confidence"],
            reverse=True,
        )
        if total_ground_truth == 0:
            continue
        class_aps: list[float] = []
        for threshold in thresholds:
            matched: dict[int, set[int]] = {}
            true_positives: list[float] = []
            false_positives: list[float] = []
            for image_index, prediction in predictions:
                candidates = ground_truth[image_index]
                used = matched.setdefault(image_index, set())
                best_index = -1
                best_iou = 0.0
                for candidate_index, target in enumerate(candidates):
                    if candidate_index in used:
                        continue
                    overlap = _iou(prediction["bbox"], target["bbox"])
                    if overlap > best_iou:
                        best_iou = overlap
                        best_index = candidate_index
                hit = best_index >= 0 and best_iou >= float(threshold)
                if hit:
                    used.add(best_index)
                true_positives.append(1.0 if hit else 0.0)
                false_positives.append(0.0 if hit else 1.0)
            if predictions:
                tp = np.cumsum(np.asarray(true_positives))
                fp = np.cumsum(np.asarray(false_positives))
                recalls = tp / total_ground_truth
                precisions = tp / np.maximum(tp + fp, 1e-12)
                ap = _average_precision(recalls, precisions)
                if math.isclose(float(threshold), 0.5):
                    precision_at_50.append(float(precisions[-1]))
                    recall_at_50.append(float(recalls[-1]))
            else:
                ap = 0.0
                if math.isclose(float(threshold), 0.5):
                    precision_at_50.append(0.0)
                    recall_at_50.append(0.0)
            class_aps.append(ap)
        aps.append(class_aps)
    if not aps:
        return {"precision": 0.0, "recall": 0.0, "map50": 0.0, "map50_95": 0.0}
    matrix = np.asarray(aps)
    return {
        "precision": float(np.mean(precision_at_50)),
        "recall": float(np.mean(recall_at_50)),
        "map50": float(np.mean(matrix[:, 0])),
        "map50_95": float(np.mean(matrix)),
    }


def _run_validation(model, dataset: Path, class_names: list[str]) -> dict[str, Any]:
    detect = importlib.import_module("ultralytics.models.yolo.detect")
    cv2 = importlib.import_module("cv2")
    image_paths = _collect_images(dataset)
    _require(bool(image_paths), "DATASET_INVALID", "No validation images were found.")
    predictor = detect.DetectionPredictor(overrides={
        "task": "detect",
        "mode": "predict",
        "imgsz": 640,
        "device": 0,
        "conf": 0.001,
        "iou": 0.7,
        "max_det": 300,
        "half": False,
        "save": False,
        "verbose": False,
    })
    records: list[dict[str, Any]] = []
    sample_results: list[dict[str, Any]] = []
    class_counts: dict[str, int] = {}
    class_confidences: dict[str, list[float]] = {}
    for offset in range(0, len(image_paths), 16):
        batch = image_paths[offset: offset + 16]
        results = list(predictor(source=[str(path) for path in batch], model=model, stream=False))
        _require(len(results) == len(batch), "INFERENCE_FAILED", "YOLO inference returned an unexpected result count.")
        for image_path, result in zip(batch, results):
            height, width = (int(value) for value in result.orig_shape)
            metric_predictions: list[dict[str, Any]] = []
            predictions: list[dict[str, Any]] = []
            boxes = result.boxes
            if boxes is not None:
                for xyxy, confidence, class_id in zip(boxes.xyxy, boxes.conf, boxes.cls):
                    bbox = [float(value) for value in xyxy.detach().cpu().tolist()]
                    conf = float(confidence.detach().cpu().item())
                    cls = int(class_id.detach().cpu().item())
                    _require(
                        all(math.isfinite(value) for value in bbox)
                        and math.isfinite(conf)
                        and 0.0 <= conf <= 1.0
                        and 0 <= cls < len(class_names),
                        "INFERENCE_INVALID",
                        "YOLO inference produced an invalid detection.",
                    )
                    label = class_names[cls]
                    metric_prediction = {
                        "label": label,
                        "confidence": round(conf, 6),
                        "category": "livestock" if label.lower() in {"sheep", "cattle", "cow", "goat", "pig"} else "other",
                        "bbox": bbox,
                        "classId": cls,
                    }
                    metric_predictions.append(metric_prediction)
                    if conf < CONFIDENCE_THRESHOLD:
                        continue
                    predictions.append(metric_prediction)
                    class_counts[label] = class_counts.get(label, 0) + 1
                    class_confidences.setdefault(label, []).append(conf)
            records.append({
                "predictions": metric_predictions,
                "labels": _read_labels(dataset, image_path, width, height),
            })
            if len(sample_results) < MAX_SAMPLE_RESULTS:
                ok, encoded = cv2.imencode(".jpg", result.plot())
                _require(bool(ok), "RENDER_FAILED", "YOLO annotated sample rendering failed.")
                sample_results.append({
                    "image_name": image_path.name,
                    "image_path": str(image_path),
                    "rendered_image_base64": base64.b64encode(encoded.tobytes()).decode("ascii"),
                    "annotated_image_available": True,
                    "prediction_count": len(predictions),
                    "predictions": [
                        {key: value for key, value in prediction.items() if key != "classId"}
                        for prediction in predictions
                    ],
                })

    has_labels = any(record["labels"] for record in records)
    if has_labels:
        metric_values = _evaluate(records, len(class_names))
        fallback = False
        fallback_reason = None
    else:
        confidences = [value for values in class_confidences.values() for value in values]
        precision = float(np.mean(confidences)) if confidences else 0.0
        recall = min(sum(class_counts.values()), len(image_paths)) / len(image_paths)
        map50 = (precision + recall) / 2.0
        metric_values = {
            "precision": precision,
            "recall": recall,
            "map50": map50,
            "map50_95": map50 * 0.65,
        }
        fallback = True
        fallback_reason = "labels_missing_estimated_metrics"

    per_class = {
        name: {
            "count": class_counts.get(name, 0),
            "avg_confidence": round(float(np.mean(class_confidences.get(name, [0.0]))), 4),
            "category": "livestock" if name.lower() in {"sheep", "cattle", "cow", "goat", "pig"} else "other",
        }
        for name in class_names
    }
    rounded = {key: round(value, 4) for key, value in metric_values.items()}
    rounded["accuracy"] = round((rounded["precision"] + rounded["recall"]) / 2.0, 4)
    return {
        "mAP": rounded["map50"],
        **rounded,
        "total_images": len(image_paths),
        "crop_detections": 0,
        "livestock_detections": sum(class_counts.values()),
        "per_class_results": per_class,
        "sample_results": sample_results,
        "visualization_mode": "SOURCE_IMAGE_PREDICTIONS",
        "has_annotated_images": bool(sample_results),
        "fallback": fallback,
        "fallback_reason": fallback_reason,
        "dataset_path": str(dataset),
        "error": None,
    }


def validate_weights(payload: WeightsValidationRequest) -> WeightsValidationResult:
    definition = payload.modelDefinition
    availability = check_definition(definition, payload.runtimeProfileId)
    _require(availability.available, availability.reasonCode, availability.message)
    weights_path = _controlled_path(payload.globalWeights.weightsPath, directory=False)
    dataset_path = _controlled_path(payload.datasetPath, directory=True)
    actual_sha = _sha256(weights_path)
    _require(
        actual_sha.lower() == payload.globalWeights.expectedSha256.lower(),
        "WEIGHTS_VALIDATION_SHA_MISMATCH",
        "Global weights SHA256 does not match trusted evidence.",
    )
    state = _safe_load(weights_path)
    signatures = _state_signatures(state)
    element_count = sum(int(tensor.numel()) for tensor in state.values())
    _require(
        len(state) == payload.globalWeights.expectedTensorCount
        and element_count == payload.globalWeights.expectedElementCount
        and signatures["stateDictKeyHash"] == payload.globalWeights.expectedStateDictKeyHash
        and signatures["shapeSignature"] == payload.globalWeights.expectedShapeSignature
        and signatures["dtypeSignature"] == payload.globalWeights.expectedDtypeSignature,
        "WEIGHTS_VALIDATION_STATE_MISMATCH",
        "Global weights state contract does not match trusted evidence.",
    )
    model, model_key_count = _strict_rebuild(definition, state)
    validation_result = _run_validation(model, dataset_path, list(definition["classOrder"]))
    validation_result["model_path"] = str(weights_path)
    validation_result["yolo_version"] = payload.algorithmType
    metrics = {
        "mAP": validation_result["map50"],
        "precision": validation_result["precision"],
        "recall": validation_result["recall"],
        "map50": validation_result["map50"],
        "map50_95": validation_result["map50_95"],
        "accuracy": validation_result["accuracy"],
        "totalImages": validation_result["total_images"],
        "cropDetections": validation_result["crop_detections"],
        "livestockDetections": validation_result["livestock_detections"],
        "perClassResults": validation_result["per_class_results"],
        "fallback": validation_result["fallback"],
        "fallbackReason": validation_result["fallback_reason"],
    }
    reconstruction = {
        "weightsOnly": True,
        "checkpointUsed": False,
        "baseCheckpointUsed": False,
        "reconstructionMode": "DEFINITION_PLUS_STATE_DICT",
        "strictLoad": True,
        "missingKeyCount": 0,
        "unexpectedKeyCount": 0,
        "modelKeyCount": model_key_count,
        "weightsKeyCount": len(state),
    }
    evidence = {
        "validationMode": "WEIGHTS_PROTOCOL_V1",
        "workflowId": payload.workflowId,
        "globalWeightsAssetId": payload.globalWeights.assetId,
        "modelDefinitionId": definition["definitionId"],
        "modelDefinitionCode": definition["code"],
        "definitionSha256": definition["definitionSha256"],
        "architectureSignature": definition["architectureSignature"],
        "globalWeightsSha256": actual_sha,
        "runtimeProfileId": payload.runtimeProfileId,
        **reconstruction,
        "tensorCount": len(state),
        "elementCount": element_count,
        **signatures,
        "validationCompleted": True,
    }
    return WeightsValidationResult(
        passed=True,
        reasonCode="WEIGHTS_VALIDATION_COMPLETED",
        message="Weights-only model reconstruction and validation completed.",
        runtimeProfileId=payload.runtimeProfileId,
        modelDefinitionId=definition["definitionId"],
        modelDefinitionCode=definition["code"],
        definitionSha256=definition["definitionSha256"],
        architectureSignature=definition["architectureSignature"],
        weightsSha256=actual_sha,
        reconstruction=reconstruction,
        metrics=metrics,
        validationResult=validation_result,
        validationEvidence=evidence,
    )
