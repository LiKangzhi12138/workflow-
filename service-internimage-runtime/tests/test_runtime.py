from __future__ import annotations

import copy
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path
from types import SimpleNamespace

import pytest
import torch


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app import definition_check, runtime_probe, weights_compatibility
from app.model_template import EXPECTED_ELEMENT_COUNT, EXPECTED_TENSOR_COUNT, trusted_architecture
from app.schemas import RuntimeHealthResponse, StateTensorMetadata


DEFINITION_PATH = (
    SERVICE_ROOT.parent
    / "service-python"
    / "app"
    / "model_definitions"
    / "INTERNIMAGE_T_UPERNET_WHEAT_V1.json"
)


def definition() -> dict:
    return json.loads(DEFINITION_PATH.read_text(encoding="utf-8"))


def ready_health(**overrides) -> RuntimeHealthResponse:
    values = {
        "runtimeProfileId": "INTERNIMAGE_RUNTIME_V1",
        "runtimeType": "REMOTE_CONTAINER",
        "probeContext": "CONTAINER_RUNTIME",
        "pythonVersion": "3.11.13",
        "torchAvailable": True,
        "torchVersion": "2.7.1+cu128",
        "torchvisionAvailable": True,
        "torchvisionVersion": "0.22.1+cu128",
        "mmcvAvailable": True,
        "mmcvVersion": "1.6.2",
        "mmengineAvailable": False,
        "mmengineVersion": None,
        "mmsegAvailable": True,
        "mmsegVersion": "0.27.0",
        "internimageAvailable": True,
        "internimageVersion": "31c962dc6c1ceb23e580772f7daaa6944694fbe6",
        "python311CompatibilityPatch": True,
        "dcnv3Available": True,
        "dcnv3Version": "1.0",
        "dcnv3SelfCheckPassed": True,
        "minimalForwardCheckPassed": True,
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


class FakeTensor:
    def __init__(self, elements: int) -> None:
        self.elements = elements

    def numel(self) -> int:
        return self.elements


class FakeModel:
    decode_head = SimpleNamespace(num_classes=3)
    auxiliary_head = SimpleNamespace(num_classes=3)

    def state_dict(self):
        state = {f"key.{index}": FakeTensor(1) for index in range(EXPECTED_TENSOR_COUNT - 1)}
        state["large"] = FakeTensor(EXPECTED_ELEMENT_COUNT - EXPECTED_TENSOR_COUNT + 1)
        return state


def rehash(value: dict) -> dict:
    value["architectureSignature"] = definition_check.canonical_sha256(
        value["definitionPayload"]["architecture"]
    )
    value["definitionSha256"] = ""
    value["definitionSha256"] = definition_check.canonical_sha256(value)
    return value


def test_trusted_definition_and_architecture_signatures_are_stable():
    value = definition()
    assert value["architectureSignature"] == definition_check.EXPECTED_ARCHITECTURE_SIGNATURE
    assert value["definitionPayload"]["architecture"] == trusted_architecture()
    assert definition_check.canonical_sha256({**value, "definitionSha256": ""}) == value["definitionSha256"]


def test_manual_sql_seed_contains_the_exact_canonical_definition():
    sql_path = SERVICE_ROOT.parent / "docs" / "weights-protocol" / "STAGE5A_SQL.sql"
    sql = sql_path.read_text(encoding="utf-8")
    match = re.search(r"CAST\('(?P<definition>\{.*?\})' AS JSON\)", sql, re.DOTALL)
    assert match is not None
    mysql_string_value = match.group("definition").replace("\\\\", "\\")
    assert json.loads(mysql_string_value) == definition()


def test_definition_architecture_only_build_passes_without_checkpoint(monkeypatch):
    monkeypatch.setattr(definition_check, "probe_runtime", ready_health)
    monkeypatch.setattr(definition_check, "build_model", FakeModel)

    result = definition_check.check_definition(definition(), "INTERNIMAGE_RUNTIME_V1")

    assert result.available is True
    assert result.selfCheckPassed is True
    assert result.tensorCount == 728
    assert result.elementCount == 58_963_958
    assert result.checkpointUsed is False
    assert result.baseCheckpointUsed is False
    assert result.pretrainedUsed is False


def test_weights_compatibility_accepts_exact_architecture_state(monkeypatch):
    model = SimpleNamespace(
        state_dict=lambda: OrderedDict(
            [
                ("backbone.weight", torch.ones((2, 3), dtype=torch.float32)),
                ("decode_head.counter", torch.tensor(1, dtype=torch.int64)),
            ]
        )
    )
    monkeypatch.setattr(
        weights_compatibility,
        "check_definition",
        lambda _definition, _profile: SimpleNamespace(available=True),
    )
    monkeypatch.setattr(weights_compatibility, "build_model", lambda: model)

    result = weights_compatibility.check_weights_compatibility(
        definition(),
        "INTERNIMAGE_RUNTIME_V1",
        [
            StateTensorMetadata(key="backbone.weight", shape=[2, 3], dtype="float32"),
            StateTensorMetadata(key="decode_head.counter", shape=[], dtype="int64"),
        ],
    )

    assert result.compatible is True
    assert result.reasonCode == "AVAILABLE"
    assert result.expectedTensorCount == 2
    assert result.actualTensorCount == 2
    assert result.expectedElementCount == 7
    assert result.actualElementCount == 7


@pytest.mark.parametrize(
    ("state", "reason"),
    [
        (
            [StateTensorMetadata(key="backbone.weight", shape=[2, 3], dtype="float32")],
            "WEIGHTS_KEY_MISMATCH",
        ),
        (
            [
                StateTensorMetadata(key="backbone.weight", shape=[2, 3], dtype="float32"),
                StateTensorMetadata(key="decode_head.counter", shape=[], dtype="int64"),
                StateTensorMetadata(key="extra", shape=[1], dtype="float32"),
            ],
            "WEIGHTS_KEY_MISMATCH",
        ),
        (
            [
                StateTensorMetadata(key="backbone.weight", shape=[3, 2], dtype="float32"),
                StateTensorMetadata(key="decode_head.counter", shape=[], dtype="int64"),
            ],
            "WEIGHTS_SHAPE_MISMATCH",
        ),
        (
            [
                StateTensorMetadata(key="backbone.weight", shape=[2, 3], dtype="float32"),
                StateTensorMetadata(key="decode_head.counter", shape=[], dtype="float32"),
            ],
            "WEIGHTS_DTYPE_UNSUPPORTED",
        ),
    ],
)
def test_weights_compatibility_rejects_state_contract_mismatch(monkeypatch, state, reason):
    model = SimpleNamespace(
        state_dict=lambda: OrderedDict(
            [
                ("backbone.weight", torch.ones((2, 3), dtype=torch.float32)),
                ("decode_head.counter", torch.tensor(1, dtype=torch.int64)),
            ]
        )
    )
    monkeypatch.setattr(
        weights_compatibility,
        "check_definition",
        lambda _definition, _profile: SimpleNamespace(available=True),
    )
    monkeypatch.setattr(weights_compatibility, "build_model", lambda: model)

    result = weights_compatibility.check_weights_compatibility(
        definition(), "INTERNIMAGE_RUNTIME_V1", state
    )

    assert result.compatible is False
    assert result.reasonCode == reason


def test_weights_compatibility_fails_closed_when_definition_is_unavailable(monkeypatch):
    monkeypatch.setattr(
        weights_compatibility,
        "check_definition",
        lambda _definition, _profile: SimpleNamespace(available=False),
    )

    result = weights_compatibility.check_weights_compatibility(
        definition(), "OTHER_RUNTIME", []
    )

    assert result.compatible is False
    assert result.reasonCode == "WEIGHTS_COMPATIBILITY_FAILED"


@pytest.mark.parametrize(
    ("mutation", "reason"),
    [
        (lambda value: value.update(definitionSha256="0" * 64), "DEFINITION_SHA_MISMATCH"),
        (
            lambda value: (
                value["definitionPayload"]["architecture"].update(templateId="UNKNOWN"),
                rehash(value),
            ),
            "ARCHITECTURE_SIGNATURE_MISMATCH",
        ),
        (lambda value: value.update(runtimeProfileId="OTHER_RUNTIME"), "RUNTIME_PROFILE_NOT_FOUND"),
    ],
)
def test_identity_contract_failures_are_rejected(monkeypatch, mutation, reason):
    monkeypatch.setattr(definition_check, "probe_runtime", ready_health)
    value = definition()
    mutation(value)
    result = definition_check.check_definition(value, value["runtimeProfileId"])
    assert result.available is False
    assert result.reasonCode == reason


def test_pretrained_field_is_rejected_even_when_definition_is_rehashed(monkeypatch):
    monkeypatch.setattr(definition_check, "probe_runtime", ready_health)
    value = definition()
    value["definitionPayload"]["pretrained"] = "forbidden.pth"
    rehash(value)
    result = definition_check.check_definition(value, "INTERNIMAGE_RUNTIME_V1")
    assert result.available is False
    assert result.reasonCode == "DEFINITION_INVALID"


def test_dcnv3_unavailable_makes_runtime_not_ready(monkeypatch):
    monkeypatch.setattr(
        definition_check,
        "probe_runtime",
        lambda: ready_health(runtimeReady=False, dcnv3Available=False, dcnv3SelfCheckPassed=False),
    )
    result = definition_check.check_definition(definition(), "INTERNIMAGE_RUNTIME_V1")
    assert result.available is False
    assert result.reasonCode == "RUNTIME_NOT_READY"


def test_runtime_health_reports_verified_dependencies_and_ready_cuda(monkeypatch):
    class FakeMaxxVitCfg:
        def __init__(self):
            self.conv_cfg = object()
            self.transformer_cfg = object()

    modules = {
        "torch": SimpleNamespace(
            __version__="2.7.1+cu128",
            version=SimpleNamespace(cuda="12.8"),
            cuda=SimpleNamespace(
                is_available=lambda: True,
                get_device_name=lambda _index: "Fake GPU",
            ),
        ),
        "torchvision": SimpleNamespace(__version__="0.22.1+cu128"),
        "mmcv": SimpleNamespace(__version__="1.6.2"),
        "mmseg": SimpleNamespace(__version__="0.27.0"),
        "timm": SimpleNamespace(__version__="0.6.11"),
        "timm.models.maxxvit": SimpleNamespace(MaxxVitCfg=FakeMaxxVitCfg),
        "DCNv3": SimpleNamespace(__version__="1.0"),
        "runtime_vendor.intern_image": SimpleNamespace(),
    }
    monkeypatch.setattr(runtime_probe.platform, "python_version", lambda: "3.11.13")
    monkeypatch.setattr(runtime_probe, "_import", lambda name, _errors: modules[name])
    monkeypatch.setattr(
        runtime_probe.importlib,
        "import_module",
        lambda name: modules[name] if name in modules else (_ for _ in ()).throw(ImportError(name)),
    )
    monkeypatch.setattr(runtime_probe, "_dcnv3_forward", lambda _torch, _errors: True)

    result = runtime_probe.probe_runtime()

    assert result.runtimeReady is True
    assert result.mmcvVersion == "1.6.2"
    assert result.mmsegVersion == "0.27.0"
    assert result.internimageVersion == "31c962dc6c1ceb23e580772f7daaa6944694fbe6"
    assert result.python311CompatibilityPatch is True
    assert result.dcnv3SelfCheckPassed is True
    assert result.cudaAvailable is True
    assert result.gpuAvailable is True
    assert result.mmengineAvailable is False
    assert result.errors == []
    assert result.warnings == [
        "MMEngine is not required by the verified MMCV 1.x/MMSeg 0.x stack."
    ]


def test_timm_python311_compatibility_rejects_shared_dataclass_defaults(monkeypatch):
    shared_conv = object()
    shared_transformer = object()

    class BadMaxxVitCfg:
        def __init__(self):
            self.conv_cfg = shared_conv
            self.transformer_cfg = shared_transformer

    monkeypatch.setattr(
        runtime_probe.importlib,
        "import_module",
        lambda name: SimpleNamespace(MaxxVitCfg=BadMaxxVitCfg),
    )
    errors = []

    passed = runtime_probe._timm_python311_compatibility(SimpleNamespace(), errors)

    assert passed is False
    assert errors == ["timm Python 3.11 default_factory compatibility check failed"]


def test_certification_source_uses_only_safe_strict_load():
    source = (SERVICE_ROOT / "certify_runtime.py").read_text(encoding="utf-8")
    assert "weights_only=True" in source
    assert "strict=True" in source
    assert "weights_only=False" not in source
