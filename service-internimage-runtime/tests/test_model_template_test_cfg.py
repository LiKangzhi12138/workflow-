from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app import model_template


DEFINITION_PATH = (
    SERVICE_ROOT.parent
    / "service-python"
    / "app"
    / "model_definitions"
    / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json"
)


def trusted_definition() -> dict:
    return json.loads(DEFINITION_PATH.read_text(encoding="utf-8"))


def test_builder_passes_mmc_config_dict_and_preserves_definition_test_cfg(monkeypatch):
    class FakeConfigDict(dict):
        def __getattr__(self, name):
            try:
                return self[name]
            except KeyError as exc:
                raise AttributeError(name) from exc

    class EncoderDecoderContract:
        def __init__(self, config):
            self.test_cfg = config["test_cfg"]

        def simple_test(self, *_args, **_kwargs):
            # MMSeg 0.27.0 EncoderDecoder.simple_test reads this exact attribute.
            assert self.test_cfg.mode in {"whole", "slide"}
            return self.test_cfg.mode

    expected_test_cfg = trusted_definition()["definitionPayload"]["architecture"]["modelConfig"]["test_cfg"]
    captured = {}

    def build_segmentor(config):
        assert isinstance(config["test_cfg"], FakeConfigDict)
        captured["test_cfg"] = dict(config["test_cfg"])
        return EncoderDecoderContract(config)

    real_import = model_template.importlib.import_module

    def import_runtime_module(name):
        if name == "runtime_vendor.intern_image":
            return SimpleNamespace()
        if name == "mmcv":
            return SimpleNamespace(ConfigDict=FakeConfigDict)
        if name == "mmseg.models.builder":
            return SimpleNamespace(build_segmentor=build_segmentor)
        return real_import(name)

    monkeypatch.setattr(model_template.importlib, "import_module", import_runtime_module)

    model = model_template.build_model(trusted_definition())

    assert model.test_cfg.mode == expected_test_cfg["mode"]
    assert captured["test_cfg"] == expected_test_cfg
    assert model.simple_test(None, None, rescale=True) == expected_test_cfg["mode"]


def test_builder_rejects_definition_test_cfg_drift_before_build(monkeypatch):
    definition = trusted_definition()
    definition["definitionPayload"]["architecture"]["modelConfig"]["test_cfg"] = {"mode": "slide"}

    def unexpected_import(_name):
        raise AssertionError("runtime imports should not occur for an untrusted architecture")

    monkeypatch.setattr(model_template.importlib, "import_module", unexpected_import)
    with pytest.raises(ValueError, match="trusted InternImage template"):
        model_template.build_model(definition)


@pytest.mark.skipif(
    os.getenv("INTERNIMAGE_GPU_INTEGRATION") != "1",
    reason="opt-in real MMSegmentation/DCNv3 GPU inference check",
)
def test_real_trusted_model_simple_test_uses_definition_test_cfg():
    pytest.importorskip("mmcv")
    pytest.importorskip("mmseg")
    import torch

    if not torch.cuda.is_available():
        pytest.skip("CUDA is unavailable")

    model = model_template.build_model(trusted_definition()).cuda().eval()
    assert model.test_cfg.mode == "whole"
    image = torch.zeros((1, 3, 480, 480), dtype=torch.float32, device="cuda")
    metadata = [{
        "filename": "synthetic.png",
        "ori_filename": "synthetic.png",
        "ori_shape": (480, 480, 3),
        "img_shape": (480, 480, 3),
        "pad_shape": (480, 480, 3),
        "scale_factor": np.ones(4, dtype=np.float32),
        "flip": False,
        "flip_direction": None,
    }]
    with torch.inference_mode():
        prediction = model.simple_test(image, metadata, rescale=True)
        torch.cuda.synchronize()
    assert isinstance(prediction, list) and len(prediction) == 1
    assert prediction[0].shape == (480, 480)
