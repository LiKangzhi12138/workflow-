from __future__ import annotations

from app.schemas.model_protocol import AvailabilityReasonCode, RuntimeProbeResult, RuntimeProfile, RuntimeStatus
from app.services.internimage_runtime_client import InternImageRuntimeClientError
from app.services.runtime_client_registry import RuntimeClientRegistry, runtime_client_registry
from app.services.yolo_runtime_client import YoloRuntimeClientError


def _base_version(value: str | None) -> str | None:
    return value.split("+", 1)[0] if value else None


def _detected_requirement(health, name: str) -> tuple[bool, str | None]:
    mapping = {
        "PyTorch": (health.torchAvailable, health.torchVersion),
        "torchvision": (health.torchvisionAvailable, health.torchvisionVersion),
        "Ultralytics": (health.ultralyticsAvailable, health.ultralyticsVersion),
        "MMSegmentation": (bool(health.mmsegAvailable), health.mmsegVersion),
        "MMCV": (bool(health.mmcvAvailable), health.mmcvVersion),
        "InternImage": (bool(health.internimageAvailable), health.internimageVersion),
        "DCNv3": (bool(health.dcnv3Available), health.dcnv3Version),
        "CUDA": (health.cudaAvailable, health.cudaBuildVersion),
    }
    return mapping.get(name, (False, None))


class RuntimeCapabilityProbe:
    def __init__(self, runtime_client=None, client_registry: RuntimeClientRegistry | None = None) -> None:
        # runtime_client preserves the focused-test constructor contract.
        self.runtime_client = runtime_client
        self.client_registry = client_registry or runtime_client_registry

    def probe(self, profile: RuntimeProfile, *, probe_context: str | None = None) -> RuntimeProbeResult:
        if profile.status != RuntimeStatus.ENABLED:
            return self._failure(profile, AvailabilityReasonCode.RUNTIME_NOT_READY, "RuntimeProfile is disabled.", True)
        if profile.runtimeMode != "REMOTE_CONTAINER":
            return self._failure(
                profile,
                AvailabilityReasonCode.NOT_EVALUATED,
                "RuntimeProfile is not configured as a remote container runtime.",
                False,
            )

        client = self.runtime_client or self.client_registry.get(profile.runtimeProfileId)
        if client is None:
            return self._failure(
                profile,
                AvailabilityReasonCode.RUNTIME_PROFILE_NOT_FOUND,
                "No remote client is configured for RuntimeProfile.",
                True,
            )
        try:
            health = client.health()
        except (YoloRuntimeClientError, InternImageRuntimeClientError) as exc:
            return self._failure(profile, AvailabilityReasonCode.RUNTIME_CHECK_FAILED, str(exc), False)

        if health.runtimeProfileId != profile.runtimeProfileId:
            return self._from_health(
                profile,
                health,
                False,
                AvailabilityReasonCode.RUNTIME_PROFILE_NOT_FOUND,
                "Remote runtime returned an unexpected RuntimeProfile id.",
            )
        if health.probeContext != "CONTAINER_RUNTIME":
            return self._from_health(
                profile,
                health,
                None,
                AvailabilityReasonCode.NOT_EVALUATED,
                "Remote response did not originate from CONTAINER_RUNTIME.",
                False,
            )
        if not health.pythonVersion.startswith(profile.pythonVersion + "."):
            return self._from_health(
                profile,
                health,
                False,
                AvailabilityReasonCode.PYTHON_VERSION_MISMATCH,
                f"Python {health.pythonVersion} does not satisfy {profile.pythonVersion}.x.",
            )

        for name, expected in profile.frameworkRequirements.items():
            available, detected = _detected_requirement(health, name)
            if not available:
                reason = (
                    AvailabilityReasonCode.COMPILED_OP_NOT_AVAILABLE
                    if name == "DCNv3"
                    else AvailabilityReasonCode.CUDA_NOT_AVAILABLE
                    if name == "CUDA"
                    else AvailabilityReasonCode.FRAMEWORK_NOT_AVAILABLE
                )
                return self._from_health(profile, health, False, reason, f"Remote {name} dependency is unavailable.")
            comparable = _base_version(detected) if name in {"PyTorch", "torchvision"} else detected
            if comparable != expected:
                reason = (
                    AvailabilityReasonCode.CUDA_NOT_AVAILABLE
                    if name == "CUDA"
                    else AvailabilityReasonCode.FRAMEWORK_VERSION_MISMATCH
                )
                return self._from_health(
                    profile,
                    health,
                    False,
                    reason,
                    f"Remote {name} version does not match RuntimeProfile.",
                )

        checks = [
            (
                not profile.cudaRequired or health.cudaAvailable,
                AvailabilityReasonCode.CUDA_NOT_AVAILABLE,
                "CUDA is required but unavailable in the remote runtime.",
            ),
            (
                not profile.gpuRequired or health.gpuAvailable,
                AvailabilityReasonCode.RUNTIME_NOT_READY,
                "A visible GPU is required but unavailable in the remote runtime.",
            ),
            (
                profile.adapterType != "INTERNIMAGE" or health.dcnv3SelfCheckPassed is True,
                AvailabilityReasonCode.COMPILED_OP_NOT_AVAILABLE,
                "DCNv3 minimal CUDA forward self-check did not pass.",
            ),
            (
                health.runtimeReady,
                AvailabilityReasonCode.RUNTIME_NOT_READY,
                "Remote runtime readiness checks failed.",
            ),
        ]
        for passed, reason, message in checks:
            if not passed:
                return self._from_health(profile, health, False, reason, message)
        return self._from_health(
            profile,
            health,
            True,
            AvailabilityReasonCode.AVAILABLE,
            f"Remote {profile.name} capability probe passed.",
        )

    @staticmethod
    def _from_health(
        profile: RuntimeProfile,
        health,
        ready: bool | None,
        reason: AvailabilityReasonCode,
        message: str,
        evaluated: bool = True,
    ) -> RuntimeProbeResult:
        primary_framework = "Ultralytics" if "Ultralytics" in profile.frameworkRequirements else "MMSegmentation"
        framework_available, framework_version = _detected_requirement(health, primary_framework)
        return RuntimeProbeResult(
            runtimeProfileId=health.runtimeProfileId,
            probeContext=health.probeContext,
            evaluated=evaluated,
            ready=ready,
            pythonAvailable=True,
            detectedPythonVersion=health.pythonVersion,
            torchAvailable=health.torchAvailable,
            detectedTorchVersion=health.torchVersion,
            detectedTorchvisionVersion=health.torchvisionVersion,
            frameworkAvailable=framework_available,
            detectedFrameworkVersion=framework_version,
            frameworkVersionMatch=framework_version == profile.frameworkRequirements.get(primary_framework),
            cudaAvailable=health.cudaAvailable,
            cudaBuildVersion=health.cudaBuildVersion,
            gpuAvailable=health.gpuAvailable,
            gpuName=health.gpuName,
            reasonCode=reason,
            message=message,
        )

    @staticmethod
    def _failure(
        profile: RuntimeProfile,
        reason: AvailabilityReasonCode,
        message: str,
        evaluated: bool,
    ) -> RuntimeProbeResult:
        return RuntimeProbeResult(
            runtimeProfileId=profile.runtimeProfileId,
            probeContext="REMOTE_RUNTIME",
            evaluated=evaluated,
            ready=False if reason != AvailabilityReasonCode.NOT_EVALUATED else None,
            pythonAvailable=False,
            detectedPythonVersion="unknown",
            torchAvailable=False,
            frameworkAvailable=False,
            reasonCode=reason,
            message=message,
        )


runtime_capability_probe = RuntimeCapabilityProbe()
