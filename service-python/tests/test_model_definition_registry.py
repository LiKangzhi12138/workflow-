from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    DefinitionSelfCheckResult,
    DefinitionStatus,
    ModelDefinition,
    ModelAvailabilityResult,
    RuntimeHealthResponse,
    RuntimeProbeResult,
)
from app.services.model_definition_check_service import ModelDefinitionCheckService
from app.services.model_definition_integrity_service import (
    ModelDefinitionIntegrityService,
    canonical_definition_sha256,
)
from app.services.runtime_capability_probe import RuntimeCapabilityProbe
from app.services.runtime_profile_registry import RuntimeProfileRegistry, YOLO_RUNTIME_V1
from app.services.weights_package_inspector import canonical_json_sha256
from app.services.yolo_definition_self_checker import YoloDefinitionSelfChecker
from app.services.yolo_runtime_client import YoloRuntimeClient, YoloRuntimeClientError


DEFINITION_PATH = SERVICE_ROOT / "app" / "model_definitions" / "YOLOV8N_SHEEP_V1.json"


def load_definition() -> ModelDefinition:
    return ModelDefinition.model_validate(json.loads(DEFINITION_PATH.read_text(encoding="utf-8")))


class ModelDefinitionIntegrityTest(unittest.TestCase):
    def test_real_definition_round_trip_and_signatures_are_stable(self) -> None:
        definition = load_definition()
        serialized = definition.model_dump_json()
        restored = ModelDefinition.model_validate_json(serialized)

        self.assertEqual(definition, restored)
        self.assertEqual(["sheep"], restored.classOrder)
        self.assertEqual(
            "2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a",
            canonical_json_sha256(restored.definitionPayload["architecture"]),
        )
        self.assertEqual(
            "302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2",
            canonical_definition_sha256(restored),
        )
        self.assertTrue(ModelDefinitionIntegrityService().validate(restored).valid)

    def test_definition_sha_mismatch_is_rejected(self) -> None:
        definition = load_definition()
        definition.definitionSha256 = "0" * 64
        result = ModelDefinitionIntegrityService().validate(definition)
        self.assertFalse(result.valid)
        self.assertEqual(AvailabilityReasonCode.DEFINITION_SHA_MISMATCH, result.reason_code)

    def test_architecture_signature_mismatch_is_rejected(self) -> None:
        definition = load_definition()
        definition.architectureSignature = "0" * 64
        definition.definitionSha256 = canonical_definition_sha256(definition)
        result = ModelDefinitionIntegrityService().validate(definition)
        self.assertFalse(result.valid)
        self.assertEqual(
            AvailabilityReasonCode.ARCHITECTURE_SIGNATURE_MISMATCH,
            result.reason_code,
        )

    def test_class_order_mismatch_is_rejected(self) -> None:
        definition = load_definition()
        definition.classOrder = ["other"]
        definition.definitionSha256 = canonical_definition_sha256(definition)
        result = ModelDefinitionIntegrityService().validate(definition)
        self.assertFalse(result.valid)
        self.assertEqual(AvailabilityReasonCode.CLASS_ORDER_MISMATCH, result.reason_code)


def runtime_health(**overrides) -> RuntimeHealthResponse:
    values = {
        "runtimeProfileId": "YOLO_RUNTIME_V1",
        "runtimeType": "REMOTE_CONTAINER",
        "probeContext": "CONTAINER_RUNTIME",
        "pythonVersion": "3.11.11",
        "torchAvailable": True,
        "torchVersion": "2.7.1+cu128",
        "torchvisionAvailable": True,
        "torchvisionVersion": "0.22.1+cu128",
        "ultralyticsAvailable": True,
        "ultralyticsVersion": "8.4.41",
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


class StubRuntimeClient:
    def __init__(self, health=None, check=None, error: bool = False) -> None:
        self.health_result = health or runtime_health()
        self.check_result = check
        self.error = error

    def health(self):
        if self.error:
            raise YoloRuntimeClientError("unreachable")
        return self.health_result

    def check_definition(self, definition, runtime_profile_id):
        if self.error:
            raise YoloRuntimeClientError("unreachable")
        if self.check_result is not None:
            return self.check_result
        return ModelAvailabilityResult(
            available=True,
            availabilityState="AVAILABLE",
            definitionId=definition.definitionId,
            definitionCode=definition.code,
            modelFamily=definition.modelFamily,
            variant=definition.variant,
            taskType=definition.taskType,
            adapterAvailable=True,
            runtimeAvailable=True,
            definitionValid=True,
            definitionEnabled=True,
            runtimeEvaluated=True,
            selfCheckPassed=True,
            probeContext="CONTAINER_RUNTIME",
            detectedPythonVersion="3.11.11",
            detectedFrameworkVersion="8.4.41",
            detectedTorchVersion="2.7.1+cu128",
            cudaAvailable=True,
            gpuName="Fake GPU",
            reasonCode=AvailabilityReasonCode.AVAILABLE,
            message="passed",
        )


class RuntimeCapabilityProbeTest(unittest.TestCase):
    def test_remote_runtime_health_success_is_ready(self) -> None:
        result = RuntimeCapabilityProbe(StubRuntimeClient()).probe(YOLO_RUNTIME_V1)
        self.assertTrue(result.ready)
        self.assertEqual(AvailabilityReasonCode.AVAILABLE, result.reasonCode)

    def test_runtime_unreachable_fails_closed(self) -> None:
        result = RuntimeCapabilityProbe(StubRuntimeClient(error=True)).probe(YOLO_RUNTIME_V1)
        self.assertFalse(result.evaluated)
        self.assertFalse(result.ready)
        self.assertEqual(AvailabilityReasonCode.RUNTIME_CHECK_FAILED, result.reasonCode)

    def test_framework_version_mismatch_is_unavailable(self) -> None:
        health = runtime_health(ultralyticsVersion="8.3.0", runtimeReady=False)
        result = RuntimeCapabilityProbe(StubRuntimeClient(health=health)).probe(YOLO_RUNTIME_V1)
        self.assertFalse(result.ready)
        self.assertEqual(AvailabilityReasonCode.FRAMEWORK_VERSION_MISMATCH, result.reasonCode)

    def test_cuda_required_but_unavailable_is_unavailable(self) -> None:
        health = runtime_health(cudaAvailable=False, runtimeReady=False)
        result = RuntimeCapabilityProbe(StubRuntimeClient(health=health)).probe(YOLO_RUNTIME_V1)
        self.assertFalse(result.ready)
        self.assertEqual(AvailabilityReasonCode.CUDA_NOT_AVAILABLE, result.reasonCode)

    def test_gpu_required_but_missing_is_unavailable(self) -> None:
        health = runtime_health(gpuAvailable=False, gpuName=None, runtimeReady=False)
        result = RuntimeCapabilityProbe(StubRuntimeClient(health=health)).probe(YOLO_RUNTIME_V1)
        self.assertFalse(result.ready)
        self.assertEqual(AvailabilityReasonCode.RUNTIME_NOT_READY, result.reasonCode)

    def test_host_response_is_not_evaluated(self) -> None:
        health = runtime_health(probeContext="HOST_DEV")
        result = RuntimeCapabilityProbe(StubRuntimeClient(health=health)).probe(YOLO_RUNTIME_V1)
        self.assertFalse(result.evaluated)
        self.assertIsNone(result.ready)
        self.assertEqual(AvailabilityReasonCode.NOT_EVALUATED, result.reasonCode)


class FakeHttpResponse:
    def __init__(self, payload) -> None:
        self.payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self):
        return self.payload


class FakeHttpSession:
    def __init__(self) -> None:
        self.calls = []

    def request(self, method, url, timeout, **kwargs):
        self.calls.append((method, url, timeout, kwargs))
        return FakeHttpResponse(runtime_health().model_dump(mode="json"))


class YoloRuntimeClientTest(unittest.TestCase):
    def test_health_uses_short_configured_timeouts(self) -> None:
        session = FakeHttpSession()
        client = YoloRuntimeClient(
            base_url="http://yolo-runtime:8010/",
            connect_timeout=1.5,
            read_timeout=7.0,
            session=session,
        )
        result = client.health()
        self.assertTrue(result.runtimeReady)
        self.assertEqual(
            ("GET", "http://yolo-runtime:8010/internal/runtime/health", (1.5, 7.0), {}),
            session.calls[0],
        )


class YoloDefinitionSelfCheckerTest(unittest.TestCase):
    def test_self_check_is_delegated_without_checkpoint(self) -> None:
        result = YoloDefinitionSelfChecker(StubRuntimeClient()).check(load_definition())
        self.assertTrue(result.passed, result.message)
        self.assertFalse(result.checkpointUsed)


class StubProbe:
    def __init__(self, result: RuntimeProbeResult) -> None:
        self.result = result

    def probe(self, profile, *, probe_context=None) -> RuntimeProbeResult:
        return self.result


class StubChecker:
    def __init__(self, passed: bool = True) -> None:
        self.passed = passed

    def supports(self, definition: ModelDefinition) -> bool:
        return True

    def check(self, definition: ModelDefinition) -> DefinitionSelfCheckResult:
        return DefinitionSelfCheckResult(
            passed=self.passed,
            checkpointUsed=False,
            reasonCode=(
                AvailabilityReasonCode.AVAILABLE
                if self.passed
                else AvailabilityReasonCode.SELF_CHECK_FAILED
            ),
            message="stub self-check",
        )


def ready_probe() -> RuntimeProbeResult:
    return RuntimeProbeResult(
        runtimeProfileId="YOLO_RUNTIME_V1",
        probeContext="CONTAINER_RUNTIME",
        evaluated=True,
        ready=True,
        pythonAvailable=True,
        detectedPythonVersion="3.11.9",
        torchAvailable=True,
        detectedTorchVersion="2.6.0",
        frameworkAvailable=True,
        detectedFrameworkVersion="8.4.41",
        frameworkVersionMatch=True,
        cudaAvailable=True,
        gpuName="Fake GPU",
        reasonCode=AvailabilityReasonCode.AVAILABLE,
        message="ready",
    )


class ModelDefinitionCheckServiceTest(unittest.TestCase):
    def test_missing_runtime_profile_is_rejected(self) -> None:
        service = ModelDefinitionCheckService(profile_registry=RuntimeProfileRegistry([]))
        result = service.check(load_definition(), "MISSING_RUNTIME")
        self.assertFalse(result.available)
        self.assertEqual(AvailabilityReasonCode.RUNTIME_PROFILE_NOT_FOUND, result.reasonCode)

    def test_definition_framework_version_must_match_runtime_profile(self) -> None:
        definition = load_definition()
        definition.frameworkVersion = "8.3.0"
        definition.definitionSha256 = canonical_definition_sha256(definition)
        service = ModelDefinitionCheckService()

        result = service.check(definition, "YOLO_RUNTIME_V1")

        self.assertFalse(result.available)
        self.assertEqual(AvailabilityReasonCode.FRAMEWORK_VERSION_MISMATCH, result.reasonCode)
        self.assertFalse(result.definitionValid)

    def test_disabled_definition_is_not_available(self) -> None:
        definition = load_definition()
        definition.status = DefinitionStatus.DISABLED
        service = ModelDefinitionCheckService()
        result = service.check(definition, "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertEqual(AvailabilityReasonCode.DISABLED, result.reasonCode)

    def test_build_failure_is_not_available(self) -> None:
        service = ModelDefinitionCheckService(
            capability_probe=StubProbe(ready_probe()),
            yolo_self_checker=StubChecker(False),
        )
        result = service.check(load_definition(), "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertEqual(AvailabilityReasonCode.SELF_CHECK_FAILED, result.reasonCode)

    def test_runtime_unreachable_is_unavailable_not_not_evaluated(self) -> None:
        service = ModelDefinitionCheckService(
            capability_probe=RuntimeCapabilityProbe(StubRuntimeClient(error=True)),
            yolo_self_checker=StubChecker(True),
        )
        result = service.check(load_definition(), "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertEqual("UNAVAILABLE", result.availabilityState)
        self.assertEqual(AvailabilityReasonCode.RUNTIME_CHECK_FAILED, result.reasonCode)

    def test_all_checks_must_pass_for_available(self) -> None:
        service = ModelDefinitionCheckService(
            capability_probe=StubProbe(ready_probe()),
            yolo_self_checker=StubChecker(True),
        )
        result = service.check(load_definition(), "YOLO_RUNTIME_V1")
        self.assertTrue(result.available)
        self.assertEqual("AVAILABLE", result.availabilityState)
        self.assertTrue(result.definitionValid)
        self.assertTrue(result.runtimeAvailable)
        self.assertTrue(result.selfCheckPassed)


if __name__ == "__main__":
    unittest.main()
