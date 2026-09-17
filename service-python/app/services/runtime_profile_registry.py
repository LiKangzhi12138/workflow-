from __future__ import annotations

from app.core.config import settings
from app.schemas.model_protocol import RuntimeProfile, RuntimeStatus


YOLO_RUNTIME_V1 = RuntimeProfile(
    runtimeProfileId="YOLO_RUNTIME_V1",
    name="YOLO Runtime v1",
    runtimeType="MODEL_RUNTIME",
    supportedModelFamilies=["YOLO"],
    pythonVersion="3.11",
    frameworkRequirements={
        "Ultralytics": "8.4.41",
        "PyTorch": "2.7.1",
        "torchvision": "0.22.1",
        "CUDA": "12.8",
    },
    cudaRequired=True,
    gpuRequired=True,
    adapterType="YOLO",
    healthCheckType="RUNTIME_AND_DEFINITION_SELF_CHECK",
    status=RuntimeStatus.ENABLED,
    runtimeMode="REMOTE_CONTAINER",
    serviceName="yolo-runtime",
    baseUrlEnvironmentVariable="YOLO_RUNTIME_BASE_URL",
)

INTERNIMAGE_RUNTIME_V1 = RuntimeProfile(
    runtimeProfileId="INTERNIMAGE_RUNTIME_V1",
    name="InternImage Runtime v1",
    runtimeType="MODEL_RUNTIME",
    supportedModelFamilies=["INTERNIMAGE"],
    pythonVersion="3.11",
    frameworkRequirements={
        "MMSegmentation": "0.27.0",
        "MMCV": "1.6.2",
        "PyTorch": "2.7.1",
        "torchvision": "0.22.1",
        "CUDA": "12.8",
        "InternImage": "31c962dc6c1ceb23e580772f7daaa6944694fbe6",
        "DCNv3": "1.0",
    },
    cudaRequired=True,
    gpuRequired=True,
    adapterType="INTERNIMAGE",
    healthCheckType="RUNTIME_AND_DEFINITION_SELF_CHECK",
    status=RuntimeStatus.ENABLED,
    runtimeMode="REMOTE_CONTAINER",
    serviceName="internimage-runtime",
    baseUrlEnvironmentVariable="INTERNIMAGE_RUNTIME_BASE_URL",
)


class RuntimeProfileRegistry:
    def __init__(self, profiles: list[RuntimeProfile] | None = None) -> None:
        configured = profiles if profiles is not None else [YOLO_RUNTIME_V1]
        if profiles is None and settings.INTERNIMAGE_RUNTIME_V1_ENABLED:
            configured.append(INTERNIMAGE_RUNTIME_V1)
        self._profiles = {profile.runtimeProfileId: profile for profile in configured}

    def get(self, runtime_profile_id: str) -> RuntimeProfile | None:
        return self._profiles.get(runtime_profile_id)

    def all(self) -> list[RuntimeProfile]:
        return list(self._profiles.values())


runtime_profile_registry = RuntimeProfileRegistry()
