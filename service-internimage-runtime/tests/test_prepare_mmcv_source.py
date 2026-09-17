from __future__ import annotations

import importlib.util
import re
from pathlib import Path

import pytest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = SERVICE_ROOT / "build_support" / "prepare_mmcv_source.py"
SPEC = importlib.util.spec_from_file_location("prepare_mmcv_source", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
prepare_mmcv_source = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare_mmcv_source)


UPSTREAM_SETUP = """
extra_compile_args = {
    'nvcc': [cuda_args, '-std=c++14'] if cuda_args else ['-std=c++14'],
    'cxx': ['-std=c++14'],
}
extra_compile_args['cxx'] = ['-std=c++14']
extra_compile_args['cxx'] = ['-Wall', '-std=c++17']
extra_compile_args['nvcc'] += ['-std=c++14']
"""

UPSTREAM_SCATTER = """
def forward(target_gpus):
    streams = [_get_stream(device) for device in target_gpus]
"""


def source_tree(tmp_path: Path) -> Path:
    root = tmp_path / "mmcv-full-1.6.2"
    (root / "mmcv" / "parallel").mkdir(parents=True)
    (root / "setup.py").write_text(UPSTREAM_SETUP, encoding="utf-8")
    (root / "mmcv" / "parallel" / "_functions.py").write_text(
        UPSTREAM_SCATTER,
        encoding="utf-8",
    )
    return root


def test_patches_all_cpu_and_cuda_extension_flags_to_cpp17(tmp_path: Path) -> None:
    root = source_tree(tmp_path)

    prepare_mmcv_source.patch_mmcv_source(root)

    setup = (root / "setup.py").read_text(encoding="utf-8")
    assert setup.count("-std=c++14") == 0
    assert setup.count("-std=c++17") == 6
    assert "extra_compile_args['cxx'] = ['-std=c++17']" in setup
    assert "extra_compile_args['nvcc'] += ['-std=c++17']" in setup


def test_missing_expected_upstream_flag_fails_closed(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    setup_path = root / "setup.py"
    setup_path.write_text(
        setup_path.read_text(encoding="utf-8").replace("'-std=c++14'", "'-std=c++20'", 1),
        encoding="utf-8",
    )

    with pytest.raises(prepare_mmcv_source.MmcvPatchError, match="Unexpected MMCV"):
        prepare_mmcv_source.patch_mmcv_source(root)


def test_effective_cpp14_flag_remaining_after_patch_fails_closed(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    prepare_mmcv_source.patch_mmcv_source(root)
    (root / "remaining_flag.py").write_text("flags = ['-std=gnu++14']\n", encoding="utf-8")

    with pytest.raises(prepare_mmcv_source.MmcvPatchError, match=r"C\+\+14 flags remain"):
        prepare_mmcv_source.audit_source_tree(root)


def test_scatter_patch_uses_torch_device_for_pytorch_27(tmp_path: Path) -> None:
    root = source_tree(tmp_path)

    prepare_mmcv_source.patch_mmcv_source(root)

    scatter = (root / "mmcv" / "parallel" / "_functions.py").read_text(encoding="utf-8")
    assert "_get_stream(device)" not in scatter
    assert "_get_stream(torch.device('cuda', device))" in scatter


def test_repeat_patch_is_idempotent(tmp_path: Path) -> None:
    root = source_tree(tmp_path)
    prepare_mmcv_source.patch_mmcv_source(root)
    setup_before = (root / "setup.py").read_bytes()
    scatter_before = (root / "mmcv" / "parallel" / "_functions.py").read_bytes()

    prepare_mmcv_source.patch_mmcv_source(root)

    assert (root / "setup.py").read_bytes() == setup_before
    assert (root / "mmcv" / "parallel" / "_functions.py").read_bytes() == scatter_before


def test_runtime_install_glob_matches_normalized_dcnv3_wheel_name() -> None:
    setup_source = (SERVICE_ROOT / "ops_dcnv3" / "setup.py").read_text(encoding="utf-8")
    package_name_match = re.search(r"\bname\s*=\s*['\"]([^'\"]+)['\"]", setup_source)
    assert package_name_match is not None
    wheel_stem = re.sub(r"[-_.]+", "_", package_name_match.group(1)).lower()

    dockerfile = (SERVICE_ROOT / "Dockerfile").read_text(encoding="utf-8")
    expected_glob = f"/tmp/dcnv3-wheels/{wheel_stem}-*.whl"
    assert expected_glob in dockerfile
    assert "/tmp/dcnv3-wheels/DCNv3-*.whl" not in dockerfile
    assert '[ "$#" -eq 1 ] && [ -f "$1" ]' in dockerfile
