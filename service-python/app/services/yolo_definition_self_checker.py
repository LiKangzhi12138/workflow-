from __future__ import annotations

from app.schemas.model_protocol import (
    AvailabilityReasonCode,
    DefinitionSelfCheckResult,
    ModelDefinition,
)
from app.services.yolo_runtime_client import (
    YoloRuntimeClient,
    YoloRuntimeClientError,
    yolo_runtime_client,
)


class YoloDefinitionSelfChecker:
    def __init__(self, runtime_client: YoloRuntimeClient | None = None) -> None:
        self.runtime_client = runtime_client or yolo_runtime_client

    def supports(self, definition: ModelDefinition) -> bool:
        return (
            definition.modelFamily.upper() == "YOLO"
            and definition.definitionType == "ULTRALYTICS_DETECTION_MODEL"
            and definition.runtimeProfileId == "YOLO_RUNTIME_V1"
        )

    def check(self, definition: ModelDefinition) -> DefinitionSelfCheckResult:
        try:
            response = self.runtime_client.check_definition(
                definition,
                definition.runtimeProfileId,
            )
        except YoloRuntimeClientError as exc:
            return DefinitionSelfCheckResult(
                passed=False,
                checkpointUsed=False,
                reasonCode=AvailabilityReasonCode.RUNTIME_CHECK_FAILED,
                message=str(exc),
            )
        return DefinitionSelfCheckResult(
            passed=response.available is True and response.selfCheckPassed is True,
            architectureSignature=definition.architectureSignature,
            checkpointUsed=False,
            reasonCode=(
                AvailabilityReasonCode.AVAILABLE
                if response.available is True and response.selfCheckPassed is True
                else response.reasonCode
            ),
            message=response.message,
        )


yolo_definition_self_checker = YoloDefinitionSelfChecker()
