from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path
from unittest.mock import patch

import torch


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    ClassContract,
    DefinitionReference,
    DefinitionStatus,
    FrameworkSpec,
    ModelDefinition,
    ModelDescriptor,
    ModelPackageInspectRequest,
    ProtocolErrorCode,
    RuntimeProfile,
    RuntimeStatus,
    StateContract,
    WeightsFileSpec,
    WeightsPackageManifest,
    WeightsStateSummary,
)
from app.services.model_availability_service import ModelAvailabilityEvaluator
from app.services.weights_package_inspector import (
    InspectorLimits,
    WeightsPackageInspector,
    canonical_json_sha256,
    sha256_file,
    state_signatures,
)


def _dtype_counts(state: OrderedDict[str, torch.Tensor]) -> dict[str, int]:
    result: dict[str, int] = {}
    for tensor in state.values():
        name = str(tensor.dtype).removeprefix("torch.")
        result[name] = result.get(name, 0) + 1
    return dict(sorted(result.items()))


def _build_request(
    weights_path: Path,
    contract_state: OrderedDict[str, torch.Tensor],
    *,
    model_family: str = "YOLO",
    version: str = "YOLOv8",
    variant: str = "yolov8n",
    task_type: str = "DETECTION",
) -> ModelPackageInspectRequest:
    signatures = state_signatures(contract_state)
    key_count = len(contract_state)
    element_count = sum(tensor.numel() for tensor in contract_state.values())
    definition = DefinitionReference(
        definitionId="definition-test-v1",
        definitionSha256="d" * 64,
    )
    descriptor = ModelDescriptor(
        schemaVersion="1.0",
        descriptorVersion="1.0",
        modelFamily=model_family,
        version=version,
        variant=variant,
        taskType=task_type,
        framework=FrameworkSpec(name="test-framework", version="1.0"),
        modelDefinition=definition.model_copy(deep=True),
        classes=ClassContract(count=1, order=["target"], ignoreIndex=None),
        stateContract=StateContract(
            keyCount=key_count,
            tensorCount=key_count,
            elementCount=element_count,
            stateDictKeyHash=signatures["stateDictKeyHash"],
            shapeSignature=signatures["shapeSignature"],
            clientDtypeSignature=signatures["dtypeSignature"],
        ),
        parameterSemantics="EMA_SNAPSHOT",
        inputSpec={"channels": 3},
        preprocessing={},
        runtimeRequirements={"runtimeProfileId": "TEST_RUNTIME_V1"},
    )
    manifest = WeightsPackageManifest(
        schemaVersion="1.0",
        weightsFormatVersion="1.0",
        packageId="package-test-v1",
        artifactType="CLIENT_WEIGHTS",
        descriptorSha256=canonical_json_sha256(descriptor.model_dump(mode="json")),
        modelDefinition=definition,
        parameterSemantics="EMA_SNAPSHOT",
        weights=WeightsFileSpec(
            format="PYTORCH_STATE_DICT",
            wrapperKey="state_dict",
            fileName=weights_path.name,
            sizeBytes=weights_path.stat().st_size,
            sha256=sha256_file(weights_path),
        ),
        state=WeightsStateSummary(
            keyCount=key_count,
            tensorCount=key_count,
            elementCount=element_count,
            stateDictKeyHash=signatures["stateDictKeyHash"],
            shapeSignature=signatures["shapeSignature"],
            dtypeSignature=signatures["dtypeSignature"],
            dtypeCounts=_dtype_counts(contract_state),
        ),
        aggregationContribution=1.0,
        createdBy={"type": "SYNTHETIC_TEST"},
    )
    return ModelPackageInspectRequest(
        weightsPath=str(weights_path),
        manifest=manifest,
        descriptor=descriptor,
    )


def _codes(report) -> set[ProtocolErrorCode]:
    return {error.code for error in report.errors}


class _UnsafePicklePayload:
    def __reduce__(self):
        return eval, ("1 + 1",)


class WeightsPackageInspectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name) / "allowed"
        self.root.mkdir()
        self.inspector = WeightsPackageInspector(allowed_roots=[self.root])

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def _save(
        self,
        state: OrderedDict[str, object],
        *,
        extra_top_level: dict | None = None,
        name: str = "weights.pt",
    ) -> Path:
        payload = {"state_dict": state}
        if extra_top_level:
            payload.update(extra_top_level)
        path = self.root / name
        torch.save(payload, path)
        return path

    def test_yolo_like_float16_and_int64_passes(self) -> None:
        state = OrderedDict(
            [
                ("model.0.weight", torch.ones((2, 3), dtype=torch.float16)),
                ("model.0.anchor", torch.tensor([1, 2], dtype=torch.int64)),
            ]
        )
        path = self._save(state)

        report = self.inspector.inspect(_build_request(path, state))

        self.assertTrue(report.passed, report.errors)
        self.assertEqual({"float16": 1, "int64": 1}, report.dtypeCounts)
        self.assertEqual(1, report.floatingTensorCount)
        self.assertEqual(1, report.nonFloatingTensorCount)

    def test_internimage_like_float32_passes(self) -> None:
        state = OrderedDict(
            [
                ("backbone.levels.0.weight", torch.ones((4, 4), dtype=torch.float32)),
                ("decode_head.conv.weight", torch.zeros((3, 4), dtype=torch.float32)),
            ]
        )
        path = self._save(state)
        request = _build_request(
            path,
            state,
            model_family="InternImage",
            version="InternImage",
            variant="InternImage-T",
            task_type="SEMANTIC_SEGMENTATION",
        )

        report = self.inspector.inspect(request)

        self.assertTrue(report.passed, report.errors)
        self.assertEqual({"float32": 2}, report.dtypeCounts)

    def test_extra_top_level_key_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state, extra_top_level={"model": "forbidden"})
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.INVALID_TOP_LEVEL, _codes(report))

    def test_plain_dict_state_is_rejected(self) -> None:
        contract_state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self.root / "weights.pt"
        torch.save({"state_dict": {"weight": torch.ones(1, dtype=torch.float32)}}, path)
        report = self.inspector.inspect(_build_request(path, contract_state))
        self.assertIn(ProtocolErrorCode.INVALID_STATE_DICT, _codes(report))

    def test_empty_state_is_rejected(self) -> None:
        state: OrderedDict[str, torch.Tensor] = OrderedDict()
        path = self._save(state)
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.INVALID_STATE_DICT, _codes(report))

    def test_non_tensor_value_is_rejected(self) -> None:
        valid_contract = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        invalid_state = OrderedDict([("weight", "not-a-tensor")])
        path = self._save(invalid_state)
        report = self.inspector.inspect(_build_request(path, valid_contract))
        self.assertIn(ProtocolErrorCode.NON_TENSOR_VALUE, _codes(report))

    def test_sha_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.weights.sha256 = "0" * 64
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.WEIGHTS_SHA256_MISMATCH, _codes(report))

    def test_size_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.weights.sizeBytes += 1
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.WEIGHTS_SIZE_MISMATCH, _codes(report))

    def test_key_hash_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.state.stateDictKeyHash = "0" * 64
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.KEY_HASH_MISMATCH, _codes(report))

    def test_shape_signature_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.state.shapeSignature = "0" * 64
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.SHAPE_SIGNATURE_MISMATCH, _codes(report))

    def test_dtype_signature_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.state.dtypeSignature = "0" * 64
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.DTYPE_SIGNATURE_MISMATCH, _codes(report))

    def test_nan_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.tensor([float("nan")], dtype=torch.float32))])
        path = self._save(state)
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.NON_FINITE, _codes(report))
        self.assertEqual(1, report.nanElements)

    def test_positive_infinity_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.tensor([float("inf")], dtype=torch.float32))])
        path = self._save(state)
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.NON_FINITE, _codes(report))
        self.assertEqual(1, report.positiveInfElements)

    def test_negative_infinity_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.tensor([float("-inf")], dtype=torch.float32))])
        path = self._save(state)
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.NON_FINITE, _codes(report))
        self.assertEqual(1, report.negativeInfElements)

    def test_unsupported_dtype_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float64))])
        path = self._save(state)
        report = self.inspector.inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.UNSUPPORTED_DTYPE, _codes(report))

    def test_tensor_count_limit_is_enforced(self) -> None:
        state = OrderedDict(
            [("a", torch.ones(1, dtype=torch.float32)), ("b", torch.ones(1, dtype=torch.float32))]
        )
        path = self._save(state)
        limits = InspectorLimits(1024 * 1024, 1, 100, 100, 8, 512)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_total_element_limit_is_enforced(self) -> None:
        state = OrderedDict([("weight", torch.ones(3, dtype=torch.float32))])
        path = self._save(state)
        limits = InspectorLimits(1024 * 1024, 10, 2, 100, 8, 512)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_file_size_limit_is_enforced(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        limits = InspectorLimits(1, 10, 100, 100, 8, 512)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_single_tensor_element_limit_is_enforced(self) -> None:
        state = OrderedDict([("weight", torch.ones(3, dtype=torch.float32))])
        path = self._save(state)
        limits = InspectorLimits(1024 * 1024, 10, 100, 2, 8, 512)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_tensor_dimension_limit_is_enforced(self) -> None:
        state = OrderedDict([("weight", torch.ones((1, 1, 1), dtype=torch.float32))])
        path = self._save(state)
        limits = InspectorLimits(1024 * 1024, 10, 100, 100, 2, 512)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_state_key_length_limit_is_enforced(self) -> None:
        state = OrderedDict([("long-key", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        limits = InspectorLimits(1024 * 1024, 10, 100, 100, 8, 3)
        report = WeightsPackageInspector([self.root], limits).inspect(_build_request(path, state))
        self.assertIn(ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED, _codes(report))

    def test_path_outside_allowed_root_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        outside = Path(self.temp_dir.name) / "outside.pt"
        torch.save({"state_dict": state}, outside)
        report = self.inspector.inspect(_build_request(outside, state))
        self.assertIn(ProtocolErrorCode.PATH_NOT_ALLOWED, _codes(report))

    def test_parent_traversal_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        outside = Path(self.temp_dir.name) / "outside.pt"
        torch.save({"state_dict": state}, outside)
        traversal = self.root / ".." / "outside.pt"
        request = _build_request(outside, state)
        request.weightsPath = str(traversal)
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.PATH_NOT_ALLOWED, _codes(report))

    def test_symbolic_link_is_rejected_when_supported(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        target = self._save(state, name="target.pt")
        link = self.root / "link.pt"
        try:
            link.symlink_to(target)
        except OSError as exc:
            self.skipTest(f"symbolic links are unavailable in this test environment: {exc}")
        request = _build_request(target, state)
        request.weightsPath = str(link)
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.PATH_NOT_ALLOWED, _codes(report))

    def test_missing_file_is_reported(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        existing = self._save(state)
        request = _build_request(existing, state)
        request.weightsPath = str(self.root / "missing.pt")
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.WEIGHTS_FILE_NOT_FOUND, _codes(report))

    def test_descriptor_hash_mismatch_is_rejected_before_loading(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.descriptorSha256 = "0" * 64
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.DESCRIPTOR_MISMATCH, _codes(report))

    def test_definition_reference_mismatch_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.modelDefinition.definitionId = "other-definition"
        request.manifest.descriptorSha256 = canonical_json_sha256(
            request.descriptor.model_dump(mode="json")
        )
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.DEFINITION_MISMATCH, _codes(report))

    def test_unsupported_schema_version_is_rejected(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        request.manifest.schemaVersion = "2.0"
        report = self.inspector.inspect(request)
        self.assertIn(ProtocolErrorCode.UNSUPPORTED_SCHEMA_VERSION, _codes(report))

    def test_safe_loader_never_falls_back_to_weights_only_false(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        path = self._save(state)
        request = _build_request(path, state)
        with patch("app.services.weights_package_inspector.torch.load", side_effect=RuntimeError("rejected")) as load:
            report = self.inspector.inspect(request)

        self.assertIn(ProtocolErrorCode.INVALID_STATE_DICT, _codes(report))
        load.assert_called_once_with(path.resolve(), map_location="cpu", weights_only=True)

    def test_weights_only_loader_rejects_an_actual_unsafe_pickle_global(self) -> None:
        path = self.root / "unsafe-pickle.pt"
        torch.save({"state_dict": _UnsafePicklePayload()}, path)
        contract = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])

        report = self.inspector.inspect(_build_request(path, contract))

        self.assertFalse(report.passed)
        self.assertIn(ProtocolErrorCode.INVALID_STATE_DICT, _codes(report))

    def test_yolo_golden_artifact_passes_without_modification(self) -> None:
        golden = Path(
            r"D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt"
        )
        if not golden.exists():
            self.skipTest("YOLO golden artifact is not present on this workstation")

        manifest_path = golden.with_name("manifest.json")
        legacy_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        before = sha256_file(golden)
        self.assertEqual(legacy_manifest["weightsSHA256"], before)
        self.assertEqual(legacy_manifest["weightsSize"], golden.stat().st_size)
        payload = torch.load(golden, map_location="cpu", weights_only=True)
        state = payload["state_dict"]
        signatures = state_signatures(state)
        self.assertEqual(legacy_manifest["stateDictKeyHash"], signatures["stateDictKeyHash"])
        self.assertEqual(legacy_manifest["shapeSignature"], signatures["shapeSignature"])
        self.assertEqual(legacy_manifest["dtypeSignature"], signatures["dtypeSignature"])
        request = _build_request(golden, state)
        inspector = WeightsPackageInspector(allowed_roots=[golden.parents[2]])

        report = inspector.inspect(request)

        self.assertTrue(report.passed, report.errors)
        self.assertEqual(before, sha256_file(golden))


class ModelAvailabilityEvaluatorTest(unittest.TestCase):
    def _definition(self, status: DefinitionStatus = DefinitionStatus.ENABLED) -> ModelDefinition:
        return ModelDefinition(
            definitionVersion="1.0",
            definitionId="YOLOV8N_DETECTION_V1",
            code="YOLOV8N_DETECTION_V1",
            displayName="YOLOv8n Detection",
            modelFamily="YOLO",
            version="YOLOv8",
            variant="yolov8n",
            taskType="DETECTION",
            framework="Ultralytics",
            frameworkVersion="8.4.41",
            definitionType="ULTRALYTICS_DETECTION_MODEL",
            definitionPayload={"architecture": {"nc": 1}},
            classCount=1,
            classOrder=["target"],
            inputSpec={"channels": 3},
            runtimeProfileId="YOLO_RUNTIME_V1",
            architectureSignature="a" * 64,
            definitionSha256="b" * 64,
            status=status,
        )

    def test_availability_requires_all_three_real_checks(self) -> None:
        result = ModelAvailabilityEvaluator().evaluate(
            self._definition(),
            definition_valid=True,
            adapter_available=True,
            runtime_available=True,
        )
        self.assertTrue(result.available)
        self.assertEqual(AvailabilityReasonCode.AVAILABLE, result.reasonCode)

    def test_unprobed_runtime_is_not_evaluated_not_available(self) -> None:
        result = ModelAvailabilityEvaluator().evaluate(self._definition())
        self.assertFalse(result.available)
        self.assertEqual(AvailabilityReasonCode.NOT_EVALUATED, result.reasonCode)

    def test_internimage_definition_and_runtime_need_no_base_checkpoint(self) -> None:
        definition = ModelDefinition(
            definitionVersion="1.0",
            definitionId="INTERNIMAGE_T_UPERNET_V1",
            code="INTERNIMAGE_T_UPERNET_V1",
            displayName="InternImage-T UPerNet",
            modelFamily="InternImage",
            version="InternImage",
            variant="InternImage-T",
            taskType="SEMANTIC_SEGMENTATION",
            framework="MMSegmentation",
            frameworkVersion="0.27.0",
            definitionType="MMSEG_CONFIG_MODEL",
            definitionPayload={
                "configDefinition": {"model": {"type": "EncoderDecoder"}},
                "decoder": "UPerNet",
                "customModuleRequirements": ["DCNv3"],
                "trustedSource": {"repository": "registered-at-stage1b"},
            },
            classCount=3,
            classOrder=["background", "healthy_wheat", "lodged_wheat"],
            ignoreIndex=255,
            inputSpec={"channels": 3},
            runtimeProfileId="INTERNIMAGE_RUNTIME_V1",
            architectureSignature="a" * 64,
            definitionSha256="b" * 64,
            status=DefinitionStatus.DRAFT,
        )
        runtime = RuntimeProfile(
            runtimeProfileId="INTERNIMAGE_RUNTIME_V1",
            name="InternImage Runtime v1",
            runtimeType="DEDICATED_MODEL_RUNTIME",
            supportedModelFamilies=["InternImage"],
            pythonVersion="3.11",
            frameworkRequirements={
                "MMSegmentation": "0.27.0",
                "MMCV": "1.6.2",
                "MMDetection": "2.28.1",
            },
            cudaRequired=True,
            gpuRequired=True,
            adapterType="INTERNIMAGE",
            healthCheckType="RUNTIME_AND_DEFINITION_SELF_CHECK",
            status=RuntimeStatus.DISABLED,
        )

        serialized = definition.model_dump(mode="json")
        self.assertEqual("INTERNIMAGE_RUNTIME_V1", runtime.runtimeProfileId)
        self.assertNotIn("baseModelPath", serialized)
        self.assertNotIn("pretrainedModelPath", serialized)


if __name__ == "__main__":
    unittest.main()
