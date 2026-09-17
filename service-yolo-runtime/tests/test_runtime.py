from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from collections import OrderedDict
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np
import torch


SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SERVICE_ROOT.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.definition_check import check_definition
from app.runtime_probe import probe_runtime, ultralytics_config_directory_ready
from app.schemas import (
    GlobalWeightsInput,
    RuntimeHealthResponse,
    StateTensorMetadata,
    WeightsValidationRequest,
)
from app.weights_compatibility import check_weights_compatibility
from app.weights_validation import (
    WeightsValidationError,
    _run_validation,
    _safe_load,
    _strict_rebuild,
    validate_weights,
)


DEFINITION_PATH = (
    REPO_ROOT
    / "service-python"
    / "app"
    / "model_definitions"
    / "YOLOV8N_SHEEP_V1.json"
)


class FakeCuda:
    def is_available(self) -> bool:
        return True

    def get_device_name(self, index: int) -> str:
        return "Fake RTX GPU"


class FakeStride:
    def detach(self):
        return self

    def cpu(self):
        return self

    def tolist(self):
        return [8, 16, 32]


class FakeDetectionModel:
    last_call = None

    def __init__(self, *, cfg, ch, nc, verbose) -> None:
        FakeDetectionModel.last_call = {
            "cfg": cfg,
            "ch": ch,
            "nc": nc,
            "verbose": verbose,
        }
        self.model = [SimpleNamespace(nc=nc)]
        self.stride = FakeStride()
        self.names = {}

    def state_dict(self):
        return OrderedDict([
            ("weight", SimpleNamespace(shape=(2, 2), dtype="torch.float32")),
            ("counter", SimpleNamespace(shape=(1,), dtype="torch.int64")),
        ])


class FakeStrictModel:
    def __init__(self) -> None:
        self.names = {}
        self.strict = None

    def state_dict(self):
        return OrderedDict([
            ("weight", torch.zeros((2, 2), dtype=torch.float32)),
            ("counter", torch.zeros((1,), dtype=torch.int64)),
        ])

    def load_state_dict(self, state, strict):
        self.strict = strict
        return SimpleNamespace(missing_keys=[], unexpected_keys=[])

    def float(self):
        return self

    def eval(self):
        return self

    def cuda(self):
        return self


def ready_health(**overrides) -> RuntimeHealthResponse:
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
        "gpuName": "Fake RTX GPU",
        "runtimeReady": True,
        "errors": [],
        "warnings": [],
    }
    values.update(overrides)
    return RuntimeHealthResponse(**values)


class RuntimeHealthTest(unittest.TestCase):
    def test_health_schema_reports_ready_contract(self) -> None:
        torch_module = SimpleNamespace(
            __version__="2.7.1+cu128",
            cuda=FakeCuda(),
            version=SimpleNamespace(cuda="12.8"),
        )
        modules = {
            "torch": torch_module,
            "torchvision": SimpleNamespace(__version__="0.22.1+cu128"),
            "ultralytics": SimpleNamespace(__version__="8.4.41"),
        }
        with tempfile.TemporaryDirectory() as config_home:
            Path(config_home, "Ultralytics").mkdir()
            with patch.dict(os.environ, {"XDG_CONFIG_HOME": config_home}):
                with patch("app.runtime_probe.platform.python_version", return_value="3.11.11"):
                    with patch(
                        "app.runtime_probe.importlib.import_module",
                        side_effect=lambda name: modules[name],
                    ):
                        result = probe_runtime()
        self.assertTrue(result.runtimeReady, result.errors)
        self.assertEqual("CONTAINER_RUNTIME", result.probeContext)
        self.assertTrue(result.cudaAvailable)
        self.assertTrue(result.gpuAvailable)

    def test_ultralytics_config_directory_is_writable(self) -> None:
        with tempfile.TemporaryDirectory() as config_home:
            Path(config_home, "Ultralytics").mkdir()
            with patch.dict(os.environ, {"XDG_CONFIG_HOME": config_home}):
                self.assertTrue(ultralytics_config_directory_ready())


class DefinitionCheckTest(unittest.TestCase):
    def load_definition(self):
        return json.loads(DEFINITION_PATH.read_text(encoding="utf-8"))

    def test_architecture_only_build_passes_without_checkpoint(self) -> None:
        tasks = SimpleNamespace(DetectionModel=FakeDetectionModel)
        with patch("app.definition_check.probe_runtime", return_value=ready_health()):
            with patch(
                "app.definition_check.importlib.import_module",
                return_value=tasks,
            ):
                result = check_definition(self.load_definition(), "YOLO_RUNTIME_V1")
        self.assertTrue(result.available, result.message)
        self.assertTrue(result.selfCheckPassed)
        self.assertEqual(3, FakeDetectionModel.last_call["ch"])
        self.assertEqual(1, FakeDetectionModel.last_call["nc"])
        self.assertNotIn("checkpoint", FakeDetectionModel.last_call)

    def test_architecture_mismatch_fails_closed(self) -> None:
        definition = self.load_definition()
        definition["architectureSignature"] = "0" * 64
        definition_for_hash = dict(definition)
        definition_for_hash["definitionSha256"] = ""
        from app.definition_check import canonical_sha256

        definition["definitionSha256"] = canonical_sha256(definition_for_hash)
        with patch("app.definition_check.probe_runtime", return_value=ready_health()):
            result = check_definition(definition, "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertEqual("ARCHITECTURE_SIGNATURE_MISMATCH", result.reasonCode)

    def test_definition_sha_mismatch_fails_closed(self) -> None:
        definition = self.load_definition()
        definition["definitionSha256"] = "0" * 64
        with patch("app.definition_check.probe_runtime", return_value=ready_health()):
            result = check_definition(definition, "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertEqual("DEFINITION_SHA_MISMATCH", result.reasonCode)

    def test_runtime_not_ready_fails_closed(self) -> None:
        health = ready_health(
            cudaAvailable=False,
            gpuAvailable=False,
            runtimeReady=False,
            errors=["CUDA unavailable"],
        )
        with patch("app.definition_check.probe_runtime", return_value=health):
            result = check_definition(self.load_definition(), "YOLO_RUNTIME_V1")
        self.assertFalse(result.available)
        self.assertFalse(result.runtimeAvailable)
        self.assertEqual("RUNTIME_NOT_READY", result.reasonCode)

    def test_weights_state_contract_passes_with_float16_client_snapshot(self) -> None:
        state = [
            StateTensorMetadata(key="weight", shape=[2, 2], dtype="float16"),
            StateTensorMetadata(key="counter", shape=[1], dtype="int64"),
        ]
        available = SimpleNamespace(available=True)
        with patch("app.weights_compatibility.check_definition", return_value=available), patch(
            "app.weights_compatibility.importlib.import_module",
            return_value=SimpleNamespace(DetectionModel=FakeDetectionModel),
        ):
            result = check_weights_compatibility(self.load_definition(), "YOLO_RUNTIME_V1", state)
        self.assertTrue(result.compatible, result.message)

    def test_weights_state_contract_rejects_missing_key_and_shape_mismatch(self) -> None:
        available = SimpleNamespace(available=True)
        with patch("app.weights_compatibility.check_definition", return_value=available), patch(
            "app.weights_compatibility.importlib.import_module",
            return_value=SimpleNamespace(DetectionModel=FakeDetectionModel),
        ):
            missing = check_weights_compatibility(
                self.load_definition(),
                "YOLO_RUNTIME_V1",
                [StateTensorMetadata(key="weight", shape=[2, 2], dtype="float32")],
            )
            wrong_shape = check_weights_compatibility(
                self.load_definition(),
                "YOLO_RUNTIME_V1",
                [
                    StateTensorMetadata(key="weight", shape=[3, 2], dtype="float32"),
                    StateTensorMetadata(key="counter", shape=[1], dtype="int64"),
                ],
            )
        self.assertFalse(missing.compatible)
        self.assertEqual("WEIGHTS_KEY_MISMATCH", missing.reasonCode)
        self.assertFalse(wrong_shape.compatible)
        self.assertEqual("WEIGHTS_SHAPE_MISMATCH", wrong_shape.reasonCode)

    def test_weights_state_contract_rejects_extra_key_order_and_dtype_mismatch(self) -> None:
        available = SimpleNamespace(available=True)
        with patch("app.weights_compatibility.check_definition", return_value=available), patch(
            "app.weights_compatibility.importlib.import_module",
            return_value=SimpleNamespace(DetectionModel=FakeDetectionModel),
        ):
            extra = check_weights_compatibility(
                self.load_definition(),
                "YOLO_RUNTIME_V1",
                [
                    StateTensorMetadata(key="weight", shape=[2, 2], dtype="float32"),
                    StateTensorMetadata(key="counter", shape=[1], dtype="int64"),
                    StateTensorMetadata(key="extra", shape=[1], dtype="float32"),
                ],
            )
            wrong_order = check_weights_compatibility(
                self.load_definition(),
                "YOLO_RUNTIME_V1",
                [
                    StateTensorMetadata(key="counter", shape=[1], dtype="int64"),
                    StateTensorMetadata(key="weight", shape=[2, 2], dtype="float32"),
                ],
            )
            wrong_dtype = check_weights_compatibility(
                self.load_definition(),
                "YOLO_RUNTIME_V1",
                [
                    StateTensorMetadata(key="weight", shape=[2, 2], dtype="float32"),
                    StateTensorMetadata(key="counter", shape=[1], dtype="float32"),
                ],
            )
        self.assertEqual("WEIGHTS_KEY_MISMATCH", extra.reasonCode)
        self.assertEqual("WEIGHTS_KEY_MISMATCH", wrong_order.reasonCode)
        self.assertEqual("WEIGHTS_DTYPE_UNSUPPORTED", wrong_dtype.reasonCode)


class WeightsValidationTest(unittest.TestCase):
    def load_definition(self):
        return json.loads(DEFINITION_PATH.read_text(encoding="utf-8"))

    def request(self) -> WeightsValidationRequest:
        return WeightsValidationRequest(
            workflowId=37,
            jobId="job-1",
            modelDefinition=self.load_definition(),
            runtimeProfileId="YOLO_RUNTIME_V1",
            globalWeights=GlobalWeightsInput(
                assetId=77,
                weightsPath="/storage/global_weights.pt",
                expectedSha256="a" * 64,
                expectedTensorCount=2,
                expectedElementCount=5,
                expectedStateDictKeyHash="b" * 64,
                expectedShapeSignature="c" * 64,
                expectedDtypeSignature="d" * 64,
            ),
            datasetPath="/storage/dataset",
            sampleOutputDirectory="/storage/samples/job-1",
            algorithmType="YOLOv8",
        )

    def test_safe_loader_uses_weights_only_without_fallback(self) -> None:
        state = OrderedDict([("weight", torch.ones(1))])
        fake_torch = SimpleNamespace(
            Tensor=torch.Tensor,
            isfinite=torch.isfinite,
            load=Mock(return_value={"state_dict": state}),
        )
        with patch("app.weights_validation.importlib.import_module", return_value=fake_torch):
            loaded = _safe_load(Path("weights.pt"))
        self.assertIs(state, loaded)
        fake_torch.load.assert_called_once_with(
            Path("weights.pt"), map_location="cpu", weights_only=True
        )

    def test_safe_loader_does_not_retry_unsafe_pickle(self) -> None:
        fake_torch = SimpleNamespace(
            Tensor=torch.Tensor,
            isfinite=torch.isfinite,
            load=Mock(side_effect=RuntimeError("unsafe pickle rejected")),
        )
        with patch("app.weights_validation.importlib.import_module", return_value=fake_torch):
            with self.assertRaises(RuntimeError):
                _safe_load(Path("weights.pt"))
        self.assertEqual(1, fake_torch.load.call_count)
        self.assertTrue(fake_torch.load.call_args.kwargs["weights_only"])

    def test_safe_loader_rejects_non_finite_tensor(self) -> None:
        state = OrderedDict([("weight", torch.tensor([float("nan")]))])
        fake_torch = SimpleNamespace(
            Tensor=torch.Tensor,
            isfinite=torch.isfinite,
            load=Mock(return_value={"state_dict": state}),
        )
        with patch("app.weights_validation.importlib.import_module", return_value=fake_torch):
            with self.assertRaises(WeightsValidationError) as error:
                _safe_load(Path("weights.pt"))
        self.assertEqual("WEIGHTS_VALIDATION_NON_FINITE", error.exception.code)

    def test_strict_rebuild_accepts_global_float32_and_uses_strict_true(self) -> None:
        model = FakeStrictModel()
        state = OrderedDict([
            ("weight", torch.ones((2, 2), dtype=torch.float32)),
            ("counter", torch.zeros((1,), dtype=torch.int64)),
        ])
        with patch("app.weights_validation.build_detection_model", return_value=(model, FakeStrictModel)):
            rebuilt, key_count = _strict_rebuild(self.load_definition(), state)
        self.assertIs(model, rebuilt)
        self.assertEqual(2, key_count)
        self.assertTrue(model.strict)

    def test_strict_rebuild_rejects_missing_unexpected_and_shape_mismatch(self) -> None:
        cases = [
            OrderedDict([("weight", torch.ones((2, 2)))]),
            OrderedDict([
                ("weight", torch.ones((2, 2))),
                ("counter", torch.zeros((1,), dtype=torch.int64)),
                ("extra", torch.ones(1)),
            ]),
            OrderedDict([
                ("weight", torch.ones((3, 2))),
                ("counter", torch.zeros((1,), dtype=torch.int64)),
            ]),
        ]
        expected_codes = [
            "WEIGHTS_VALIDATION_MISSING_KEY",
            "WEIGHTS_VALIDATION_UNEXPECTED_KEY",
            "WEIGHTS_VALIDATION_SHAPE_MISMATCH",
        ]
        for state, expected_code in zip(cases, expected_codes):
            with self.subTest(expected_code=expected_code), patch(
                "app.weights_validation.build_detection_model",
                return_value=(FakeStrictModel(), FakeStrictModel),
            ):
                with self.assertRaises(WeightsValidationError) as error:
                    _strict_rebuild(self.load_definition(), state)
                self.assertEqual(expected_code, error.exception.code)

    def test_inference_path_returns_rendered_sample_without_checkpoint(self) -> None:
        class FakeBoxes:
            xyxy = torch.tensor([[1.0, 2.0, 10.0, 12.0]])
            conf = torch.tensor([0.9])
            cls = torch.tensor([0.0])

        class FakeResult:
            orig_shape = (20, 30)
            boxes = FakeBoxes()

            def plot(self):
                return np.zeros((20, 30, 3), dtype=np.uint8)

        class FakePredictor:
            def __init__(self, overrides):
                self.overrides = overrides

            def __call__(self, *, source, model, stream):
                return [FakeResult() for _ in source]

        with tempfile.TemporaryDirectory() as root:
            dataset = Path(root)
            (dataset / "images").mkdir()
            (dataset / "images" / "sample.jpg").write_bytes(b"image")
            detect = SimpleNamespace(DetectionPredictor=FakePredictor)
            cv2 = SimpleNamespace(
                imencode=lambda extension, image: (True, np.asarray([1, 2, 3], dtype=np.uint8))
            )
            with patch(
                "app.weights_validation.importlib.import_module",
                side_effect=lambda name: detect if name == "ultralytics.models.yolo.detect" else cv2,
            ):
                result = _run_validation(FakeStrictModel(), dataset, ["sheep"])
        self.assertEqual(1, result["total_images"])
        self.assertEqual(1, result["sample_results"][0]["prediction_count"])
        self.assertIn("rendered_image_base64", result["sample_results"][0])

    def test_full_validation_returns_checkpoint_free_evidence(self) -> None:
        request = self.request()
        state = OrderedDict([
            ("weight", torch.ones((2, 2))),
            ("counter", torch.zeros((1,), dtype=torch.int64)),
        ])
        validation = {
            "map50": 0.9,
            "map50_95": 0.5,
            "precision": 0.8,
            "recall": 0.7,
            "accuracy": 0.75,
            "total_images": 1,
            "crop_detections": 0,
            "livestock_detections": 1,
            "per_class_results": {},
            "sample_results": [],
            "visualization_mode": "METRICS_ONLY",
            "has_annotated_images": False,
            "fallback": False,
            "fallback_reason": None,
            "error": None,
        }
        signatures = {
            "stateDictKeyHash": request.globalWeights.expectedStateDictKeyHash,
            "shapeSignature": request.globalWeights.expectedShapeSignature,
            "dtypeSignature": request.globalWeights.expectedDtypeSignature,
        }
        with patch("app.weights_validation.check_definition", return_value=SimpleNamespace(
            available=True, reasonCode="AVAILABLE", message="ok"
        )), patch("app.weights_validation._controlled_path", side_effect=[Path("weights.pt"), Path("dataset")]), patch(
            "app.weights_validation._sha256", return_value=request.globalWeights.expectedSha256
        ), patch("app.weights_validation._safe_load", return_value=state), patch(
            "app.weights_validation._state_signatures", return_value=signatures
        ), patch("app.weights_validation._strict_rebuild", return_value=(FakeStrictModel(), 2)), patch(
            "app.weights_validation._run_validation", return_value=validation
        ):
            result = validate_weights(request)
        self.assertTrue(result.passed)
        self.assertTrue(result.reconstruction["weightsOnly"])
        self.assertFalse(result.reconstruction["checkpointUsed"])
        self.assertFalse(result.reconstruction["baseCheckpointUsed"])
        self.assertTrue(result.reconstruction["strictLoad"])
        evidence = result.validationEvidence
        self.assertEqual("WEIGHTS_PROTOCOL_V1", evidence["validationMode"])
        self.assertEqual(request.globalWeights.assetId, evidence["globalWeightsAssetId"])
        self.assertEqual(request.modelDefinition["definitionId"], evidence["modelDefinitionId"])
        self.assertEqual(request.globalWeights.expectedSha256, evidence["globalWeightsSha256"])
        self.assertEqual("YOLO_RUNTIME_V1", evidence["runtimeProfileId"])
        self.assertTrue(evidence["weightsOnly"])
        self.assertFalse(evidence["checkpointUsed"])
        self.assertFalse(evidence["baseCheckpointUsed"])
        self.assertEqual("DEFINITION_PLUS_STATE_DICT", evidence["reconstructionMode"])
        self.assertTrue(evidence["strictLoad"])
        self.assertEqual(0, evidence["missingKeyCount"])
        self.assertEqual(0, evidence["unexpectedKeyCount"])
        self.assertTrue(evidence["validationCompleted"])

    def test_full_validation_rejects_sha_mismatch_before_safe_load(self) -> None:
        request = self.request()
        with patch("app.weights_validation.check_definition", return_value=SimpleNamespace(
            available=True, reasonCode="AVAILABLE", message="ok"
        )), patch("app.weights_validation._controlled_path", side_effect=[Path("weights.pt"), Path("dataset")]), patch(
            "app.weights_validation._sha256", return_value="f" * 64
        ), patch("app.weights_validation._safe_load") as safe_load:
            with self.assertRaises(WeightsValidationError) as error:
                validate_weights(request)
        self.assertEqual("WEIGHTS_VALIDATION_SHA_MISMATCH", error.exception.code)
        safe_load.assert_not_called()


if __name__ == "__main__":
    unittest.main()
