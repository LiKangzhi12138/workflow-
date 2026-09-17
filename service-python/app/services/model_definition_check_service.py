from __future__ import annotations

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    DefinitionStatus,
    ModelAvailabilityResult,
    ModelDefinition,
)
from app.services.model_definition_integrity_service import (
    ModelDefinitionIntegrityService,
    model_definition_integrity_service,
)
from app.services.runtime_capability_probe import (
    RuntimeCapabilityProbe,
    runtime_capability_probe,
)
from app.services.runtime_profile_registry import (
    RuntimeProfileRegistry,
    runtime_profile_registry,
)
from app.services.yolo_definition_self_checker import (
    YoloDefinitionSelfChecker,
    yolo_definition_self_checker,
)
from app.services.runtime_definition_self_checker import (
    RuntimeDefinitionSelfChecker,
    runtime_definition_self_checker,
)


class ModelDefinitionCheckService:
    def __init__(
        self,
        integrity_service: ModelDefinitionIntegrityService | None = None,
        profile_registry: RuntimeProfileRegistry | None = None,
        capability_probe: RuntimeCapabilityProbe | None = None,
        definition_self_checker: RuntimeDefinitionSelfChecker | None = None,
        yolo_self_checker: YoloDefinitionSelfChecker | None = None,
    ) -> None:
        self.integrity_service = integrity_service or model_definition_integrity_service
        self.profile_registry = profile_registry or runtime_profile_registry
        self.capability_probe = capability_probe or runtime_capability_probe
        self.definition_self_checker = (
            definition_self_checker
            or yolo_self_checker
            or runtime_definition_self_checker
        )

    def check(
        self,
        definition: ModelDefinition,
        runtime_profile_id: str,
        *,
        probe_context: str | None = None,
    ) -> ModelAvailabilityResult:
        if definition.status != DefinitionStatus.ENABLED:
            return self._result(
                definition,
                reason=AvailabilityReasonCode.DISABLED,
                message="ModelDefinition is disabled.",
                definition_enabled=False,
            )

        integrity = self.integrity_service.validate(definition)
        if not integrity.valid:
            return self._result(
                definition,
                reason=integrity.reason_code,
                message=integrity.message,
                definition_enabled=True,
                definition_valid=False,
            )

        profile = self.profile_registry.get(runtime_profile_id)
        if profile is None or definition.runtimeProfileId != runtime_profile_id:
            return self._result(
                definition,
                reason=AvailabilityReasonCode.RUNTIME_PROFILE_NOT_FOUND,
                message="Requested RuntimeProfile is missing or does not match ModelDefinition.",
                definition_enabled=True,
                definition_valid=True,
            )

        expected_framework_version = profile.frameworkRequirements.get(definition.framework)
        if (
            definition.modelFamily not in profile.supportedModelFamilies
            or expected_framework_version is None
            or expected_framework_version != definition.frameworkVersion
        ):
            return self._result(
                definition,
                reason=AvailabilityReasonCode.FRAMEWORK_VERSION_MISMATCH,
                message=(
                    "ModelDefinition framework declaration does not match its trusted "
                    "RuntimeProfile."
                ),
                definition_enabled=True,
                definition_valid=False,
            )

        adapter_available = self.definition_self_checker.supports(definition)
        if not adapter_available:
            return self._result(
                definition,
                reason=AvailabilityReasonCode.ADAPTER_NOT_REGISTERED,
                message="No definition self-check capability is registered for this model type.",
                definition_enabled=True,
                definition_valid=True,
                adapter_available=False,
            )

        probe = self.capability_probe.probe(profile, probe_context=probe_context)
        if not probe.evaluated:
            return self._result(
                definition,
                reason=probe.reasonCode,
                message=probe.message,
                definition_enabled=True,
                definition_valid=True,
                adapter_available=True,
                runtime_evaluated=False,
                runtime_available=(
                    None
                    if probe.reasonCode == AvailabilityReasonCode.NOT_EVALUATED
                    else False
                ),
                probe=probe,
            )
        if probe.ready is not True:
            return self._result(
                definition,
                reason=probe.reasonCode,
                message=probe.message,
                definition_enabled=True,
                definition_valid=True,
                adapter_available=True,
                runtime_evaluated=True,
                runtime_available=False,
                probe=probe,
            )

        self_check = self.definition_self_checker.check(definition)
        if not self_check.passed:
            return self._result(
                definition,
                reason=self_check.reasonCode,
                message=self_check.message,
                definition_enabled=True,
                definition_valid=True,
                adapter_available=True,
                runtime_evaluated=True,
                runtime_available=True,
                self_check_passed=False,
                probe=probe,
            )

        return self._result(
            definition,
            reason=AvailabilityReasonCode.AVAILABLE,
            message="ModelDefinition, runtime capability, and architecture-only self-check passed.",
            definition_enabled=True,
            definition_valid=True,
            adapter_available=True,
            runtime_evaluated=True,
            runtime_available=True,
            self_check_passed=True,
            probe=probe,
        )

    @staticmethod
    def _result(
        definition: ModelDefinition,
        *,
        reason: AvailabilityReasonCode,
        message: str,
        definition_enabled: bool | None = None,
        definition_valid: bool | None = None,
        adapter_available: bool | None = None,
        runtime_evaluated: bool | None = None,
        runtime_available: bool | None = None,
        self_check_passed: bool | None = None,
        probe=None,
    ) -> ModelAvailabilityResult:
        if reason == AvailabilityReasonCode.AVAILABLE:
            availability_state = "AVAILABLE"
        elif reason == AvailabilityReasonCode.NOT_EVALUATED:
            availability_state = "NOT_EVALUATED"
        else:
            availability_state = "UNAVAILABLE"
        return ModelAvailabilityResult(
            available=reason == AvailabilityReasonCode.AVAILABLE,
            availabilityState=availability_state,
            definitionId=definition.definitionId,
            definitionCode=definition.code,
            modelFamily=definition.modelFamily,
            variant=definition.variant,
            taskType=definition.taskType,
            adapterAvailable=adapter_available,
            runtimeAvailable=runtime_available,
            definitionValid=definition_valid,
            definitionEnabled=definition_enabled,
            runtimeEvaluated=runtime_evaluated,
            selfCheckPassed=self_check_passed,
            probeContext=probe.probeContext if probe is not None else None,
            detectedPythonVersion=probe.detectedPythonVersion if probe is not None else None,
            detectedFrameworkVersion=probe.detectedFrameworkVersion if probe is not None else None,
            detectedTorchVersion=probe.detectedTorchVersion if probe is not None else None,
            cudaAvailable=probe.cudaAvailable if probe is not None else None,
            gpuName=probe.gpuName if probe is not None else None,
            reasonCode=reason,
            message=message,
        )


model_definition_check_service = ModelDefinitionCheckService()
