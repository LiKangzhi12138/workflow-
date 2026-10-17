from __future__ import annotations

import hashlib
import json
import logging
import sys
from collections import OrderedDict
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest
import torch
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app import weights_validation as validation
from app.schemas import WeightsValidationRequest
from app.segmentation_metrics import ConfusionMetrics, SegmentationMetricError


def test_ground_truth_metrics_exclude_ignore_and_absent_classes():
    metrics = ConfusionMetrics(["background", "healthy_wheat", "lodged_wheat"], 255)
    metrics.add(np.array([[0, 1], [1, 2]], dtype=np.uint8),
                np.array([[0, 1], [2, 255]], dtype=np.uint8))
    result = metrics.result()
    assert result["validPixelCount"] == 3
    assert result["aAcc"] == pytest.approx(2 / 3)
    assert result["mAcc"] == pytest.approx(2 / 3)
    assert result["mIoU"] == pytest.approx((1 + 0.5 + 0) / 3)
    assert [item["iou"] for item in result["perClass"]] == [1, 0.5, 0]
    absent = ConfusionMetrics(["background", "wheat"], 255)
    absent.add(np.zeros((1, 1), dtype=np.uint8), np.zeros((1, 1), dtype=np.uint8))
    assert absent.result()["perClass"][1]["iou"] is None
    with pytest.raises(SegmentationMetricError):
        metrics.add(np.zeros((1, 1), dtype=np.uint8), np.array([[17]], dtype=np.uint8))


def dataset(tmp_path: Path, *, label: int = 1, mask: bool = True) -> Path:
    root = tmp_path / "datasets" / "dataset-1"
    (root / "images").mkdir(parents=True, exist_ok=True)
    (root / "masks").mkdir(exist_ok=True)
    cv2.imwrite(str(root / "images" / "one.jpg"), np.zeros((2, 2, 3), dtype=np.uint8))
    if mask:
        cv2.imwrite(str(root / "masks" / "one.png"), np.full((2, 2), label, dtype=np.uint8))
    return root


def test_dataset_contract_rejects_missing_and_unknown_mask(tmp_path):
    with pytest.raises(validation.WeightsValidationError) as missing:
        validation.collect_dataset(dataset(tmp_path, mask=False), 3, 255)
    assert missing.value.code == "MASK_MISSING"
    with pytest.raises(validation.WeightsValidationError) as wrong:
        validation.collect_dataset(dataset(tmp_path, label=17), 3, 255)
    assert wrong.value.code == "MASK_LABEL_INVALID"


def test_real_world_palette_mask_and_filename_rejection_order(tmp_path, monkeypatch):
    root = tmp_path / "storage"
    dataset_root = root / "datasets" / "dataset-1"
    (dataset_root / "images").mkdir(parents=True)
    (dataset_root / "masks").mkdir()
    image_name = "ADE_train_00000007.jpg_1_1.png"
    mask_name = "ADE_train_00000007.png_1_1.png"
    cv2.imwrite(str(dataset_root / "images" / image_name), np.zeros((2, 2, 3), dtype=np.uint8))
    mask_path = dataset_root / "masks" / mask_name
    indexed = Image.new("P", (2, 2))
    indexed.putpalette([0, 0, 0, 32, 32, 32, 64, 64, 64, 96, 96, 96] + [0, 0, 0] * 252)
    indexed.putdata([0, 1, 2, 3])
    indexed.save(mask_path)
    with pytest.raises(validation.WeightsValidationError) as missing:
        validation.collect_dataset(dataset_root, 3, 255)
    assert missing.value.code == "MASK_MISSING"

    renamed_mask = dataset_root / "masks" / (Path(image_name).stem + ".png")
    mask_path.rename(renamed_mask)
    with pytest.raises(validation.WeightsValidationError) as invalid_label:
        validation.collect_dataset(dataset_root, 3, 255)
    assert invalid_label.value.code == "MASK_LABEL_INVALID"

    indexed.putdata([0, 1, 2, 255])
    indexed.save(renamed_mask)
    assert len(validation.collect_dataset(dataset_root, 3, 255)) == 1


def test_dataset_accepts_case_sensitive_png_filename(tmp_path):
    root = dataset(tmp_path, mask=False)
    cv2.imwrite(str(root / "masks" / "one.PNG"), np.ones((2, 2), dtype=np.uint8))
    pairs = validation.collect_dataset(root, 3, 255)
    assert pairs[0][1].name == "one.PNG"


def test_metrics_reject_all_ignored_ground_truth():
    metrics = ConfusionMetrics(["background", "healthy_wheat", "lodged_wheat"], 255)
    metrics.add(np.zeros((2, 2), dtype=np.uint8), np.full((2, 2), 255, dtype=np.uint8))
    with pytest.raises(SegmentationMetricError):
        metrics.result()


def test_runtime_rejects_path_outside_managed_storage(tmp_path, monkeypatch):
    root = tmp_path / "storage"
    (root / "datasets").mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    monkeypatch.setenv("WORKFLOW_STORAGE_ROOT", str(root))
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.controlled_path(str(outside), "datasets", directory=True)
    assert error.value.code == "PATH_NOT_ALLOWED"


def test_safe_loader_rejects_full_checkpoint_and_nonfinite(tmp_path):
    path = tmp_path / "weights.pt"
    torch.save({"model": "full checkpoint"}, path)
    with pytest.raises(validation.WeightsValidationError):
        validation.safe_load(path)
    torch.save({"state_dict": OrderedDict(weight=torch.tensor([float("nan")]))}, path)
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.safe_load(path)
    assert error.value.code == "WEIGHTS_NON_FINITE"


class FakeModel:
    def state_dict(self):
        return OrderedDict(weight=torch.ones((1,), dtype=torch.float32))

    def load_state_dict(self, state, strict):
        assert strict is True
        return SimpleNamespace(missing_keys=[], unexpected_keys=[])

    def eval(self):
        return self

    def cuda(self):
        return self

    def simple_test(self, tensor, metadata, rescale):
        assert rescale is True
        return [np.ones((2, 2), dtype=np.uint8)]


def validation_request(tmp_path, monkeypatch, *, model_factory=FakeModel, preprocess_fn=None):
    root = tmp_path / "storage"
    images = dataset(root)
    weights = root / "federated-models" / "workflow" / "global_weights.pt"
    weights.parent.mkdir(parents=True)
    state = OrderedDict(weight=torch.ones((1,), dtype=torch.float32))
    torch.save({"state_dict": state}, weights)
    digest = hashlib.sha256(weights.read_bytes()).hexdigest()
    signatures = validation.state_signatures(state)
    output = root / "python" / "result-samples" / "job-1"
    monkeypatch.setenv("WORKFLOW_STORAGE_ROOT", str(root))
    monkeypatch.setattr(validation, "check_definition", lambda *_args, **_kwargs: SimpleNamespace(available=True, reasonCode="AVAILABLE", message="ok"))
    monkeypatch.setattr(torch.cuda, "is_available", lambda: True)
    monkeypatch.setattr(torch.cuda, "synchronize", lambda: None)
    monkeypatch.setattr(torch.cuda, "empty_cache", lambda: None)
    monkeypatch.setattr(validation, "build_model", lambda *_args: model_factory())
    monkeypatch.setattr(validation, "preprocess", preprocess_fn or (lambda *_: (torch.zeros(1, 3, 2, 2), [{}])))
    definition = json.loads((ROOT.parent / "service-python" / "app" / "model_definitions" / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json").read_text(encoding="utf-8"))
    request = WeightsValidationRequest(
        workflowId=41, jobId="diagnostic-job", modelDefinition=definition,
        runtimeProfileId="INTERNIMAGE_RUNTIME_V1",
        globalWeights={"assetId": 89, "weightsPath": str(weights), "expectedSha256": digest,
                       "expectedTensorCount": 1, "expectedElementCount": 1,
                       "expectedStateDictKeyHash": signatures["stateDictKeyHash"],
                       "expectedShapeSignature": signatures["shapeSignature"],
                       "expectedDtypeSignature": signatures["dtypeSignature"]},
        datasetPath=str(images), sampleOutputDirectory=str(output), algorithmType="InternImage-T",
    )
    return request


@pytest.mark.parametrize("state,reason", [
    (OrderedDict(), "STRICT_LOAD_FAILED"),
    (OrderedDict(weight=torch.ones((1,)), extra=torch.ones((1,))), "STRICT_LOAD_FAILED"),
    (OrderedDict(weight=torch.ones((2,))), "STRICT_LOAD_FAILED"),
    (OrderedDict(weight=torch.ones((1,), dtype=torch.int64)), "WEIGHTS_DTYPE_UNSUPPORTED"),
])
def test_strict_rebuild_rejects_invalid_state(monkeypatch, state, reason):
    monkeypatch.setattr(validation, "build_model", lambda *_args: FakeModel())
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.strict_rebuild({}, state)
    assert error.value.code == reason


def test_runtime_validation_returns_ground_truth_and_strict_evidence(tmp_path, monkeypatch):
    request = validation_request(tmp_path, monkeypatch)
    result = validation.run_validation(request)
    assert result.metrics["mIoU"] == 1
    assert result.metrics["metricsMode"] == "GROUND_TRUTH"
    assert result.reconstruction["strictLoad"] is True
    assert result.reconstruction["checkpointUsed"] is False
    assert result.validationEvidence["globalWeightsAssetId"] == 89
    request.globalWeights.expectedSha256 = "0" * 64
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.run_validation(request)
    assert error.value.code == "WEIGHTS_SHA_MISMATCH"


def test_forward_cuda_error_is_safely_classified_and_runtime_logs_traceback(tmp_path, monkeypatch, caplog):
    class ForwardError(FakeModel):
        def simple_test(self, *_args, **_kwargs):
            raise RuntimeError("CUDA error: device-side assert triggered")

    request = validation_request(tmp_path, monkeypatch, model_factory=ForwardError)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)

    assert error.value.code == "CUDA_INFERENCE_FAILED"
    assert error.value.phase == "MODEL_FORWARD"
    crash = next(record for record in caplog.records if "validation phase crashed" in record.message)
    assert crash.exc_info is not None
    assert crash.exc_info[0] is RuntimeError
    assert "workflowId=41" in crash.message
    assert "jobId=diagnostic-job" in crash.message
    assert "runtimeProfileId=INTERNIMAGE_RUNTIME_V1" in crash.message
    assert "global_weights.pt" not in caplog.text


def test_forward_api_error_is_not_mislabeled_as_cuda_failure(tmp_path, monkeypatch, caplog):
    class ForwardApiError(FakeModel):
        def simple_test(self, *_args, **_kwargs):
            raise TypeError("synthetic simple_test argument mismatch")

    request = validation_request(tmp_path, monkeypatch, model_factory=ForwardApiError)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "MODEL_FORWARD_FAILED"
    assert error.value.phase == "MODEL_FORWARD"
    assert any(record.exc_info for record in caplog.records if "validation phase crashed" in record.message)


def test_cuda_out_of_memory_has_distinct_safe_reason_and_traceback(tmp_path, monkeypatch, caplog):
    class OutOfMemoryModel(FakeModel):
        def simple_test(self, *_args, **_kwargs):
            raise torch.cuda.OutOfMemoryError("CUDA out of memory")

    request = validation_request(tmp_path, monkeypatch, model_factory=OutOfMemoryModel)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "CUDA_OUT_OF_MEMORY"
    assert error.value.phase == "MODEL_FORWARD"
    assert any(record.exc_info for record in caplog.records if "validation phase crashed" in record.message)


def test_safe_weight_load_exception_is_logged_and_does_not_enter_forward(tmp_path, monkeypatch, caplog):
    request = validation_request(tmp_path, monkeypatch)

    def unreadable(*_args, **_kwargs):
        raise OSError("synthetic weight read error")

    monkeypatch.setattr(torch, "load", unreadable)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "WEIGHTS_LOAD_FAILED"
    assert error.value.phase == "WEIGHTS_LOAD"
    crash = next(record for record in caplog.records if "validation phase crashed" in record.message)
    assert crash.exc_info is not None
    assert crash.exc_info[0] is OSError


def test_strict_load_runtime_exception_is_logged_and_keeps_strict_failure_code(tmp_path, monkeypatch, caplog):
    class StrictLoadErrorModel(FakeModel):
        def load_state_dict(self, _state, strict):
            assert strict is True
            raise RuntimeError("synthetic strict mismatch")

    request = validation_request(tmp_path, monkeypatch, model_factory=StrictLoadErrorModel)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "STRICT_LOAD_FAILED"
    assert error.value.phase == "STRICT_LOAD"
    crash = next(record for record in caplog.records if "validation phase crashed" in record.message)
    assert crash.exc_info is not None
    assert crash.exc_info[0] is RuntimeError


def test_model_rebuild_runtime_exception_is_logged_and_classified(tmp_path, monkeypatch, caplog):
    def broken_builder():
        raise RuntimeError("synthetic architecture build failure")

    request = validation_request(tmp_path, monkeypatch, model_factory=broken_builder)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "MODEL_REBUILD_FAILED"
    assert error.value.phase == "MODEL_REBUILD"
    crash = next(record for record in caplog.records if "validation phase crashed" in record.message)
    assert crash.exc_info is not None
    assert crash.exc_info[0] is RuntimeError


def test_invalid_preprocessed_input_is_not_reported_as_inference_failure(tmp_path, monkeypatch, caplog):
    def invalid_preprocess(*_args):
        raise ValueError("synthetic invalid input")

    request = validation_request(tmp_path, monkeypatch, preprocess_fn=invalid_preprocess)
    with caplog.at_level(logging.INFO, logger="internimage-runtime.validation"):
        with pytest.raises(validation.WeightsValidationError) as error:
            validation.run_validation(request)
    assert error.value.code == "INPUT_PREPROCESS_FAILED"
    assert error.value.phase == "IMAGE_PREPROCESS"
    assert any(record.exc_info for record in caplog.records if "validation phase crashed" in record.message)


def test_unexpected_prediction_structure_is_output_decode_failure(tmp_path, monkeypatch):
    class InvalidOutputModel(FakeModel):
        def simple_test(self, *_args, **_kwargs):
            return {"prediction": np.ones((2, 2), dtype=np.uint8)}

    request = validation_request(tmp_path, monkeypatch, model_factory=InvalidOutputModel)
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.run_validation(request)
    assert error.value.code == "OUTPUT_DECODE_FAILED"
    assert error.value.phase == "OUTPUT_DECODE"


def test_metric_failure_is_distinct_from_model_inference(tmp_path, monkeypatch):
    class BrokenMetrics:
        def __init__(self, *_args):
            pass

        def add(self, *_args):
            raise RuntimeError("synthetic metric failure")

    request = validation_request(tmp_path, monkeypatch)
    monkeypatch.setattr(validation, "ConfusionMetrics", BrokenMetrics)
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.run_validation(request)
    assert error.value.code == "METRICS_FAILED"
    assert error.value.phase == "METRICS_COMPUTE"


def test_sample_render_failure_is_not_misreported_as_inference_failure(tmp_path, monkeypatch):
    request = validation_request(tmp_path, monkeypatch)
    monkeypatch.setattr(validation.cv2, "addWeighted", lambda *_args, **_kwargs: (_ for _ in ()).throw(ValueError("synthetic render error")))
    with pytest.raises(validation.WeightsValidationError) as error:
        validation.run_validation(request)
    assert error.value.code == "SAMPLE_RENDER_FAILED"
    assert error.value.phase == "SAMPLE_RENDER"
