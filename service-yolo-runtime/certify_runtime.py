from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import platform
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Any

import torch
import torchvision
import ultralytics
from ultralytics.models.yolo.detect import DetectionPredictor
from ultralytics.nn.tasks import DetectionModel


EXPECTED_RUNTIME_PROFILE_ID = "YOLO_RUNTIME_V1"
EXPECTED_ULTRALYTICS = "8.4.41"
EXPECTED_TORCH = "2.7.1"
EXPECTED_TORCHVISION = "0.22.1"
EXPECTED_ARCHITECTURE_SIGNATURE = (
    "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a"
)
EXPECTED_WEIGHTS_SHA256 = (
    "aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c"
)
EXPECTED_KEY_COUNT = 355


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalized_definition(raw: dict[str, Any]) -> dict[str, Any]:
    if "definitionPayload" in raw:
        return {
            "architecture": raw["definitionPayload"]["architecture"],
            "inputChannels": raw["definitionPayload"]["inputChannels"],
            "classCount": raw["classCount"],
            "classOrder": raw["classOrder"],
            "stride": raw["definitionPayload"]["stride"],
            "architectureSignature": raw["architectureSignature"],
            "predictionSettings": raw["definitionPayload"].get("predictionSettings", {}),
            "runtimeProfileId": raw.get("runtimeProfileId"),
        }
    return {
        "architecture": raw["architecture"],
        "inputChannels": raw["inputChannels"],
        "classCount": raw["classCount"],
        "classOrder": raw["classOrder"],
        "stride": raw["stride"],
        "architectureSignature": raw["architectureSignature"],
        "predictionSettings": raw.get("predictionSettings", {}),
        "runtimeProfileId": raw.get("runtimeProfileId", EXPECTED_RUNTIME_PROFILE_ID),
    }


def safe_load_weights(path: Path) -> OrderedDict[str, torch.Tensor]:
    wrapper = torch.load(path, map_location="cpu", weights_only=True)
    if not isinstance(wrapper, dict) or list(wrapper.keys()) != ["state_dict"]:
        raise TypeError("weights package must contain only the top-level state_dict key")
    state = wrapper["state_dict"]
    if not isinstance(state, OrderedDict):
        raise TypeError("state_dict must be an OrderedDict")
    if not all(isinstance(value, torch.Tensor) for value in state.values()):
        raise TypeError("state_dict contains a non-Tensor value")
    return state


def strict_rebuild(
    definition: dict[str, Any], state: OrderedDict[str, torch.Tensor]
) -> tuple[DetectionModel, dict[str, Any]]:
    model = DetectionModel(
        cfg=copy.deepcopy(definition["architecture"]),
        ch=int(definition["inputChannels"]),
        nc=int(definition["classCount"]),
        verbose=False,
    )
    model_state = model.state_dict()
    missing_before = [key for key in model_state if key not in state]
    unexpected_before = [key for key in state if key not in model_state]
    shape_mismatches = [
        {
            "key": key,
            "modelShape": list(model_state[key].shape),
            "weightsShape": list(state[key].shape),
        }
        for key in model_state
        if key in state and model_state[key].shape != state[key].shape
    ]
    incompatible = model.load_state_dict(state, strict=True)
    model.names = {
        index: name for index, name in enumerate(definition["classOrder"])
    }
    model.float().eval()
    return model, {
        "passed": (
            not missing_before
            and not unexpected_before
            and not shape_mismatches
            and not incompatible.missing_keys
            and not incompatible.unexpected_keys
            and len(model_state) == len(state) == EXPECTED_KEY_COUNT
        ),
        "modelKeyCount": len(model_state),
        "weightsKeyCount": len(state),
        "missingKeys": list(incompatible.missing_keys),
        "unexpectedKeys": list(incompatible.unexpected_keys),
        "missingKeysBeforeLoad": missing_before,
        "unexpectedKeysBeforeLoad": unexpected_before,
        "shapeMismatches": shape_mismatches,
        "strict": True,
    }


def run_inference(model: DetectionModel, image_path: Path) -> dict[str, Any]:
    overrides = {
        "task": "detect",
        "mode": "predict",
        "imgsz": 640,
        "device": 0,
        "conf": 0.25,
        "iou": 0.7,
        "max_det": 300,
        "augment": False,
        "half": False,
        "agnostic_nms": False,
        "classes": None,
        "save": False,
        "save_txt": False,
        "save_conf": False,
        "save_crop": False,
        "show": False,
        "verbose": False,
    }
    predictor = DetectionPredictor(overrides=overrides)
    results = list(predictor(source=[str(image_path)], model=model, stream=False))
    if len(results) != 1:
        raise RuntimeError(f"Expected one inference result, got {len(results)}")
    boxes = results[0].boxes
    invalid = 0
    detections: list[dict[str, Any]] = []
    if boxes is not None:
        for xyxy, confidence, class_id in zip(boxes.xyxy, boxes.conf, boxes.cls):
            coords = [float(value) for value in xyxy.detach().cpu().tolist()]
            conf = float(confidence.detach().cpu().item())
            cls = int(class_id.detach().cpu().item())
            valid = (
                all(math.isfinite(value) for value in coords)
                and coords[2] >= coords[0]
                and coords[3] >= coords[1]
                and math.isfinite(conf)
                and 0.0 <= conf <= 1.0
                and cls == 0
            )
            invalid += int(not valid)
            detections.append(
                {"xyxy": coords, "confidence": conf, "classId": cls, "valid": valid}
            )
    return {
        "passed": invalid == 0,
        "image": image_path.name,
        "detectionCount": len(detections),
        "invalidDetectionCount": invalid,
        "detections": detections,
    }


def certify(args: argparse.Namespace) -> dict[str, Any]:
    definition_path = Path(args.definition).resolve(strict=True)
    weights_path = Path(args.weights).resolve(strict=True)
    image_path = Path(args.image).resolve(strict=True)
    raw_definition = json.loads(definition_path.read_text(encoding="utf-8"))
    definition = normalized_definition(raw_definition)
    architecture_signature = canonical_sha256(definition["architecture"])
    weights_sha256 = file_sha256(weights_path)

    runtime_checks = {
        "pythonVersion": platform.python_version(),
        "torchVersion": torch.__version__,
        "torchvisionVersion": torchvision.__version__,
        "ultralyticsVersion": ultralytics.__version__,
        "cudaBuildVersion": torch.version.cuda,
        "cudaAvailable": torch.cuda.is_available(),
        "gpuName": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
    }
    runtime_passed = (
        platform.python_version().startswith("3.11.")
        and torch.__version__.split("+", 1)[0] == EXPECTED_TORCH
        and torchvision.__version__.split("+", 1)[0] == EXPECTED_TORCHVISION
        and ultralytics.__version__ == EXPECTED_ULTRALYTICS
        and torch.version.cuda == "12.8"
        and torch.cuda.is_available()
    )
    definition_passed = (
        architecture_signature == definition["architectureSignature"]
        and architecture_signature == EXPECTED_ARCHITECTURE_SIGNATURE
        and definition["runtimeProfileId"] == EXPECTED_RUNTIME_PROFILE_ID
        and definition["classOrder"] == ["sheep"]
    )
    weights_sha_passed = weights_sha256 == args.expected_weights_sha256
    state = safe_load_weights(weights_path)
    model, strict_load = strict_rebuild(definition, state)
    if not runtime_passed or not definition_passed or not weights_sha_passed or not strict_load["passed"]:
        inference = {"passed": False, "skipped": True, "reason": "pre-inference certification failed"}
    else:
        model.cuda()
        inference = run_inference(model, image_path)

    passed = all(
        [
            runtime_passed,
            definition_passed,
            weights_sha_passed,
            strict_load["passed"],
            inference["passed"],
        ]
    )
    return {
        "passed": passed,
        "runtimeProfileId": EXPECTED_RUNTIME_PROFILE_ID,
        "runtime": {"passed": runtime_passed, **runtime_checks},
        "definition": {
            "passed": definition_passed,
            "path": str(definition_path),
            "architectureSignature": architecture_signature,
        },
        "weights": {
            "passed": weights_sha_passed,
            "path": str(weights_path),
            "sha256": weights_sha256,
            "expectedSha256": args.expected_weights_sha256,
            "weightsOnlyLoader": True,
        },
        "strictLoad": strict_load,
        "inference": inference,
        "checkpointUsed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Certify the workflow-platform YOLO Runtime")
    parser.add_argument("--definition", required=True)
    parser.add_argument("--weights", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument(
        "--expected-weights-sha256",
        default=EXPECTED_WEIGHTS_SHA256,
    )
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        report = certify(args)
    except Exception as exc:
        report = {
            "passed": False,
            "errorType": type(exc).__name__,
            "message": str(exc),
            "checkpointUsed": False,
        }
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    print(output, end="")
    return 0 if report.get("passed") is True else 1


if __name__ == "__main__":
    sys.exit(main())
