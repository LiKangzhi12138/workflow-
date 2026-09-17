from __future__ import annotations

import json
import sys
from pathlib import Path


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    InternImageModelAvailabilityResult,
    ModelAvailabilityResult,
    ModelDefinition,
    RuntimeHealthResponse,
    RuntimeWeightsCompatibilityRequest,
    StateTensorMetadata,
)
from app.services.internimage_runtime_client import (
    InternImageRuntimeClient,
    InternImageRuntimeClientError,
)
from app.services.model_definition_check_service import ModelDefinitionCheckService
from app.services.runtime_capability_probe import RuntimeCapabilityProbe
from app.services.runtime_client_registry import RuntimeClientRegistry
from app.services.runtime_definition_self_checker import RuntimeDefinitionSelfChecker
from app.services.runtime_profile_registry import INTERNIMAGE_RUNTIME_V1, RuntimeProfileRegistry, YOLO_RUNTIME_V1


DEFINITION_PATH = SERVICE_ROOT / "app" / "model_definitions" / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json"


def definition() -> ModelDefinition:
    return ModelDefinition.model_validate(json.loads(DEFINITION_PATH.read_text(encoding="utf-8")))


def health(profile_id: str = "INTERNIMAGE_RUNTIME_V1", **overrides) -> RuntimeHealthResponse:
    values = {
        "runtimeProfileId": profile_id,
        "runtimeType": "REMOTE_CONTAINER",
        "probeContext": "CONTAINER_RUNTIME",
        "pythonVersion": "3.11.13",
        "torchAvailable": True,
        "torchVersion": "2.7.1+cu128",
        "torchvisionAvailable": True,
        "torchvisionVersion": "0.22.1+cu128",
        "ultralyticsAvailable": profile_id == "YOLO_RUNTIME_V1",
        "ultralyticsVersion": "8.4.41" if profile_id == "YOLO_RUNTIME_V1" else None,
        "mmcvAvailable": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "mmcvVersion": "1.6.2" if profile_id == "INTERNIMAGE_RUNTIME_V1" else None,
        "mmengineAvailable": False,
        "mmengineVersion": None,
        "mmsegAvailable": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "mmsegVersion": "0.27.0" if profile_id == "INTERNIMAGE_RUNTIME_V1" else None,
        "internimageAvailable": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "internimageVersion": "31c962dc6c1ceb23e580772f7daaa6944694fbe6" if profile_id == "INTERNIMAGE_RUNTIME_V1" else None,
        "python311CompatibilityPatch": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "dcnv3Available": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "dcnv3Version": "1.0" if profile_id == "INTERNIMAGE_RUNTIME_V1" else None,
        "dcnv3SelfCheckPassed": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "minimalForwardCheckPassed": profile_id == "INTERNIMAGE_RUNTIME_V1",
        "cudaAvailable": True,
        "cudaBuildVersion": "12.8",
        "gpuAvailable": True,
        "gpuName": "Fake GPU",
        "runtimeReady": True,
        "errors": [],
        "warnings": [],
    }
    values.update(overrides)
    return RuntimeHealthResponse(**values)


def runtime_definition_response(**overrides) -> dict:
    value = definition()
    values = {
        "available": True,
        "availabilityState": "AVAILABLE",
        "definitionId": value.definitionId,
        "definitionCode": value.code,
        "modelFamily": value.modelFamily,
        "variant": value.variant,
        "taskType": value.taskType,
        "adapterAvailable": True,
        "runtimeAvailable": True,
        "definitionValid": True,
        "definitionEnabled": True,
        "runtimeEvaluated": True,
        "selfCheckPassed": True,
        "probeContext": "CONTAINER_RUNTIME",
        "detectedPythonVersion": "3.11.13",
        "detectedFrameworkVersion": "0.27.0",
        "detectedTorchVersion": "2.7.1+cu128",
        "cudaAvailable": True,
        "gpuName": "Fake GPU",
        "reasonCode": "AVAILABLE",
        "message": "InternImage runtime and architecture-only Definition self-check passed.",
        "checkpointUsed": False,
        "baseCheckpointUsed": False,
        "pretrainedUsed": False,
        "constructionMode": "TRUSTED_CONFIG_TEMPLATE",
        "tensorCount": 728,
        "elementCount": 58_963_958,
    }
    values.update(overrides)
    return values


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self.payload


class RuntimeSession:
    def __init__(
        self,
        definition_payload: dict | None = None,
        compatibility_payload: dict | None = None,
    ) -> None:
        self.definition_payload = definition_payload or runtime_definition_response()
        self.compatibility_payload = compatibility_payload or {
            "compatible": True,
            "reasonCode": "AVAILABLE",
            "message": "Weights state contract matches.",
            "expectedTensorCount": 728,
            "actualTensorCount": 728,
            "expectedElementCount": 58_963_958,
            "actualElementCount": 58_963_958,
            "missingKeyCount": 0,
            "unexpectedKeyCount": 0,
            "shapeMismatchCount": 0,
            "dtypeMismatchCount": 0,
        }

    def request(self, method, url, **_kwargs):
        if method == "GET" and url.endswith("/internal/runtime/health"):
            return FakeResponse(health().model_dump(mode="json"))
        if method == "POST" and url.endswith("/internal/model-definitions/check"):
            return FakeResponse(self.definition_payload)
        if method == "POST" and url.endswith(
            "/internal/model-definitions/weights-compatibility"
        ):
            return FakeResponse(self.compatibility_payload)
        raise AssertionError(f"Unexpected Runtime request: {method} {url}")


class StubClient:
    def __init__(self, profile_id: str, *, unavailable: bool = False) -> None:
        self.profile_id = profile_id
        self.unavailable = unavailable

    def health(self):
        if self.unavailable:
            raise InternImageRuntimeClientError("unreachable")
        return health(self.profile_id)

    def check_definition(self, value, runtime_profile_id):
        if self.unavailable:
            raise InternImageRuntimeClientError("unreachable")
        return ModelAvailabilityResult(
            available=True,
            availabilityState="AVAILABLE",
            definitionId=value.definitionId,
            definitionCode=value.code,
            modelFamily=value.modelFamily,
            variant=value.variant,
            taskType=value.taskType,
            adapterAvailable=True,
            runtimeAvailable=True,
            definitionValid=True,
            definitionEnabled=True,
            runtimeEvaluated=True,
            selfCheckPassed=True,
            probeContext="CONTAINER_RUNTIME",
            detectedPythonVersion="3.11.13",
            detectedFrameworkVersion="0.27.0",
            detectedTorchVersion="2.7.1+cu128",
            cudaAvailable=True,
            gpuName="Fake GPU",
            reasonCode=AvailabilityReasonCode.AVAILABLE,
            message="passed",
        )


def service_for(client: StubClient) -> ModelDefinitionCheckService:
    clients = RuntimeClientRegistry({"INTERNIMAGE_RUNTIME_V1": client})
    return ModelDefinitionCheckService(
        profile_registry=RuntimeProfileRegistry([INTERNIMAGE_RUNTIME_V1]),
        capability_probe=RuntimeCapabilityProbe(client_registry=clients),
        definition_self_checker=RuntimeDefinitionSelfChecker(clients),
    )


def test_internimage_runtime_profile_uses_verified_dependency_contract():
    requirements = INTERNIMAGE_RUNTIME_V1.frameworkRequirements
    assert requirements["MMSegmentation"] == "0.27.0"
    assert requirements["MMCV"] == "1.6.2"
    assert requirements["PyTorch"] == "2.7.1"
    assert requirements["CUDA"] == "12.8"
    assert requirements["DCNv3"] == "1.0"


def test_internimage_definition_routes_and_becomes_available():
    result = service_for(StubClient("INTERNIMAGE_RUNTIME_V1")).check(
        definition(), "INTERNIMAGE_RUNTIME_V1"
    )
    assert result.available is True
    assert result.selfCheckPassed is True
    assert result.detectedFrameworkVersion == "0.27.0"


def test_real_internimage_definition_response_schema_parses_all_build_evidence():
    result = InternImageModelAvailabilityResult.model_validate(runtime_definition_response())

    assert result.available is True
    assert result.selfCheckPassed is True
    assert result.checkpointUsed is False
    assert result.baseCheckpointUsed is False
    assert result.pretrainedUsed is False
    assert result.constructionMode == "TRUSTED_CONFIG_TEMPLATE"
    assert result.tensorCount == 728
    assert result.elementCount == 58_963_958


def test_real_runtime_client_response_remains_available_through_unified_check():
    client = InternImageRuntimeClient(
        base_url="http://internimage-runtime:8020",
        session=RuntimeSession(),
    )

    result = service_for(client).check(definition(), "INTERNIMAGE_RUNTIME_V1")

    assert result.available is True
    assert result.availabilityState == "AVAILABLE"
    assert result.selfCheckPassed is True


def test_internimage_runtime_client_parses_expected_state_compatibility_evidence():
    client = InternImageRuntimeClient(
        base_url="http://internimage-runtime:8020",
        session=RuntimeSession(),
    )
    request = RuntimeWeightsCompatibilityRequest(
        modelDefinition=definition(),
        runtimeProfileId="INTERNIMAGE_RUNTIME_V1",
        state=[StateTensorMetadata(key="synthetic", shape=[1], dtype="float32")],
    )

    result = client.check_weights_compatibility(request)

    assert result.compatible is True
    assert result.expectedTensorCount == 728
    assert result.actualTensorCount == 728
    assert result.expectedElementCount == 58_963_958
    assert result.actualElementCount == 58_963_958


def test_runtime_response_schema_drift_still_fails_closed(monkeypatch):
    payload = runtime_definition_response()
    payload.pop("checkpointUsed")
    payload["tensorCount"] = {"invalid": 728}
    warnings = []
    monkeypatch.setattr(
        "app.services.internimage_runtime_client.logger.warning",
        lambda *args: warnings.append(args),
    )
    client = InternImageRuntimeClient(
        base_url="http://internimage-runtime:8020",
        session=RuntimeSession(payload),
    )

    result = service_for(client).check(definition(), "INTERNIMAGE_RUNTIME_V1")

    assert result.available is False
    assert result.reasonCode == AvailabilityReasonCode.RUNTIME_CHECK_FAILED
    assert warnings
    logged = str(warnings)
    assert "checkpointUsed" in logged
    assert "tensorCount" in logged
    assert "invalid" not in logged


def test_internimage_runtime_unavailable_fails_closed():
    result = service_for(StubClient("INTERNIMAGE_RUNTIME_V1", unavailable=True)).check(
        definition(), "INTERNIMAGE_RUNTIME_V1"
    )
    assert result.available is False
    assert result.reasonCode == AvailabilityReasonCode.RUNTIME_CHECK_FAILED


def test_unknown_runtime_profile_is_rejected():
    result = ModelDefinitionCheckService(
        profile_registry=RuntimeProfileRegistry([YOLO_RUNTIME_V1])
    ).check(definition(), "UNKNOWN_RUNTIME")
    assert result.available is False
    assert result.reasonCode == AvailabilityReasonCode.RUNTIME_PROFILE_NOT_FOUND


def test_internimage_failure_does_not_change_yolo_profile_registration():
    registry = RuntimeProfileRegistry([YOLO_RUNTIME_V1, INTERNIMAGE_RUNTIME_V1])
    assert registry.get("YOLO_RUNTIME_V1") == YOLO_RUNTIME_V1
    assert registry.get("INTERNIMAGE_RUNTIME_V1") == INTERNIMAGE_RUNTIME_V1
