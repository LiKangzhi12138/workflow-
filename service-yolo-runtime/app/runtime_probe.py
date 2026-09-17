from __future__ import annotations

import importlib
import os
import platform
from pathlib import Path

from app.schemas import RuntimeHealthResponse


RUNTIME_PROFILE_ID = "YOLO_RUNTIME_V1"
EXPECTED_PYTHON = "3.11"
EXPECTED_TORCH = "2.7.1"
EXPECTED_TORCHVISION = "0.22.1"
EXPECTED_ULTRALYTICS = "8.4.41"
EXPECTED_CUDA_BUILD = "12.8"


def _base_version(value: str | None) -> str | None:
    return value.split("+", 1)[0] if value else None


def ultralytics_config_directory() -> Path:
    config_home = Path(
        os.getenv("XDG_CONFIG_HOME", str(Path.home() / ".config"))
    ).expanduser()
    return config_home / "Ultralytics"


def ultralytics_config_directory_ready() -> bool:
    config_dir = ultralytics_config_directory()
    return config_dir.is_dir() and os.access(config_dir, os.W_OK)


def probe_runtime() -> RuntimeHealthResponse:
    errors: list[str] = []
    warnings: list[str] = []
    python_version = platform.python_version()
    torch_module = None
    torchvision_module = None
    ultralytics_module = None

    try:
        torch_module = importlib.import_module("torch")
    except Exception as exc:
        errors.append(f"PyTorch import failed: {type(exc).__name__}")
    try:
        torchvision_module = importlib.import_module("torchvision")
    except Exception as exc:
        errors.append(f"torchvision import failed: {type(exc).__name__}")
    try:
        ultralytics_module = importlib.import_module("ultralytics")
    except Exception as exc:
        errors.append(f"Ultralytics import failed: {type(exc).__name__}")

    torch_version = str(getattr(torch_module, "__version__", "")) or None
    torchvision_version = str(getattr(torchvision_module, "__version__", "")) or None
    ultralytics_version = str(getattr(ultralytics_module, "__version__", "")) or None
    if not ultralytics_config_directory_ready():
        errors.append(
            f"Ultralytics config directory is not writable: {ultralytics_config_directory()}"
        )
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

    if not python_version.startswith(EXPECTED_PYTHON + "."):
        errors.append(f"Python {python_version} does not satisfy {EXPECTED_PYTHON}.x")
    if _base_version(torch_version) != EXPECTED_TORCH:
        errors.append(f"PyTorch {torch_version} does not match {EXPECTED_TORCH}")
    if _base_version(torchvision_version) != EXPECTED_TORCHVISION:
        errors.append(
            f"torchvision {torchvision_version} does not match {EXPECTED_TORCHVISION}"
        )
    if ultralytics_version != EXPECTED_ULTRALYTICS:
        errors.append(
            f"Ultralytics {ultralytics_version} does not match {EXPECTED_ULTRALYTICS}"
        )
    if cuda_build_version != EXPECTED_CUDA_BUILD:
        errors.append(
            f"CUDA build {cuda_build_version} does not match {EXPECTED_CUDA_BUILD}"
        )
    if not cuda_available:
        errors.append("CUDA is required but torch.cuda.is_available() is false")
    if not gpu_available:
        errors.append("A visible NVIDIA GPU is required")

    return RuntimeHealthResponse(
        runtimeProfileId=RUNTIME_PROFILE_ID,
        runtimeType="REMOTE_CONTAINER",
        probeContext="CONTAINER_RUNTIME",
        pythonVersion=python_version,
        torchAvailable=torch_module is not None,
        torchVersion=torch_version,
        torchvisionAvailable=torchvision_module is not None,
        torchvisionVersion=torchvision_version,
        ultralyticsAvailable=ultralytics_module is not None,
        ultralyticsVersion=ultralytics_version,
        cudaAvailable=cuda_available,
        cudaBuildVersion=cuda_build_version,
        gpuAvailable=gpu_available,
        gpuName=gpu_name,
        runtimeReady=not errors,
        errors=errors,
        warnings=warnings,
    )
