from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import pytest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = SERVICE_ROOT / "build_support" / "prepare_timm_source.py"
SPEC = importlib.util.spec_from_file_location("prepare_timm_source", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
prepare_timm_source = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare_timm_source)


UPSTREAM_MAXXVIT = """
from dataclasses import dataclass, replace

@dataclass
class MaxxVitConvCfg:
    channels: int = 16

@dataclass
class MaxxVitTransformerCfg:
    heads: int = 4

@dataclass
class MaxxVitCfg:
    conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg()
    transformer_cfg: MaxxVitTransformerCfg = MaxxVitTransformerCfg()

class UsesCfg:
    def __init__(
            self,
            conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg(),
            transformer_cfg: MaxxVitTransformerCfg = MaxxVitTransformerCfg(),
    ):
        self.conv_cfg = conv_cfg
        self.transformer_cfg = transformer_cfg
"""


def source_tree(tmp_path: Path) -> Path:
    root = tmp_path / "timm-0.6.11"
    models = root / "timm" / "models"
    models.mkdir(parents=True)
    (root / "timm" / "__init__.py").write_text(
        "__version__ = '0.6.11'\nfrom . import models\n",
        encoding="utf-8",
    )
    (models / "__init__.py").write_text(
        "from .maxxvit import MaxxVitCfg\n",
        encoding="utf-8",
    )
    (models / "maxxvit.py").write_text(UPSTREAM_MAXXVIT, encoding="utf-8")
    return root


def test_patches_both_python311_mutable_dataclass_defaults(tmp_path: Path) -> None:
    root = source_tree(tmp_path)

    prepare_timm_source.patch_timm_source(root)

    source = (root / "timm" / "models" / "maxxvit.py").read_text(encoding="utf-8")
    assert "from dataclasses import dataclass, field, replace" in source
    assert "conv_cfg: MaxxVitConvCfg = field(default_factory=MaxxVitConvCfg)" in source
    assert (
        "transformer_cfg: MaxxVitTransformerCfg = "
        "field(default_factory=MaxxVitTransformerCfg)"
    ) in source
    assert "    conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg()" not in source.splitlines()
    assert (
        "    transformer_cfg: MaxxVitTransformerCfg = MaxxVitTransformerCfg()"
        not in source.splitlines()
    )
    assert "            conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg()," in source
    assert "            transformer_cfg: MaxxVitTransformerCfg = MaxxVitTransformerCfg()," in source


def test_missing_expected_upstream_token_fails_closed(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    path = root / "timm" / "models" / "maxxvit.py"
    path.write_text(
        path.read_text(encoding="utf-8").replace(
            "conv_cfg: MaxxVitConvCfg = MaxxVitConvCfg()",
            "conv_cfg: MaxxVitConvCfg | None = None",
        ),
        encoding="utf-8",
    )

    with pytest.raises(prepare_timm_source.TimmPatchError, match="Unexpected timm"):
        prepare_timm_source.patch_timm_source(root)


def test_residual_known_mutable_default_fails_closed(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    prepare_timm_source.patch_timm_source(root)
    path = root / "timm" / "models" / "maxxvit.py"
    path.write_text(
        path.read_text(encoding="utf-8").replace(
            "field(default_factory=MaxxVitConvCfg)",
            "MaxxVitConvCfg()",
        ),
        encoding="utf-8",
    )

    with pytest.raises(prepare_timm_source.TimmPatchError):
        prepare_timm_source.audit_source_tree(root)


def test_other_mutable_dataclass_default_in_source_tree_fails_closed(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    prepare_timm_source.patch_timm_source(root)
    (root / "timm" / "models" / "unsafe.py").write_text(
        "from dataclasses import dataclass\n"
        "@dataclass\n"
        "class UnsafeCfg:\n"
        "    values: list = []\n",
        encoding="utf-8",
    )

    with pytest.raises(
        prepare_timm_source.TimmPatchError,
        match="Python 3.11-incompatible dataclass defaults remain",
    ):
        prepare_timm_source.audit_source_tree(root)


def test_repeat_patch_is_idempotent(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    prepare_timm_source.patch_timm_source(root)
    path = root / "timm" / "models" / "maxxvit.py"
    before = path.read_bytes()

    prepare_timm_source.patch_timm_source(root)

    assert path.read_bytes() == before


def test_patched_timm_and_models_import_on_python311_or_later(tmp_path: Path) -> None:
    assert sys.version_info >= (3, 11)
    root = source_tree(tmp_path)
    prepare_timm_source.patch_timm_source(root)
    command = (
        "import timm, timm.models; "
        "from timm.models.maxxvit import MaxxVitCfg; "
        "a=MaxxVitCfg(); b=MaxxVitCfg(); "
        "assert timm.__version__ == '0.6.11'; "
        "assert a.conv_cfg is not b.conv_cfg; "
        "assert a.transformer_cfg is not b.transformer_cfg"
    )
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(root)

    result = subprocess.run(
        [sys.executable, "-c", command],
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr


def test_dockerfile_builds_and_installs_only_the_patched_timm_wheel() -> None:
    dockerfile = (SERVICE_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert "prepare_timm_source.py" in dockerfile
    assert "/build/timm-source/timm-0.6.11" in dockerfile
    assert "/tmp/dcnv3-wheels/timm-0.6.11-*.whl" in dockerfile
    assert "mmsegmentation==0.27.0 timm==0.6.11" not in dockerfile
    assert "import timm,timm.models" in dockerfile
    assert "assert timm.__version__=='0.6.11'" in dockerfile
