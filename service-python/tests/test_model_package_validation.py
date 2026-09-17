from __future__ import annotations

import sys
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path

import torch

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.schemas.model_protocol import ModelDefinition, ModelPackageValidationRequest, RuntimeWeightsCompatibilityResult
from app.services.model_package_validation_service import ModelPackageValidationService
from app.services.runtime_client_registry import RuntimeClientRegistry
from app.services.weights_package_inspector import WeightsPackageInspector, canonical_json_sha256
from test_weights_package_inspector import _build_request

DEFINITION_PATH = SERVICE_ROOT / "app" / "model_definitions" / "YOLOV8N_SHEEP_V1.json"
INTERNIMAGE_DEFINITION_PATH = (
    SERVICE_ROOT / "app" / "model_definitions" / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json"
)


class StubRuntimeClient:
    def __init__(self, compatible: bool = True, reason: str = "AVAILABLE") -> None:
        self.compatible = compatible
        self.reason = reason
        self.requests = []

    def check_weights_compatibility(self, request):
        self.requests.append(request)
        return RuntimeWeightsCompatibilityResult(
            compatible=self.compatible,
            reasonCode=self.reason,
            message="stub",
            expectedTensorCount=len(request.state),
            actualTensorCount=len(request.state),
        )


class ModelPackageValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.definition = ModelDefinition.model_validate_json(DEFINITION_PATH.read_text(encoding="utf-8"))
        self.internimage_definition = ModelDefinition.model_validate_json(
            INTERNIMAGE_DEFINITION_PATH.read_text(encoding="utf-8")
        )
        self.state = OrderedDict([
            ("weight", torch.ones((2, 2), dtype=torch.float16)),
            ("counter", torch.ones(1, dtype=torch.int64)),
        ])
        self.path = self.root / "weights.pt"
        torch.save({"state_dict": self.state}, self.path)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def request(self, definition: ModelDefinition | None = None) -> ModelPackageValidationRequest:
        definition = definition or self.definition
        inspect_request = _build_request(self.path, self.state)
        reference = inspect_request.manifest.modelDefinition
        reference.definitionId = definition.definitionId
        reference.definitionSha256 = definition.definitionSha256
        inspect_request.descriptor.modelDefinition = reference.model_copy(deep=True)
        inspect_request.descriptor.modelFamily = definition.modelFamily
        inspect_request.descriptor.version = definition.version
        inspect_request.descriptor.variant = definition.variant
        inspect_request.descriptor.taskType = definition.taskType
        inspect_request.descriptor.framework.name = definition.framework
        inspect_request.descriptor.framework.version = definition.frameworkVersion
        inspect_request.descriptor.classes.count = definition.classCount
        inspect_request.descriptor.classes.order = definition.classOrder
        inspect_request.descriptor.classes.ignoreIndex = definition.ignoreIndex
        inspect_request.descriptor.parameterSemantics = definition.definitionPayload[
            "parameterSemantics"
        ]
        inspect_request.manifest.parameterSemantics = inspect_request.descriptor.parameterSemantics
        inspect_request.manifest.architectureSignature = definition.architectureSignature
        inspect_request.manifest.descriptorSha256 = canonical_json_sha256(
            inspect_request.descriptor.model_dump(mode="json")
        )
        return ModelPackageValidationRequest(
            weightsPath=str(self.path),
            manifest=inspect_request.manifest,
            descriptor=inspect_request.descriptor,
            trustedModelDefinition=definition,
        )

    def service(self, runtime=None, client_registry=None) -> ModelPackageValidationService:
        if runtime is None and client_registry is None:
            runtime = StubRuntimeClient()
        return ModelPackageValidationService(
            inspector=WeightsPackageInspector(allowed_roots=[self.root]),
            runtime_client=runtime,
            client_registry=client_registry,
        )

    def test_valid_package_uses_trusted_definition_and_passes(self) -> None:
        runtime = StubRuntimeClient()
        result = self.service(runtime).validate(self.request())
        self.assertTrue(result.passed, result.message)
        self.assertEqual(self.definition.definitionId, runtime.requests[0].modelDefinition.definitionId)

    def test_internimage_package_dispatches_by_trusted_runtime_profile(self) -> None:
        internimage_runtime = StubRuntimeClient()
        yolo_runtime = StubRuntimeClient()
        registry = RuntimeClientRegistry(
            {
                "YOLO_RUNTIME_V1": yolo_runtime,
                "INTERNIMAGE_RUNTIME_V1": internimage_runtime,
            }
        )

        result = self.service(client_registry=registry).validate(
            self.request(self.internimage_definition)
        )

        self.assertTrue(result.passed, result.message)
        self.assertEqual([], yolo_runtime.requests)
        self.assertEqual(1, len(internimage_runtime.requests))
        runtime_request = internimage_runtime.requests[0]
        self.assertEqual("INTERNIMAGE_RUNTIME_V1", runtime_request.runtimeProfileId)
        self.assertEqual(
            "INTERNIMAGE_T_UPERNET_WHEAT_V1",
            runtime_request.modelDefinition.definitionId,
        )

    def test_internimage_claim_cannot_override_workflow_trusted_definition(self) -> None:
        request = self.request(self.internimage_definition)
        request.trustedModelDefinition = self.definition
        runtime = StubRuntimeClient()

        result = self.service(runtime).validate(request)

        self.assertFalse(result.passed)
        self.assertEqual("WEIGHTS_DEFINITION_MISMATCH", result.reasonCode)
        self.assertEqual([], runtime.requests)

    def test_manifest_definition_mismatch_is_rejected(self) -> None:
        request = self.request()
        request.manifest.modelDefinition.definitionId = "OTHER"
        result = self.service().validate(request)
        self.assertFalse(result.passed)
        self.assertEqual("WEIGHTS_DEFINITION_MISMATCH", result.reasonCode)

    def test_architecture_signature_mismatch_is_rejected(self) -> None:
        request = self.request()
        request.manifest.architectureSignature = "0" * 64
        result = self.service().validate(request)
        self.assertFalse(result.passed)
        self.assertEqual("WEIGHTS_ARCHITECTURE_MISMATCH", result.reasonCode)

    def test_runtime_key_mismatch_is_rejected(self) -> None:
        result = self.service(StubRuntimeClient(False, "WEIGHTS_KEY_MISMATCH")).validate(self.request())
        self.assertFalse(result.passed)
        self.assertEqual("WEIGHTS_KEY_MISMATCH", result.reasonCode)

    def test_sha_mismatch_fails_before_runtime(self) -> None:
        request = self.request()
        request.manifest.weights.sha256 = "0" * 64
        runtime = StubRuntimeClient()
        result = self.service(runtime).validate(request)
        self.assertFalse(result.passed)
        self.assertEqual("WEIGHTS_SHA_MISMATCH", result.reasonCode)
        self.assertEqual([], runtime.requests)


if __name__ == "__main__":
    unittest.main()
