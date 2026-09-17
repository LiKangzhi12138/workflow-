from __future__ import annotations

import base64
import json
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.schemas.model_protocol import (
    ModelDefinition,
    ModelDescriptor,
    ModelPackageInspectRequest,
    RuntimeGlobalWeightsInput,
    RuntimeWeightsValidationRequest,
    RuntimeWeightsValidationResult,
    WeightsPackageManifest,
)
from app.services.runtime_profile_registry import runtime_profile_registry
from app.services.weights_package_inspector import weights_package_inspector
from app.services.yolo_runtime_client import YoloRuntimeClientError, yolo_runtime_client


class WeightsValidationV1Error(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class WeightsValidationV1Service:
    def validate_job(
        self,
        job: dict[str, Any],
        *,
        sample_output_directory: str,
    ) -> RuntimeWeightsValidationResult:
        self._require(
            settings.WEIGHTS_VALIDATION_V1_ENABLED,
            "WEIGHTS_VALIDATION_V1_DISABLED",
            "Weights-only validation v1 is disabled.",
        )
        try:
            definition = ModelDefinition.model_validate(job.get("trustedModelDefinition"))
        except Exception as exc:
            raise WeightsValidationV1Error(
                "WEIGHTS_VALIDATION_V1_DEFINITION_INVALID",
                "The trusted ModelDefinition is invalid.",
            ) from exc
        runtime_profile_id = str(job.get("runtimeProfileId") or "")
        profile = runtime_profile_registry.get(runtime_profile_id)
        self._require(
            profile is not None and profile.runtimeMode == "REMOTE_CONTAINER",
            "RUNTIME_PROFILE_NOT_FOUND",
            "The requested model runtime profile is not registered.",
        )
        self._require(
            definition.runtimeProfileId == runtime_profile_id,
            "WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH",
            "The trusted ModelDefinition is bound to a different runtime profile.",
        )

        raw_weights = job.get("globalWeights")
        self._require(
            isinstance(raw_weights, dict),
            "WEIGHTS_VALIDATION_V1_INPUT_INVALID",
            "Global weights evidence is missing.",
        )
        manifest = self._read_model(raw_weights.get("manifestPath"), WeightsPackageManifest)
        descriptor = self._read_model(raw_weights.get("descriptorPath"), ModelDescriptor)
        inspection = weights_package_inspector.inspect(
            ModelPackageInspectRequest(
                weightsPath=str(raw_weights.get("weightsPath") or ""),
                manifest=manifest,
                descriptor=descriptor,
            )
        )
        self._require(
            inspection.passed,
            "WEIGHTS_VALIDATION_V1_INSPECTION_FAILED",
            "Global weights failed the Protocol v1 safe inspection.",
        )

        expected_id = str(definition.definitionId)
        self._require(
            str(manifest.modelDefinition.definitionId) == expected_id
            and str(descriptor.modelDefinition.definitionId) == expected_id
            and manifest.modelDefinition.definitionSha256.lower() == definition.definitionSha256.lower()
            and descriptor.modelDefinition.definitionSha256.lower() == definition.definitionSha256.lower()
            and manifest.architectureSignature == definition.architectureSignature,
            "WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH",
            "Global weights evidence does not match the trusted ModelDefinition.",
        )
        self._require(
            inspection.weightsSha256 is not None
            and inspection.weightsSha256.lower() == str(raw_weights.get("expectedSha256") or "").lower(),
            "WEIGHTS_VALIDATION_V1_SHA_MISMATCH",
            "Global weights SHA256 does not match server evidence.",
        )
        expected_checks = {
            "tensorCount": inspection.tensorCount,
            "elementCount": inspection.elementCount,
            "stateDictKeyHash": inspection.stateDictKeyHash,
            "shapeSignature": inspection.shapeSignature,
            "dtypeSignature": inspection.dtypeSignature,
        }
        provided_checks = {
            "tensorCount": raw_weights.get("expectedTensorCount"),
            "elementCount": raw_weights.get("expectedElementCount"),
            "stateDictKeyHash": raw_weights.get("expectedStateDictKeyHash"),
            "shapeSignature": raw_weights.get("expectedShapeSignature"),
            "dtypeSignature": raw_weights.get("expectedDtypeSignature"),
        }
        self._require(
            expected_checks == provided_checks,
            "WEIGHTS_VALIDATION_V1_EVIDENCE_MISMATCH",
            "Global weights inspection evidence changed before validation.",
        )

        runtime_request = RuntimeWeightsValidationRequest(
            workflowId=int(job["workflowId"]),
            jobId=str(job["jobId"]),
            modelDefinition=definition,
            runtimeProfileId=runtime_profile_id,
            globalWeights=RuntimeGlobalWeightsInput(
                assetId=int(raw_weights["assetId"]),
                weightsPath=str(raw_weights["weightsPath"]),
                expectedSha256=str(raw_weights["expectedSha256"]),
                expectedTensorCount=int(raw_weights["expectedTensorCount"]),
                expectedElementCount=int(raw_weights["expectedElementCount"]),
                expectedStateDictKeyHash=str(raw_weights["expectedStateDictKeyHash"]),
                expectedShapeSignature=str(raw_weights["expectedShapeSignature"]),
                expectedDtypeSignature=str(raw_weights["expectedDtypeSignature"]),
            ),
            datasetPath=str(job.get("datasetPath") or ""),
            sampleOutputDirectory=sample_output_directory,
            algorithmType=str(job.get("algorithmType") or definition.version),
        )
        try:
            result = yolo_runtime_client.validate_weights(runtime_request)
        except YoloRuntimeClientError as exc:
            raise WeightsValidationV1Error(
                "WEIGHTS_VALIDATION_V1_RUNTIME_FAILED",
                "Model runtime validation request failed.",
            ) from exc

        self._require(
            result.passed
            and str(result.modelDefinitionId) == expected_id
            and result.modelDefinitionCode == definition.code
            and result.definitionSha256.lower() == definition.definitionSha256.lower()
            and result.architectureSignature.lower() == definition.architectureSignature.lower()
            and result.weightsSha256.lower() == str(raw_weights["expectedSha256"]).lower()
            and result.runtimeProfileId == runtime_profile_id
            and result.reconstruction.get("weightsOnly") is True
            and result.reconstruction.get("checkpointUsed") is False
            and result.reconstruction.get("baseCheckpointUsed") is False
            and result.reconstruction.get("strictLoad") is True
            and result.reconstruction.get("missingKeyCount") == 0
            and result.reconstruction.get("unexpectedKeyCount") == 0,
            "WEIGHTS_VALIDATION_V1_RUNTIME_EVIDENCE_INVALID",
            "Model runtime returned invalid reconstruction evidence.",
        )
        self._materialize_sample_images(result.validationResult, sample_output_directory)
        return result

    def _materialize_sample_images(
        self,
        validation_result: dict[str, Any],
        sample_output_directory: str,
    ) -> None:
        sample_directory = Path(sample_output_directory).resolve(strict=False)
        allowed_root = settings.validation_sample_dir("stage4-boundary").parent.resolve(strict=False)
        self._require(
            sample_directory != allowed_root and sample_directory.is_relative_to(allowed_root),
            "WEIGHTS_VALIDATION_V1_OUTPUT_PATH_INVALID",
            "Validation sample output path is outside the configured root.",
        )
        sample_directory.mkdir(parents=True, exist_ok=True)
        for index, sample in enumerate(validation_result.get("sample_results") or []):
            if not isinstance(sample, dict):
                continue
            encoded = sample.pop("rendered_image_base64", None)
            if not encoded:
                continue
            try:
                content = base64.b64decode(encoded, validate=True)
            except Exception as exc:
                raise WeightsValidationV1Error(
                    "WEIGHTS_VALIDATION_V1_RUNTIME_EVIDENCE_INVALID",
                    "Model runtime returned an invalid annotated sample.",
                ) from exc
            self._require(
                0 < len(content) <= 10 * 1024 * 1024,
                "WEIGHTS_VALIDATION_V1_RUNTIME_EVIDENCE_INVALID",
                "Model runtime annotated sample exceeds the allowed size.",
            )
            output_path = sample_directory / f"sample_{index:03d}_rendered.jpg"
            output_path.write_bytes(content)
            sample["annotated_image_path"] = str(output_path)
            sample["annotated_image_available"] = True

    def _read_model(self, raw_path: Any, model_type):
        try:
            root = Path(settings.PY_STORAGE_ROOT).parent.resolve(strict=False)
            path = Path(str(raw_path or "")).resolve(strict=False)
            if (
                path == root
                or not path.is_relative_to(root)
                or not path.is_file()
                or path.is_symlink()
                or path.stat().st_size <= 0
                or path.stat().st_size > 10 * 1024 * 1024
            ):
                raise ValueError("sidecar path is outside the configured storage root")
            return model_type.model_validate(json.loads(path.read_text(encoding="utf-8")))
        except Exception as exc:
            raise WeightsValidationV1Error(
                "WEIGHTS_VALIDATION_V1_EVIDENCE_INVALID",
                "Global weights sidecar evidence is invalid.",
            ) from exc

    def _require(self, condition: bool, code: str, message: str) -> None:
        if not condition:
            raise WeightsValidationV1Error(code, message)


weights_validation_v1_service = WeightsValidationV1Service()
