from __future__ import annotations

import copy
import hashlib
import importlib
import json
from typing import Any

from app.runtime_probe import RUNTIME_PROFILE_ID, probe_runtime
from app.schemas import ModelAvailabilityResult


EXPECTED_ARCHITECTURE_SIGNATURE = (
    "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a"
)


def build_detection_model(definition: dict[str, Any]):
    tasks = importlib.import_module("ultralytics.nn.tasks")
    detection_model_class = getattr(tasks, "DetectionModel")
    model = detection_model_class(
        cfg=copy.deepcopy(definition["definitionPayload"]["architecture"]),
        ch=int(definition["definitionPayload"]["inputChannels"]),
        nc=int(definition["classCount"]),
        verbose=False,
    )
    model.names = {
        index: name for index, name in enumerate(definition["classOrder"])
    }
    return model, detection_model_class


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _result(
    definition: dict[str, Any],
    *,
    available: bool,
    reason: str,
    message: str,
    definition_valid: bool | None,
    runtime_available: bool | None,
    self_check_passed: bool | None,
) -> ModelAvailabilityResult:
    health = probe_runtime()
    return ModelAvailabilityResult(
        available=available,
        availabilityState="AVAILABLE" if available else "UNAVAILABLE",
        definitionId=definition.get("definitionId", definition.get("code", "UNKNOWN")),
        definitionCode=str(definition.get("code", "UNKNOWN")),
        modelFamily=str(definition.get("modelFamily", "UNKNOWN")),
        variant=str(definition.get("variant", "UNKNOWN")),
        taskType=str(definition.get("taskType", "UNKNOWN")),
        adapterAvailable=True,
        runtimeAvailable=runtime_available,
        definitionValid=definition_valid,
        definitionEnabled=definition.get("status") == "ENABLED",
        runtimeEvaluated=True,
        selfCheckPassed=self_check_passed,
        probeContext=health.probeContext,
        detectedPythonVersion=health.pythonVersion,
        detectedFrameworkVersion=health.ultralyticsVersion,
        detectedTorchVersion=health.torchVersion,
        cudaAvailable=health.cudaAvailable,
        gpuName=health.gpuName,
        reasonCode=reason,
        message=message,
    )


def check_definition(
    definition: dict[str, Any], runtime_profile_id: str
) -> ModelAvailabilityResult:
    if runtime_profile_id != RUNTIME_PROFILE_ID or definition.get("runtimeProfileId") != RUNTIME_PROFILE_ID:
        return _result(
            definition,
            available=False,
            reason="RUNTIME_PROFILE_NOT_FOUND",
            message="ModelDefinition is not bound to YOLO_RUNTIME_V1.",
            definition_valid=False,
            runtime_available=False,
            self_check_passed=False,
        )
    if definition.get("status") != "ENABLED":
        return _result(
            definition,
            available=False,
            reason="DISABLED",
            message="ModelDefinition is disabled.",
            definition_valid=None,
            runtime_available=None,
            self_check_passed=None,
        )

    architecture = definition.get("definitionPayload", {}).get("architecture")
    definition_for_hash = copy.deepcopy(definition)
    definition_for_hash["definitionSha256"] = ""
    if canonical_sha256(definition_for_hash) != str(definition.get("definitionSha256", "")):
        return _result(
            definition,
            available=False,
            reason="DEFINITION_SHA_MISMATCH",
            message="ModelDefinition canonical SHA256 mismatch.",
            definition_valid=False,
            runtime_available=None,
            self_check_passed=False,
        )
    architecture_signature = canonical_sha256(architecture)
    if (
        not isinstance(architecture, dict)
        or architecture_signature != definition.get("architectureSignature")
        or architecture_signature != EXPECTED_ARCHITECTURE_SIGNATURE
    ):
        return _result(
            definition,
            available=False,
            reason="ARCHITECTURE_SIGNATURE_MISMATCH",
            message="YOLO architecture signature mismatch.",
            definition_valid=False,
            runtime_available=None,
            self_check_passed=False,
        )
    if (
        definition.get("modelFamily") != "YOLO"
        or definition.get("definitionType") != "ULTRALYTICS_DETECTION_MODEL"
        or definition.get("framework") != "Ultralytics"
        or definition.get("frameworkVersion") != "8.4.41"
        or definition.get("classCount") != len(definition.get("classOrder", []))
    ):
        return _result(
            definition,
            available=False,
            reason="DEFINITION_INVALID",
            message="ModelDefinition does not match the trusted YOLO runtime contract.",
            definition_valid=False,
            runtime_available=None,
            self_check_passed=False,
        )

    health = probe_runtime()
    if not health.runtimeReady:
        return _result(
            definition,
            available=False,
            reason="RUNTIME_NOT_READY",
            message="YOLO runtime is not ready: " + "; ".join(health.errors),
            definition_valid=True,
            runtime_available=False,
            self_check_passed=None,
        )

    try:
        model, detection_model_class = build_detection_model(definition)
        actual_stride = [float(value) for value in model.stride.detach().cpu().tolist()]
        expected_stride = [
            float(value) for value in definition["definitionPayload"].get("stride", [])
        ]
        actual_nc = int(model.model[-1].nc)
        expected_names = {
            index: name for index, name in enumerate(definition["classOrder"])
        }
        passed = (
            isinstance(model, detection_model_class)
            and actual_nc == definition["classCount"]
            and model.names == expected_names
            and actual_stride == expected_stride
        )
    except Exception as exc:
        return _result(
            definition,
            available=False,
            reason="SELF_CHECK_FAILED",
            message=f"YOLO architecture-only build failed: {type(exc).__name__}.",
            definition_valid=True,
            runtime_available=True,
            self_check_passed=False,
        )

    return _result(
        definition,
        available=passed,
        reason="AVAILABLE" if passed else "SELF_CHECK_FAILED",
        message=(
            "YOLO runtime and architecture-only Definition self-check passed."
            if passed
            else "YOLO architecture-only build produced an unexpected contract."
        ),
        definition_valid=True,
        runtime_available=True,
        self_check_passed=passed,
    )
