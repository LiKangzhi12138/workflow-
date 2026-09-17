from __future__ import annotations

from app.schemas.model_protocol import AvailabilityReasonCode, DefinitionSelfCheckResult, ModelDefinition
from app.services.internimage_runtime_client import InternImageRuntimeClientError
from app.services.runtime_client_registry import RuntimeClientRegistry, runtime_client_registry
from app.services.yolo_runtime_client import YoloRuntimeClientError


class RuntimeDefinitionSelfChecker:
    def __init__(self, client_registry: RuntimeClientRegistry | None = None) -> None:
        self.client_registry = client_registry or runtime_client_registry

    def supports(self, definition: ModelDefinition) -> bool:
        supported_contracts = {
            ("YOLO", "ULTRALYTICS_DETECTION_MODEL", "YOLO_RUNTIME_V1"),
            ("INTERNIMAGE", "MMSEG_TRUSTED_CONFIG_TEMPLATE", "INTERNIMAGE_RUNTIME_V1"),
        }
        return (
            definition.modelFamily.upper(),
            definition.definitionType,
            definition.runtimeProfileId,
        ) in supported_contracts and self.client_registry.get(definition.runtimeProfileId) is not None

    def check(self, definition: ModelDefinition) -> DefinitionSelfCheckResult:
        client = self.client_registry.get(definition.runtimeProfileId)
        if client is None:
            return DefinitionSelfCheckResult(
                passed=False,
                checkpointUsed=False,
                reasonCode=AvailabilityReasonCode.ADAPTER_NOT_REGISTERED,
                message="No runtime client is registered for this RuntimeProfile.",
            )
        try:
            response = client.check_definition(definition, definition.runtimeProfileId)
        except (YoloRuntimeClientError, InternImageRuntimeClientError) as exc:
            return DefinitionSelfCheckResult(
                passed=False,
                checkpointUsed=False,
                reasonCode=AvailabilityReasonCode.RUNTIME_CHECK_FAILED,
                message=str(exc),
            )
        passed = response.available is True and response.selfCheckPassed is True
        return DefinitionSelfCheckResult(
            passed=passed,
            architectureSignature=definition.architectureSignature,
            checkpointUsed=False,
            reasonCode=AvailabilityReasonCode.AVAILABLE if passed else response.reasonCode,
            message=response.message,
        )


runtime_definition_self_checker = RuntimeDefinitionSelfChecker()
