from __future__ import annotations

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    DefinitionStatus,
    ModelAvailabilityResult,
    ModelDefinition,
)


class ModelAvailabilityEvaluator:
    """Pure availability semantics; registries and runtime probes arrive in Stage 1B."""

    def evaluate(
        self,
        definition: ModelDefinition,
        *,
        definition_valid: bool | None = None,
        adapter_available: bool | None = None,
        runtime_available: bool | None = None,
    ) -> ModelAvailabilityResult:
        reason = AvailabilityReasonCode.NOT_EVALUATED
        message = "Model availability has not been fully evaluated."

        if definition.status != DefinitionStatus.ENABLED:
            reason = AvailabilityReasonCode.DISABLED
            message = "Model definition is disabled."
        elif definition_valid is False:
            reason = AvailabilityReasonCode.DEFINITION_INVALID
            message = "Model definition validation failed."
        elif adapter_available is False:
            reason = AvailabilityReasonCode.ADAPTER_NOT_REGISTERED
            message = "No adapter is registered for this model definition."
        elif runtime_available is False:
            reason = AvailabilityReasonCode.RUNTIME_NOT_READY
            message = "The required runtime is not ready."
        elif all(value is True for value in (definition_valid, adapter_available, runtime_available)):
            reason = AvailabilityReasonCode.AVAILABLE
            message = "Model definition, adapter, and runtime are available."

        return ModelAvailabilityResult(
            available=reason == AvailabilityReasonCode.AVAILABLE,
            definitionId=definition.definitionId,
            definitionCode=definition.code,
            modelFamily=definition.modelFamily,
            variant=definition.variant,
            taskType=definition.taskType,
            adapterAvailable=adapter_available,
            runtimeAvailable=runtime_available,
            definitionValid=definition_valid,
            reasonCode=reason,
            message=message,
        )


model_availability_evaluator = ModelAvailabilityEvaluator()
