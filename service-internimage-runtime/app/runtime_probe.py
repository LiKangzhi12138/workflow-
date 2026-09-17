from __future__ import annotations

import importlib
import platform

from app.model_template import INTERNIMAGE_SOURCE_COMMIT
from app.schemas import RuntimeHealthResponse


RUNTIME_PROFILE_ID = "INTERNIMAGE_RUNTIME_V1"
EXPECTED_PYTHON = "3.11"
EXPECTED_TORCH = "2.7.1"
EXPECTED_TORCHVISION = "0.22.1"
EXPECTED_CUDA_BUILD = "12.8"
EXPECTED_MMCV = "1.6.2"
EXPECTED_MMSEG = "0.27.0"
EXPECTED_TIMM = "0.6.11"
EXPECTED_DCNV3 = "1.0"


def _base_version(value: str | None) -> str | None:
    return value.split("+", 1)[0] if value else None


def _import(name: str, errors: list[str]):
    try:
        return importlib.import_module(name)
    except Exception as exc:
        errors.append(f"{name} import failed: {type(exc).__name__}")
        return None


def _dcnv3_forward(torch_module, errors: list[str]) -> bool:
    if torch_module is None or not torch_module.cuda.is_available():
        return False
    try:
        modules = importlib.import_module("ops_dcnv3.modules")
        layer = modules.DCNv3(channels=16, group=1).cuda().eval()
        sample = torch_module.zeros((1, 8, 8, 16), device="cuda")
        with torch_module.no_grad():
            output = layer(sample)
        return tuple(output.shape) == (1, 8, 8, 16) and bool(
            torch_module.isfinite(output).all().item()
        )
    except Exception as exc:
        errors.append(f"DCNv3 minimal CUDA forward failed: {type(exc).__name__}")
        return False


def _timm_python311_compatibility(timm_module, errors: list[str]) -> bool:
    if timm_module is None:
        return False
    try:
        maxxvit = importlib.import_module("timm.models.maxxvit")
        first = maxxvit.MaxxVitCfg()
        second = maxxvit.MaxxVitCfg()
        passed = (
            first.conv_cfg is not second.conv_cfg
            and first.transformer_cfg is not second.transformer_cfg
        )
        if not passed:
            errors.append("timm Python 3.11 default_factory compatibility check failed")
        return passed
    except Exception as exc:
        errors.append(f"timm Python 3.11 compatibility check failed: {type(exc).__name__}")
        return False


def probe_runtime() -> RuntimeHealthResponse:
    errors: list[str] = []
    warnings: list[str] = []
    python_version = platform.python_version()
    torch_module = _import("torch", errors)
    torchvision_module = _import("torchvision", errors)
    mmcv_module = _import("mmcv", errors)
    mmseg_module = _import("mmseg", errors)
    timm_module = _import("timm", errors)
    dcnv3_module = _import("DCNv3", errors)
    internimage_module = _import("runtime_vendor.intern_image", errors)

    try:
        mmengine_module = importlib.import_module("mmengine")
    except Exception:
        mmengine_module = None
        warnings.append("MMEngine is not required by the verified MMCV 1.x/MMSeg 0.x stack.")

    torch_version = str(getattr(torch_module, "__version__", "")) or None
    torchvision_version = str(getattr(torchvision_module, "__version__", "")) or None
    mmcv_version = str(getattr(mmcv_module, "__version__", "")) or None
    mmseg_version = str(getattr(mmseg_module, "__version__", "")) or None
    timm_version = str(getattr(timm_module, "__version__", "")) or None
    mmengine_version = str(getattr(mmengine_module, "__version__", "")) or None
    dcnv3_version = str(getattr(dcnv3_module, "__version__", EXPECTED_DCNV3)) if dcnv3_module else None
    timm_python311_compatibility = _timm_python311_compatibility(timm_module, errors)

    cuda_available = bool(torch_module is not None and torch_module.cuda.is_available())
    cuda_build_version = (
        str(getattr(getattr(torch_module, "version", None), "cuda", "")) or None
        if torch_module is not None
        else None
    )
    gpu_name = None
    if cuda_available:
        try:
            gpu_name = str(torch_module.cuda.get_device_name(0))
        except Exception as exc:
            errors.append(f"GPU query failed: {type(exc).__name__}")
    gpu_available = bool(cuda_available and gpu_name)

    checks = [
        (python_version.startswith(EXPECTED_PYTHON + "."), f"Python must satisfy {EXPECTED_PYTHON}.x"),
        (_base_version(torch_version) == EXPECTED_TORCH, f"PyTorch must be {EXPECTED_TORCH}"),
        (_base_version(torchvision_version) == EXPECTED_TORCHVISION, f"torchvision must be {EXPECTED_TORCHVISION}"),
        (mmcv_version == EXPECTED_MMCV, f"MMCV must be {EXPECTED_MMCV}"),
        (mmseg_version == EXPECTED_MMSEG, f"MMSegmentation must be {EXPECTED_MMSEG}"),
        (timm_version == EXPECTED_TIMM, f"timm must be {EXPECTED_TIMM}"),
        (cuda_build_version == EXPECTED_CUDA_BUILD, f"CUDA build must be {EXPECTED_CUDA_BUILD}"),
        (cuda_available, "CUDA is required"),
        (gpu_available, "A visible NVIDIA GPU is required"),
        (dcnv3_module is not None, "The compiled DCNv3 extension is required"),
        (internimage_module is not None, "The pinned InternImage source is required"),
    ]
    errors.extend(message for passed, message in checks if not passed)
    dcnv3_forward_passed = _dcnv3_forward(torch_module, errors)
    if not dcnv3_forward_passed and not any("DCNv3 minimal" in item for item in errors):
        errors.append("DCNv3 minimal CUDA forward did not pass")

    return RuntimeHealthResponse(
        runtimeProfileId=RUNTIME_PROFILE_ID,
        runtimeType="REMOTE_CONTAINER",
        probeContext="CONTAINER_RUNTIME",
        pythonVersion=python_version,
        torchAvailable=torch_module is not None,
        torchVersion=torch_version,
        torchvisionAvailable=torchvision_module is not None,
        torchvisionVersion=torchvision_version,
        mmcvAvailable=mmcv_module is not None,
        mmcvVersion=mmcv_version,
        mmengineAvailable=mmengine_module is not None,
        mmengineVersion=mmengine_version,
        mmsegAvailable=mmseg_module is not None,
        mmsegVersion=mmseg_version,
        internimageAvailable=internimage_module is not None,
        internimageVersion=INTERNIMAGE_SOURCE_COMMIT if internimage_module is not None else None,
        python311CompatibilityPatch=timm_python311_compatibility,
        dcnv3Available=dcnv3_module is not None,
        dcnv3Version=dcnv3_version,
        dcnv3SelfCheckPassed=dcnv3_forward_passed,
        minimalForwardCheckPassed=dcnv3_forward_passed,
        cudaAvailable=cuda_available,
        cudaBuildVersion=cuda_build_version,
        gpuAvailable=gpu_available,
        gpuName=gpu_name,
        runtimeReady=not errors,
        errors=errors,
        warnings=warnings,
    )
