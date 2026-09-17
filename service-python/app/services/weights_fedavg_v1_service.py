from __future__ import annotations

import json
import math
import os
import shutil
import uuid
from collections import OrderedDict
from pathlib import Path
from typing import Any

import torch

from app.core.config import settings
from app.schemas.model_protocol import (
    ModelDescriptor,
    ModelPackageInspectRequest,
    WeightsFedAvgInput,
    WeightsFedAvgRequest,
    WeightsFedAvgResult,
    WeightsPackageManifest,
)
from app.services.model_definition_integrity_service import ModelDefinitionIntegrityService
from app.services.weights_package_inspector import (
    WeightsPackageInspector,
    canonical_json_sha256,
    sha256_file,
    state_signatures,
)


class WeightsFedAvgError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class WeightsFedAvgV1Service:
    """Model-independent, fail-closed FedAvg for inspected Protocol v1 assets."""

    MAX_SIDECAR_BYTES = 1024 * 1024

    def __init__(self, input_root: Path | None = None, output_root: Path | None = None) -> None:
        self.input_root = (input_root or Path(settings.WEIGHTS_FEDAVG_INPUT_ROOT)).resolve()
        self.output_root = (output_root or Path(settings.WEIGHTS_FEDAVG_OUTPUT_ROOT)).resolve()
        self.integrity_service = ModelDefinitionIntegrityService()
        self.inspector = WeightsPackageInspector(allowed_roots=[self.input_root, self.output_root])

    def aggregate(self, request: WeightsFedAvgRequest) -> WeightsFedAvgResult:
        definition = request.trustedModelDefinition
        self._require(
            request.aggregationMethod == "EQUAL_FEDAVG",
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "Only server-controlled equal FedAvg is supported.",
        )
        integrity = self.integrity_service.validate(definition)
        self._require(
            integrity.valid,
            "WEIGHTS_FEDAVG_DEFINITION_MISMATCH",
            "Trusted ModelDefinition integrity validation failed.",
        )
        self._require_unique_inputs(request.inputs)

        states: list[OrderedDict[str, torch.Tensor]] = []
        input_evidence: list[dict[str, Any]] = []
        reference_contract: tuple[str, str, str] | None = None
        reference_descriptor: ModelDescriptor | None = None

        for item in request.inputs:
            state, evidence, descriptor = self._load_verified_input(item, definition)
            contract = (
                evidence["stateDictKeyHash"],
                evidence["shapeSignature"],
                evidence["dtypeSignature"],
            )
            if reference_contract is None:
                reference_contract = contract
                reference_descriptor = descriptor
            else:
                self._require(
                    contract == reference_contract,
                    "WEIGHTS_FEDAVG_STATE_MISMATCH",
                    "Input state contracts are not identical.",
                )
            states.append(state)
            input_evidence.append(evidence)

        weights = [1.0 / len(states)] * len(states)
        global_state = self._type_safe_fedavg(states, weights)
        output_directory = self._resolve_output_directory(request.outputDirectory)

        try:
            output_directory.mkdir(parents=True, exist_ok=False)
            result = self._write_and_inspect_output(
                request,
                global_state,
                weights,
                input_evidence,
                reference_descriptor,
                output_directory,
            )
            return result
        except Exception:
            if output_directory.exists():
                shutil.rmtree(output_directory, ignore_errors=True)
            raise

    def _load_verified_input(
        self,
        item: WeightsFedAvgInput,
        definition,
    ) -> tuple[OrderedDict[str, torch.Tensor], dict[str, Any], ModelDescriptor]:
        weights_path = self._resolve_regular_file(item.weightsPath, self.input_root)
        manifest_path = self._resolve_regular_file(item.manifestPath, self.input_root)
        descriptor_path = self._resolve_regular_file(item.descriptorPath, self.input_root)
        inspection_path = self._resolve_regular_file(item.inspectionReportPath, self.input_root)

        actual_sha = sha256_file(weights_path)
        self._require(
            actual_sha.lower() == item.expectedSha256.lower(),
            "WEIGHTS_FEDAVG_INPUT_TAMPERED",
            "Input weights SHA256 differs from persisted evidence.",
        )
        try:
            manifest = WeightsPackageManifest.model_validate(self._read_json(manifest_path))
            descriptor = ModelDescriptor.model_validate(self._read_json(descriptor_path))
        except Exception as exc:
            raise WeightsFedAvgError(
                "WEIGHTS_FEDAVG_INPUT_INVALID",
                "Input asset sidecars do not satisfy Protocol v1.",
            ) from exc
        prior_inspection = self._read_json(inspection_path)
        self._require(
            prior_inspection.get("passed") is True,
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "Input asset does not contain successful Stage 2A inspection evidence.",
        )

        expected_reference = (definition.definitionId, definition.definitionSha256)
        self._require(
            (manifest.modelDefinition.definitionId, manifest.modelDefinition.definitionSha256)
            == expected_reference
            and (descriptor.modelDefinition.definitionId, descriptor.modelDefinition.definitionSha256)
            == expected_reference,
            "WEIGHTS_FEDAVG_DEFINITION_MISMATCH",
            "Input asset ModelDefinition differs from the workflow Definition.",
        )
        self._require(
            manifest.architectureSignature == definition.architectureSignature,
            "WEIGHTS_FEDAVG_ARCHITECTURE_MISMATCH",
            "Input architecture signature differs from the workflow Definition.",
        )

        inspection = self.inspector.inspect(
            ModelPackageInspectRequest(
                weightsPath=str(weights_path), manifest=manifest, descriptor=descriptor
            )
        )
        self._require(
            inspection.passed,
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "Input weights failed the pre-aggregation safe inspection.",
        )
        self._require(
            inspection.weightsSha256 is not None
            and inspection.weightsSha256.lower() == item.expectedSha256.lower(),
            "WEIGHTS_FEDAVG_INPUT_TAMPERED",
            "Input weights no longer match persisted SHA256 evidence.",
        )

        try:
            payload = torch.load(weights_path, map_location="cpu", weights_only=True)
        except Exception as exc:
            raise WeightsFedAvgError(
                "WEIGHTS_FEDAVG_INPUT_INVALID",
                "Input weights cannot be safely loaded.",
            ) from exc
        self._require(
            isinstance(payload, dict) and set(payload.keys()) == {"state_dict"},
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "Weights wrapper must contain only state_dict.",
        )
        state = payload["state_dict"]
        self._require(
            isinstance(state, OrderedDict),
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "state_dict must be an OrderedDict.",
        )
        evidence = {
            "assetId": item.assetId,
            "uploadId": item.uploadId,
            "sha256": actual_sha,
            "keyCount": inspection.keyCount,
            "tensorCount": inspection.tensorCount,
            "elementCount": inspection.elementCount,
            "stateDictKeyHash": inspection.stateDictKeyHash,
            "shapeSignature": inspection.shapeSignature,
            "dtypeSignature": inspection.dtypeSignature,
            "dtypeCounts": inspection.dtypeCounts,
        }
        return state, evidence, descriptor

    def _type_safe_fedavg(
        self,
        states: list[OrderedDict[str, torch.Tensor]],
        weights: list[float],
    ) -> OrderedDict[str, torch.Tensor]:
        self._require(bool(states), "WEIGHTS_FEDAVG_NOT_READY", "No weights were provided.")
        self._require(
            len(states) == len(weights)
            and all(math.isfinite(value) and value >= 0 for value in weights)
            and math.isclose(sum(weights), 1.0, rel_tol=0.0, abs_tol=1e-12),
            "WEIGHTS_FEDAVG_INPUT_INVALID",
            "Aggregation weights are invalid.",
        )
        keys = list(states[0].keys())
        result: OrderedDict[str, torch.Tensor] = OrderedDict()
        for key in keys:
            tensors = [state[key].detach().cpu() for state in states]
            reference = tensors[0]
            self._require(
                all(tuple(tensor.shape) == tuple(reference.shape) for tensor in tensors[1:]),
                "WEIGHTS_FEDAVG_STATE_MISMATCH",
                "Input tensor shapes differ.",
            )
            self._require(
                all(tensor.dtype == reference.dtype for tensor in tensors[1:]),
                "WEIGHTS_FEDAVG_DTYPE_MISMATCH",
                "Input tensor dtypes differ.",
            )
            if reference.dtype == torch.float16:
                accumulator = torch.zeros_like(reference, dtype=torch.float32)
                for tensor, weight in zip(tensors, weights):
                    accumulator.add_(tensor.to(torch.float32), alpha=weight)
                result[key] = accumulator
            elif reference.dtype == torch.float32:
                accumulator = torch.zeros_like(reference, dtype=torch.float64)
                for tensor, weight in zip(tensors, weights):
                    accumulator.add_(tensor.to(torch.float64), alpha=weight)
                result[key] = accumulator.to(torch.float32)
            elif reference.dtype == torch.int64:
                self._require(
                    all(torch.equal(reference, tensor) for tensor in tensors[1:]),
                    "WEIGHTS_FEDAVG_NON_FLOATING_MISMATCH",
                    "Non-floating Tensor values must be identical across clients.",
                )
                result[key] = reference.clone()
            else:
                raise WeightsFedAvgError(
                    "WEIGHTS_FEDAVG_DTYPE_MISMATCH",
                    "Input contains a dtype unsupported by type-safe FedAvg.",
                )
        return result

    def _write_and_inspect_output(
        self,
        request: WeightsFedAvgRequest,
        global_state: OrderedDict[str, torch.Tensor],
        weights: list[float],
        inputs: list[dict[str, Any]],
        reference_descriptor: ModelDescriptor | None,
        output_directory: Path,
    ) -> WeightsFedAvgResult:
        self._require(reference_descriptor is not None, "WEIGHTS_FEDAVG_NOT_READY", "Descriptor is missing.")
        weights_path = output_directory / "global_weights.pt"
        temporary_weights = output_directory / f".{uuid.uuid4().hex}.tmp"
        torch.save({"state_dict": global_state}, temporary_weights)
        os.replace(temporary_weights, weights_path)

        signatures = state_signatures(global_state)
        dtype_counts: dict[str, int] = {}
        element_count = 0
        for tensor in global_state.values():
            dtype = str(tensor.dtype).removeprefix("torch.")
            dtype_counts[dtype] = dtype_counts.get(dtype, 0) + 1
            element_count += tensor.numel()

        descriptor = reference_descriptor.model_copy(deep=True)
        descriptor.descriptorVersion = "1.0-server-global"
        descriptor.stateContract.keyCount = len(global_state)
        descriptor.stateContract.tensorCount = len(global_state)
        descriptor.stateContract.elementCount = element_count
        descriptor.stateContract.stateDictKeyHash = signatures["stateDictKeyHash"]
        descriptor.stateContract.shapeSignature = signatures["shapeSignature"]
        descriptor.stateContract.clientDtypeSignature = signatures["dtypeSignature"]
        descriptor.extensions = {
            **descriptor.extensions,
            "artifactRole": "FEDERATED_GLOBAL_WEIGHTS",
            "aggregationMethod": request.aggregationMethod,
        }
        descriptor_sha = canonical_json_sha256(descriptor.model_dump(mode="json"))
        output_sha = sha256_file(weights_path)
        definition = request.trustedModelDefinition
        manifest = WeightsPackageManifest.model_validate({
            "schemaVersion": "1.0",
            "weightsFormatVersion": "1.0",
            "packageId": f"workflow-{request.workflowId}-global-{uuid.uuid4().hex}",
            "artifactType": "FEDERATED_WEIGHTS",
            "descriptorSha256": descriptor_sha,
            "modelDefinition": {
                "definitionId": definition.definitionId,
                "definitionSha256": definition.definitionSha256,
            },
            "architectureSignature": definition.architectureSignature,
            "parameterSemantics": descriptor.parameterSemantics,
            "weights": {
                "format": "PYTORCH_STATE_DICT",
                "wrapperKey": "state_dict",
                "fileName": weights_path.name,
                "sizeBytes": weights_path.stat().st_size,
                "sha256": output_sha,
            },
            "state": {
                "keyCount": len(global_state),
                "tensorCount": len(global_state),
                "elementCount": element_count,
                **signatures,
                "dtypeCounts": dict(sorted(dtype_counts.items())),
            },
            "aggregationContribution": 1.0,
            "createdBy": {
                "type": "SERVER_WEIGHTS_FEDAVG_V1",
                "workflowId": request.workflowId,
            },
        })
        descriptor_path = output_directory / "descriptor.json"
        manifest_path = output_directory / "aggregation-manifest.json"
        descriptor_path.write_text(
            json.dumps(descriptor.model_dump(mode="json"), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        manifest_path.write_text(
            json.dumps(manifest.model_dump(mode="json"), ensure_ascii=False, indent=2), encoding="utf-8"
        )

        inspection = self.inspector.inspect(
            ModelPackageInspectRequest(
                weightsPath=str(weights_path), manifest=manifest, descriptor=descriptor
            )
        )
        self._require(
            inspection.passed,
            "WEIGHTS_FEDAVG_OUTPUT_INVALID",
            "Global weights failed the Protocol v1 output inspection.",
        )
        inspection_path = output_directory / "inspection-report.json"
        inspection_path.write_text(
            json.dumps(inspection.model_dump(mode="json"), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        report_path = output_directory / "aggregation-report.json"
        report_path.write_text(
            json.dumps({
                "schemaVersion": "1.0",
                "workflowId": request.workflowId,
                "modelDefinitionId": definition.definitionId,
                "modelDefinitionCode": definition.code,
                "definitionSha256": definition.definitionSha256,
                "architectureSignature": definition.architectureSignature,
                "aggregationMethod": request.aggregationMethod,
                "nonFloatingPolicy": "REQUIRE_IDENTICAL_AND_COPY",
                "floatingPolicies": {
                    "float16": {"accumulator": "float32", "output": "float32"},
                    "float32": {"accumulator": "float64", "output": "float32"},
                },
                "inputCount": len(inputs),
                "aggregationWeights": weights,
                "inputs": inputs,
                "output": {
                    "sha256": output_sha,
                    "sizeBytes": weights_path.stat().st_size,
                    "keyCount": inspection.keyCount,
                    "tensorCount": inspection.tensorCount,
                    "elementCount": inspection.elementCount,
                    "stateDictKeyHash": inspection.stateDictKeyHash,
                    "shapeSignature": inspection.shapeSignature,
                    "dtypeSignature": inspection.dtypeSignature,
                    "dtypeCounts": inspection.dtypeCounts,
                },
            }, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return WeightsFedAvgResult(
            passed=True,
            reasonCode="WEIGHTS_FEDAVG_COMPLETED",
            message="Weights-only FedAvg completed.",
            workflowId=request.workflowId,
            modelDefinitionId=definition.definitionId,
            modelDefinitionCode=definition.code,
            inputCount=len(inputs),
            inputAssetIds=[item["assetId"] for item in inputs],
            aggregationMethod=request.aggregationMethod,
            aggregationWeights=weights,
            outputWeightsPath=str(weights_path),
            outputManifestPath=str(manifest_path),
            outputDescriptorPath=str(descriptor_path),
            outputInspectionPath=str(inspection_path),
            outputReportPath=str(report_path),
            outputSha256=output_sha,
            inspection=inspection,
        )

    def _resolve_regular_file(self, raw: str, root: Path) -> Path:
        path = Path(raw).resolve(strict=False)
        self._require(path != root and path.is_relative_to(root), "PATH_NOT_ALLOWED", "Input path is outside the weights asset root.")
        self._require(path.exists() and path.is_file() and not path.is_symlink(), "WEIGHTS_FEDAVG_INPUT_INVALID", "Input file is missing or linked.")
        return path

    def _resolve_output_directory(self, raw: str) -> Path:
        path = Path(raw).resolve(strict=False)
        self._require(path != self.output_root and path.is_relative_to(self.output_root), "PATH_NOT_ALLOWED", "Output path is outside the federated weights root.")
        self._require(not path.exists(), "WEIGHTS_FEDAVG_ALREADY_RUNNING", "Output directory already exists.")
        return path

    def _read_json(self, path: Path) -> dict[str, Any]:
        self._require(path.stat().st_size <= self.MAX_SIDECAR_BYTES, "WEIGHTS_FEDAVG_INPUT_INVALID", "Sidecar exceeds size limit.")
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise WeightsFedAvgError("WEIGHTS_FEDAVG_INPUT_INVALID", "Sidecar is not valid JSON.") from exc
        self._require(isinstance(value, dict), "WEIGHTS_FEDAVG_INPUT_INVALID", "Sidecar must be a JSON object.")
        return value

    def _require_unique_inputs(self, inputs: list[WeightsFedAvgInput]) -> None:
        asset_ids = [item.assetId for item in inputs]
        self._require(len(asset_ids) == len(set(asset_ids)), "WEIGHTS_FEDAVG_INPUT_INVALID", "Duplicate input asset IDs are not allowed.")

    @staticmethod
    def _require(condition: bool, code: str, message: str) -> None:
        if not condition:
            raise WeightsFedAvgError(code, message)


weights_fedavg_v1_service = WeightsFedAvgV1Service()
