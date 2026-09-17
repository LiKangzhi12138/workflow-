from __future__ import annotations

import copy
import hashlib
import json
from typing import Any

from app.model_template import (
    EXPECTED_ELEMENT_COUNT,
    EXPECTED_TENSOR_COUNT,
    TEMPLATE_ID,
    build_model,
    trusted_architecture,
)
from app.runtime_probe import RUNTIME_PROFILE_ID, probe_runtime
from app.schemas import ModelAvailabilityResult


PROHIBITED_CONSTRUCTION_FIELDS = {
    "baseCheckpointPath",
    "checkpoint",
    "checkpointPath",
    "init_cfg",
    "load_from",
    "pretrained",
    "pretrainedCheckpointPath",
    "resume_from",
}


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


EXPECTED_ARCHITECTURE_SIGNATURE = canonical_sha256(trusted_architecture())


def _has_prohibited_value(value: Any, path: str = "") -> str | None:
    if isinstance(value, dict):
        for key, nested in value.items():
            current = f"{path}.{key}" if path else str(key)
            if key in PROHIBITED_CONSTRUCTION_FIELDS and nested not in (None, "", False):
                return current
            found = _has_prohibited_value(nested, current)
            if found:
                return found
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            found = _has_prohibited_value(nested, f"{path}[{index}]")
            if found:
                return found
    return None


def _result(
    definition: dict[str, Any],
    *,
    available: bool,
    reason: str,
    message: str,
    definition_valid: bool | None,
    runtime_available: bool | None,
    self_check_passed: bool | None,
    tensor_count: int | None = None,
    element_count: int | None = None,
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
        detectedFrameworkVersion=health.mmsegVersion,
        detectedTorchVersion=health.torchVersion,
        cudaAvailable=health.cudaAvailable,
        gpuName=health.gpuName,
        reasonCode=reason,
        message=message,
        checkpointUsed=False,
        baseCheckpointUsed=False,
        pretrainedUsed=False,
        constructionMode="TRUSTED_CONFIG_TEMPLATE",
        tensorCount=tensor_count,
        elementCount=element_count,
    )


def check_definition(
    definition: dict[str, Any], runtime_profile_id: str
) -> ModelAvailabilityResult:
    if runtime_profile_id != RUNTIME_PROFILE_ID or definition.get("runtimeProfileId") != RUNTIME_PROFILE_ID:
        return _result(
            definition,
            available=False,
            reason="RUNTIME_PROFILE_NOT_FOUND",
            message="ModelDefinition is not bound to INTERNIMAGE_RUNTIME_V1.",
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

    architecture = definition.get("definitionPayload", {}).get("architecture")
    if (
        not isinstance(architecture, dict)
        or canonical_sha256(architecture) != definition.get("architectureSignature")
        or architecture != trusted_architecture()
        or definition.get("architectureSignature") != EXPECTED_ARCHITECTURE_SIGNATURE
    ):
        return _result(
            definition,
            available=False,
            reason="ARCHITECTURE_SIGNATURE_MISMATCH",
            message="InternImage trusted architecture template signature mismatch.",
            definition_valid=False,
            runtime_available=None,
            self_check_passed=False,
        )

    prohibited_path = _has_prohibited_value(definition.get("definitionPayload", {}))
    expected_names = {str(index): name for index, name in enumerate(definition.get("classOrder", []))}
    if (
        prohibited_path is not None
        or definition.get("modelFamily") != "INTERNIMAGE"
        or definition.get("definitionType") != "MMSEG_TRUSTED_CONFIG_TEMPLATE"
        or definition.get("framework") != "MMSegmentation"
        or definition.get("frameworkVersion") != "0.27.0"
        or definition.get("classCount") != 3
        or definition.get("classOrder") != ["background", "healthy_wheat", "lodged_wheat"]
        or definition.get("ignoreIndex") != 255
        or definition.get("definitionPayload", {}).get("names") != expected_names
        or architecture.get("templateId") != TEMPLATE_ID
    ):
        message = (
            f"Checkpoint/pretrained field is forbidden: {prohibited_path}."
            if prohibited_path
            else "ModelDefinition does not match the trusted InternImage runtime contract."
        )
        return _result(
            definition,
            available=False,
            reason="DEFINITION_INVALID",
            message=message,
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
            message="InternImage runtime dependency or DCNv3 self-check failed.",
            definition_valid=True,
            runtime_available=False,
            self_check_passed=None,
        )

    try:
        model = build_model()
        state = model.state_dict()
        tensor_count = len(state)
        element_count = sum(int(tensor.numel()) for tensor in state.values())
        passed = (
            tensor_count == EXPECTED_TENSOR_COUNT
            and element_count == EXPECTED_ELEMENT_COUNT
            and int(model.decode_head.num_classes) == 3
            and int(model.auxiliary_head.num_classes) == 3
        )
    except Exception as exc:
        return _result(
            definition,
            available=False,
            reason="SELF_CHECK_FAILED",
            message=f"InternImage architecture-only build failed: {type(exc).__name__}.",
            definition_valid=True,
            runtime_available=True,
            self_check_passed=False,
        )

    return _result(
        definition,
        available=passed,
        reason="AVAILABLE" if passed else "SELF_CHECK_FAILED",
        message=(
            "InternImage runtime and architecture-only Definition self-check passed."
            if passed
            else "InternImage architecture-only build produced an unexpected state contract."
        ),
        definition_valid=True,
        runtime_available=True,
        self_check_passed=passed,
        tensor_count=tensor_count,
        element_count=element_count,
    )
