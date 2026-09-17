from __future__ import annotations

from dataclasses import dataclass

from app.schemas.model_protocol import AvailabilityReasonCode, ModelDefinition
from app.services.weights_package_inspector import canonical_json_sha256


@dataclass(frozen=True)
class DefinitionIntegrityResult:
    valid: bool
    reason_code: AvailabilityReasonCode
    message: str
    computed_definition_sha256: str
    computed_architecture_signature: str | None


def canonical_definition_sha256(definition: ModelDefinition) -> str:
    payload = definition.model_dump(mode="json")
    # The digest field is blanked to avoid a self-referential hash contract.
    payload["definitionSha256"] = ""
    return canonical_json_sha256(payload)


class ModelDefinitionIntegrityService:
    def validate(self, definition: ModelDefinition) -> DefinitionIntegrityResult:
        computed_definition_sha256 = canonical_definition_sha256(definition)
        architecture = definition.definitionPayload.get("architecture")
        computed_architecture_signature = (
            canonical_json_sha256(architecture) if isinstance(architecture, dict) else None
        )

        if definition.schemaVersion != "1.0":
            return self._invalid(
                AvailabilityReasonCode.DEFINITION_INVALID,
                "Only ModelDefinition schemaVersion=1.0 is supported.",
                computed_definition_sha256,
                computed_architecture_signature,
            )
        if definition.definitionSha256.lower() != computed_definition_sha256.lower():
            return self._invalid(
                AvailabilityReasonCode.DEFINITION_SHA_MISMATCH,
                "ModelDefinition canonical SHA256 does not match definitionSha256.",
                computed_definition_sha256,
                computed_architecture_signature,
            )
        if computed_architecture_signature is None:
            return self._invalid(
                AvailabilityReasonCode.DEFINITION_INVALID,
                "ModelDefinition definitionPayload.architecture must be an object.",
                computed_definition_sha256,
                computed_architecture_signature,
            )
        if definition.architectureSignature.lower() != computed_architecture_signature.lower():
            return self._invalid(
                AvailabilityReasonCode.ARCHITECTURE_SIGNATURE_MISMATCH,
                "Model architecture canonical signature does not match architectureSignature.",
                computed_definition_sha256,
                computed_architecture_signature,
            )
        if definition.classCount != len(definition.classOrder):
            return self._invalid(
                AvailabilityReasonCode.CLASS_ORDER_MISMATCH,
                "classCount does not match classOrder length.",
                computed_definition_sha256,
                computed_architecture_signature,
            )

        names = definition.definitionPayload.get("names")
        expected_names = {str(index): name for index, name in enumerate(definition.classOrder)}
        normalized_names = {str(key): value for key, value in names.items()} if isinstance(names, dict) else None
        if normalized_names != expected_names:
            return self._invalid(
                AvailabilityReasonCode.CLASS_ORDER_MISMATCH,
                "definitionPayload.names does not match classOrder.",
                computed_definition_sha256,
                computed_architecture_signature,
            )

        return DefinitionIntegrityResult(
            valid=True,
            reason_code=AvailabilityReasonCode.AVAILABLE,
            message="ModelDefinition integrity validation passed.",
            computed_definition_sha256=computed_definition_sha256,
            computed_architecture_signature=computed_architecture_signature,
        )

    @staticmethod
    def _invalid(
        reason_code: AvailabilityReasonCode,
        message: str,
        definition_sha256: str,
        architecture_signature: str | None,
    ) -> DefinitionIntegrityResult:
        return DefinitionIntegrityResult(
            valid=False,
            reason_code=reason_code,
            message=message,
            computed_definition_sha256=definition_sha256,
            computed_architecture_signature=architecture_signature,
        )


model_definition_integrity_service = ModelDefinitionIntegrityService()
