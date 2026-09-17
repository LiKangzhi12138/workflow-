from __future__ import annotations

from collections import OrderedDict

import torch

from app.schemas.model_protocol import (
    InspectionReport,
    ModelPackageInspectRequest,
    ModelPackageValidationRequest,
    ModelPackageValidationResult,
    ProtocolErrorCode,
    RuntimeWeightsCompatibilityRequest,
    RuntimeWeightsCompatibilityResult,
    StateTensorMetadata,
)
from app.services.model_definition_integrity_service import ModelDefinitionIntegrityService
from app.services.weights_package_inspector import WeightsPackageInspector
from app.services.runtime_client_error import RuntimeClientError
from app.services.runtime_client_registry import RuntimeClientRegistry, runtime_client_registry


class ModelPackageValidationService:
    """Validate an uploaded package against a server-selected trusted Definition."""

    def __init__(
        self,
        inspector: WeightsPackageInspector | None = None,
        runtime_client=None,
        client_registry: RuntimeClientRegistry | None = None,
    ) -> None:
        self.inspector = inspector or WeightsPackageInspector()
        self.runtime_client = runtime_client
        self.client_registry = client_registry or runtime_client_registry
        self.integrity_service = ModelDefinitionIntegrityService()

    def validate(self, request: ModelPackageValidationRequest) -> ModelPackageValidationResult:
        inspection = self.inspector.inspect(
            ModelPackageInspectRequest(
                weightsPath=request.weightsPath,
                manifest=request.manifest,
                descriptor=request.descriptor,
            )
        )
        if not inspection.passed:
            codes = {issue.code for issue in inspection.errors}
            reason = (
                "WEIGHTS_SHA_MISMATCH"
                if ProtocolErrorCode.WEIGHTS_SHA256_MISMATCH in codes
                else "WEIGHTS_DEFINITION_MISMATCH"
                if ProtocolErrorCode.DEFINITION_MISMATCH in codes
                else "WEIGHTS_SHAPE_MISMATCH"
                if ProtocolErrorCode.SHAPE_SIGNATURE_MISMATCH in codes
                else "WEIGHTS_DTYPE_UNSUPPORTED"
                if ProtocolErrorCode.UNSUPPORTED_DTYPE in codes
                else "WEIGHTS_INSPECTION_FAILED"
            )
            return self._failed(reason, "Weights package inspection failed.", inspection)

        definition = request.trustedModelDefinition
        integrity = self.integrity_service.validate(definition)
        if not integrity.valid:
            return self._failed(
                "WEIGHTS_DEFINITION_MISMATCH",
                "Trusted ModelDefinition integrity validation failed.",
                inspection,
            )

        manifest = request.manifest
        descriptor = request.descriptor
        expected_reference = (definition.definitionId, definition.definitionSha256)
        declared_references = (
            (manifest.modelDefinition.definitionId, manifest.modelDefinition.definitionSha256),
            (descriptor.modelDefinition.definitionId, descriptor.modelDefinition.definitionSha256),
        )
        if any(reference != expected_reference for reference in declared_references):
            return self._failed(
                ProtocolErrorCode.WEIGHTS_DEFINITION_MISMATCH.value,
                "Package ModelDefinition declaration does not match the workflow Definition.",
                inspection,
            )
        if manifest.architectureSignature != definition.architectureSignature:
            return self._failed(
                ProtocolErrorCode.WEIGHTS_ARCHITECTURE_MISMATCH.value,
                "Package architecture signature does not match the workflow Definition.",
                inspection,
            )
        if not self._descriptor_matches_definition(request):
            return self._failed(
                ProtocolErrorCode.WEIGHTS_DEFINITION_MISMATCH.value,
                "Package descriptor metadata does not match the workflow Definition.",
                inspection,
            )

        try:
            payload = torch.load(request.weightsPath, map_location="cpu", weights_only=True)
            state_dict = payload["state_dict"]
            if not isinstance(state_dict, OrderedDict):
                raise TypeError("state_dict is not OrderedDict")
            runtime_request = RuntimeWeightsCompatibilityRequest(
                modelDefinition=definition,
                runtimeProfileId=definition.runtimeProfileId,
                state=[
                    StateTensorMetadata(
                        key=key,
                        shape=list(tensor.shape),
                        dtype=str(tensor.dtype).removeprefix("torch."),
                    )
                    for key, tensor in state_dict.items()
                ],
            )
            runtime_client = self.runtime_client or self.client_registry.get(
                definition.runtimeProfileId
            )
            if runtime_client is None or not callable(
                getattr(runtime_client, "check_weights_compatibility", None)
            ):
                return self._failed(
                    "WEIGHTS_COMPATIBILITY_FAILED",
                    "No compatibility client is registered for the trusted RuntimeProfile.",
                    inspection,
                )
            compatibility = runtime_client.check_weights_compatibility(runtime_request)
        except RuntimeClientError:
            return self._failed(
                "WEIGHTS_COMPATIBILITY_FAILED",
                "Model runtime compatibility check is unavailable.",
                inspection,
            )
        except Exception as exc:
            return self._failed(
                "WEIGHTS_INSPECTION_FAILED",
                f"Safe state metadata extraction failed: {type(exc).__name__}.",
                inspection,
            )

        if not compatibility.compatible:
            return ModelPackageValidationResult(
                passed=False,
                reasonCode=compatibility.reasonCode,
                message=compatibility.message,
                inspection=inspection,
                compatibility=compatibility,
            )
        return ModelPackageValidationResult(
            passed=True,
            reasonCode="AVAILABLE",
            message="Weights package matches the trusted ModelDefinition.",
            inspection=inspection,
            compatibility=compatibility,
        )

    @staticmethod
    def _descriptor_matches_definition(request: ModelPackageValidationRequest) -> bool:
        descriptor = request.descriptor
        definition = request.trustedModelDefinition
        return (
            descriptor.modelFamily == definition.modelFamily
            and descriptor.version == definition.version
            and descriptor.variant == definition.variant
            and descriptor.taskType == definition.taskType
            and descriptor.framework.name == definition.framework
            and descriptor.framework.version == definition.frameworkVersion
            and descriptor.classes.count == definition.classCount
            and descriptor.classes.order == definition.classOrder
            and descriptor.classes.ignoreIndex == definition.ignoreIndex
            and descriptor.parameterSemantics
            == definition.definitionPayload.get("parameterSemantics")
        )

    @staticmethod
    def _failed(reason: str, message: str, inspection: InspectionReport) -> ModelPackageValidationResult:
        return ModelPackageValidationResult(
            passed=False,
            reasonCode=reason,
            message=message,
            inspection=inspection,
        )


model_package_validation_service = ModelPackageValidationService()
