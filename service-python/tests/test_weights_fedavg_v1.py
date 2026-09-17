from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path

import torch

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.schemas.model_protocol import (
    ModelDefinition,
    WeightsFedAvgInput,
    WeightsFedAvgRequest,
)
from app.services.weights_fedavg_v1_service import WeightsFedAvgError, WeightsFedAvgV1Service
from app.services.weights_package_inspector import (
    InspectorLimits,
    WeightsPackageInspector,
    canonical_json_sha256,
    sha256_file,
)
from test_weights_package_inspector import _build_request


DEFINITION_PATH = SERVICE_ROOT / "app" / "model_definitions" / "YOLOV8N_SHEEP_V1.json"
INTERNIMAGE_DEFINITION_PATH = (
    SERVICE_ROOT / "app" / "model_definitions" / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json"
)
LAB_ROOT = Path(r"D:\workspace\federated-weights-lab\yolo\artifacts")


class WeightsFedAvgV1Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.input_root = self.root / "weights-assets"
        self.output_root = self.root / "federated-models"
        self.input_root.mkdir()
        self.output_root.mkdir()
        self.definition = ModelDefinition.model_validate_json(DEFINITION_PATH.read_text(encoding="utf-8"))
        self.service = WeightsFedAvgV1Service(self.input_root, self.output_root)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_two_client_type_safe_fedavg_and_output_inspection(self) -> None:
        left = OrderedDict([
            ("half", torch.tensor([1.0, 3.0], dtype=torch.float16)),
            ("full", torch.tensor([2.0, 6.0], dtype=torch.float32)),
            ("counter", torch.tensor(7, dtype=torch.int64)),
        ])
        right = OrderedDict([
            ("half", torch.tensor([3.0, 5.0], dtype=torch.float16)),
            ("full", torch.tensor([4.0, 8.0], dtype=torch.float32)),
            ("counter", torch.tensor(7, dtype=torch.int64)),
        ])
        result = self.service.aggregate(self._request([self._asset(1, left), self._asset(2, right)], "two"))

        self.assertTrue(result.passed)
        self.assertTrue(result.inspection and result.inspection.passed)
        payload = torch.load(result.outputWeightsPath, map_location="cpu", weights_only=True)
        self.assertEqual(torch.float32, payload["state_dict"]["half"].dtype)
        self.assertTrue(torch.equal(payload["state_dict"]["half"], torch.tensor([2.0, 4.0])))
        self.assertTrue(torch.equal(payload["state_dict"]["full"], torch.tensor([3.0, 7.0])))
        self.assertTrue(torch.equal(payload["state_dict"]["counter"], left["counter"]))
        self.assertTrue(Path(result.outputReportPath).is_file())

    def test_internimage_uses_the_same_generic_float32_fedavg(self) -> None:
        self.definition = ModelDefinition.model_validate_json(
            INTERNIMAGE_DEFINITION_PATH.read_text(encoding="utf-8")
        )
        left = OrderedDict(
            [
                ("backbone.level0.weight", torch.tensor([1.0, 3.0], dtype=torch.float32)),
                ("decode_head.weight", torch.tensor([5.0], dtype=torch.float32)),
            ]
        )
        right = OrderedDict(
            [
                ("backbone.level0.weight", torch.tensor([3.0, 7.0], dtype=torch.float32)),
                ("decode_head.weight", torch.tensor([9.0], dtype=torch.float32)),
            ]
        )

        result = self.service.aggregate(
            self._request(
                [self._asset(41, left), self._asset(42, right)],
                "internimage",
            )
        )

        output = torch.load(
            result.outputWeightsPath, map_location="cpu", weights_only=True
        )["state_dict"]
        self.assertTrue(result.passed)
        self.assertEqual("INTERNIMAGE_T_UPERNET_WHEAT_V1", result.modelDefinitionId)
        self.assertTrue(
            torch.equal(
                output["backbone.level0.weight"],
                torch.tensor([2.0, 5.0], dtype=torch.float32),
            )
        )
        self.assertTrue(
            torch.equal(output["decode_head.weight"], torch.tensor([7.0], dtype=torch.float32))
        )

    def test_single_client_is_value_identity_under_global_dtype_policy(self) -> None:
        state = OrderedDict([
            ("half", torch.tensor([1.5], dtype=torch.float16)),
            ("counter", torch.tensor(2, dtype=torch.int64)),
        ])
        result = self.service.aggregate(self._request([self._asset(1, state)], "single"))
        output = torch.load(result.outputWeightsPath, map_location="cpu", weights_only=True)["state_dict"]
        self.assertTrue(torch.equal(output["half"], state["half"].float()))
        self.assertTrue(torch.equal(output["counter"], state["counter"]))

    def test_rejects_non_floating_value_mismatch(self) -> None:
        left = OrderedDict([("counter", torch.tensor(1, dtype=torch.int64))])
        right = OrderedDict([("counter", torch.tensor(2, dtype=torch.int64))])
        with self.assertRaisesRegex(WeightsFedAvgError, "Non-floating") as raised:
            self.service.aggregate(self._request([self._asset(1, left), self._asset(2, right)], "int"))
        self.assertEqual("WEIGHTS_FEDAVG_NON_FLOATING_MISMATCH", raised.exception.code)

    def test_rejects_key_shape_and_dtype_contract_mismatch(self) -> None:
        cases = [
            (OrderedDict([("other", torch.ones(1, dtype=torch.float32))]), "key"),
            (OrderedDict([("weight", torch.ones(2, dtype=torch.float32))]), "shape"),
            (OrderedDict([("weight", torch.ones(1, dtype=torch.float16))]), "dtype"),
        ]
        reference = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        for index, (changed, label) in enumerate(cases, start=2):
            with self.subTest(label=label):
                with self.assertRaises(WeightsFedAvgError) as raised:
                    self.service.aggregate(self._request([
                        self._asset(index * 10, reference), self._asset(index * 10 + 1, changed)
                    ], label))
                self.assertEqual("WEIGHTS_FEDAVG_STATE_MISMATCH", raised.exception.code)

    def test_rejects_sha_tamper_nan_path_escape_and_duplicate_asset(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        tampered = self._asset(1, state)
        tampered.expectedSha256 = "0" * 64
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([tampered], "sha"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_TAMPERED", raised.exception.code)

        nan_asset = self._asset(2, OrderedDict([("weight", torch.tensor([float("nan")]))]))
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([nan_asset], "nan"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_INVALID", raised.exception.code)

        outside = self.root / "outside.pt"
        torch.save({"state_dict": state}, outside)
        escaped = self._asset(3, state)
        escaped.weightsPath = str(outside)
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([escaped], "escape"))
        self.assertEqual("PATH_NOT_ALLOWED", raised.exception.code)

        first = self._asset(4, state)
        duplicate = self._asset(5, state)
        duplicate.assetId = first.assetId
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([first, duplicate], "duplicate"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_INVALID", raised.exception.code)

    def test_rejects_definition_and_architecture_mismatch(self) -> None:
        state = OrderedDict([("weight", torch.ones(1, dtype=torch.float32))])
        definition_asset = self._asset(20, state)
        manifest_path = Path(definition_asset.manifestPath)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["modelDefinition"]["definitionId"] = 999
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([definition_asset], "definition"))
        self.assertEqual("WEIGHTS_FEDAVG_DEFINITION_MISMATCH", raised.exception.code)

        architecture_asset = self._asset(21, state)
        manifest_path = Path(architecture_asset.manifestPath)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["architectureSignature"] = "0" * 64
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([architecture_asset], "architecture"))
        self.assertEqual("WEIGHTS_FEDAVG_ARCHITECTURE_MISMATCH", raised.exception.code)

    def test_rejects_unsupported_dtype_infinity_and_resource_limit(self) -> None:
        unsupported = self._asset(
            30,
            OrderedDict([("weight", torch.ones(1, dtype=torch.int32))]),
        )
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([unsupported], "unsupported"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_INVALID", raised.exception.code)

        infinite = self._asset(
            31,
            OrderedDict([("weight", torch.tensor([float("inf")], dtype=torch.float32))]),
        )
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([infinite], "infinite"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_INVALID", raised.exception.code)

        limited = self._asset(
            32,
            OrderedDict([("weight", torch.ones(2, dtype=torch.float32))]),
        )
        defaults = self.service.inspector.limits
        self.service.inspector.limits = InspectorLimits(
            max_file_bytes=defaults.max_file_bytes,
            max_tensor_count=defaults.max_tensor_count,
            max_total_elements=1,
            max_single_tensor_elements=defaults.max_single_tensor_elements,
            max_tensor_dimensions=defaults.max_tensor_dimensions,
            max_key_length=defaults.max_key_length,
        )
        with self.assertRaises(WeightsFedAvgError) as raised:
            self.service.aggregate(self._request([limited], "limited"))
        self.assertEqual("WEIGHTS_FEDAVG_INPUT_INVALID", raised.exception.code)

    @unittest.skipUnless(
        (LAB_ROOT / "client_a" / "weights_only.pt").is_file()
        and (LAB_ROOT / "client_b" / "weights_only.pt").is_file()
        and (LAB_ROOT / "global" / "global_weights.pt").is_file(),
        "Frozen YOLO Phase 2 artifacts are not available.",
    )
    def test_frozen_yolo_phase2_golden_tensor_result(self) -> None:
        left = self._copied_golden_asset(101, LAB_ROOT / "client_a" / "weights_only.pt")
        right = self._copied_golden_asset(102, LAB_ROOT / "client_b" / "weights_only.pt")
        result = self.service.aggregate(self._request([left, right], "golden"))
        actual = torch.load(result.outputWeightsPath, map_location="cpu", weights_only=True)["state_dict"]
        expected = torch.load(LAB_ROOT / "global" / "global_weights.pt", map_location="cpu", weights_only=True)["state_dict"]
        self.assertEqual(list(expected.keys()), list(actual.keys()))
        self.assertTrue(all(torch.equal(expected[key], actual[key]) for key in expected))
        self.assertEqual(355, result.inspection.tensorCount)
        self.assertEqual(3_021_500, result.inspection.elementCount)

    def _request(self, inputs: list[WeightsFedAvgInput], name: str) -> WeightsFedAvgRequest:
        return WeightsFedAvgRequest(
            workflowId=10,
            trustedModelDefinition=self.definition,
            inputs=inputs,
            outputDirectory=str(self.output_root / name),
        )

    def _copied_golden_asset(self, asset_id: int, source: Path) -> WeightsFedAvgInput:
        directory = self.input_root / str(asset_id)
        directory.mkdir()
        target = directory / "weights.pt"
        shutil.copyfile(source, target)
        state = torch.load(target, map_location="cpu", weights_only=True)["state_dict"]
        return self._sidecars(asset_id, target, state)

    def _asset(self, asset_id: int, state: OrderedDict[str, torch.Tensor]) -> WeightsFedAvgInput:
        directory = self.input_root / str(asset_id)
        directory.mkdir()
        path = directory / "weights.pt"
        torch.save({"state_dict": state}, path)
        return self._sidecars(asset_id, path, state)

    def _sidecars(self, asset_id: int, path: Path, state: OrderedDict[str, torch.Tensor]) -> WeightsFedAvgInput:
        request = _build_request(path, state)
        reference = request.manifest.modelDefinition
        reference.definitionId = self.definition.definitionId
        reference.definitionSha256 = self.definition.definitionSha256
        request.descriptor.modelDefinition = reference.model_copy(deep=True)
        request.descriptor.modelFamily = self.definition.modelFamily
        request.descriptor.version = self.definition.version
        request.descriptor.variant = self.definition.variant
        request.descriptor.taskType = self.definition.taskType
        request.descriptor.framework.name = self.definition.framework
        request.descriptor.framework.version = self.definition.frameworkVersion
        request.descriptor.classes.count = self.definition.classCount
        request.descriptor.classes.order = self.definition.classOrder
        request.descriptor.classes.ignoreIndex = self.definition.ignoreIndex
        request.descriptor.parameterSemantics = self.definition.definitionPayload["parameterSemantics"]
        request.manifest.parameterSemantics = request.descriptor.parameterSemantics
        request.manifest.architectureSignature = self.definition.architectureSignature
        request.manifest.descriptorSha256 = canonical_json_sha256(request.descriptor.model_dump(mode="json"))
        inspector = WeightsPackageInspector(allowed_roots=[self.input_root])
        inspection = inspector.inspect(request)
        manifest_path = path.parent / "manifest.json"
        descriptor_path = path.parent / "descriptor.json"
        inspection_path = path.parent / "inspection-report.json"
        manifest_path.write_text(request.manifest.model_dump_json(indent=2), encoding="utf-8")
        descriptor_path.write_text(request.descriptor.model_dump_json(indent=2), encoding="utf-8")
        inspection_path.write_text(json.dumps({"passed": inspection.passed}), encoding="utf-8")
        return WeightsFedAvgInput(
            assetId=asset_id,
            uploadId=asset_id + 1000,
            weightsPath=str(path),
            expectedSha256=sha256_file(path),
            manifestPath=str(manifest_path),
            descriptorPath=str(descriptor_path),
            inspectionReportPath=str(inspection_path),
        )


if __name__ == "__main__":
    unittest.main()
