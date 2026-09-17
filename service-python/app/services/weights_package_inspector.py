from __future__ import annotations

import hashlib
import json
from collections import Counter, OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import torch

from app.core.config import settings
from app.schemas.model_protocol import (
    InspectionCheck,
    InspectionReport,
    ModelDescriptor,
    ModelPackageInspectRequest,
    ProtocolErrorCode,
    ProtocolIssue,
    WeightsPackageManifest,
)


SUPPORTED_SCHEMA_VERSION = "1.0"
SUPPORTED_WEIGHTS_FORMAT_VERSION = "1.0"
SUPPORTED_ARTIFACT_TYPES = frozenset({"CLIENT_WEIGHTS", "FEDERATED_WEIGHTS"})
SUPPORTED_WEIGHTS_FORMAT = "PYTORCH_STATE_DICT"
SUPPORTED_WRAPPER_KEY = "state_dict"
CANONICALIZATION_ALGORITHM = "CANONICAL_JSON_SHA256_V1"
ALLOWED_DTYPES = frozenset({"float16", "float32", "int64"})


@dataclass(frozen=True)
class InspectorLimits:
    max_file_bytes: int
    max_tensor_count: int
    max_total_elements: int
    max_single_tensor_elements: int
    max_tensor_dimensions: int
    max_key_length: int

    @classmethod
    def from_settings(cls) -> "InspectorLimits":
        return cls(
            max_file_bytes=settings.WEIGHTS_PROTOCOL_MAX_FILE_BYTES,
            max_tensor_count=settings.WEIGHTS_PROTOCOL_MAX_TENSOR_COUNT,
            max_total_elements=settings.WEIGHTS_PROTOCOL_MAX_TOTAL_ELEMENTS,
            max_single_tensor_elements=settings.WEIGHTS_PROTOCOL_MAX_SINGLE_TENSOR_ELEMENTS,
            max_tensor_dimensions=settings.WEIGHTS_PROTOCOL_MAX_TENSOR_DIMENSIONS,
            max_key_length=settings.WEIGHTS_PROTOCOL_MAX_KEY_LENGTH,
        )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(value: Any) -> str:
    canonical = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def state_signatures(state_dict: OrderedDict[str, torch.Tensor]) -> dict[str, str]:
    """Match the ordered signature contract proven by the weights lab."""
    keys = list(state_dict.keys())

    def hash_text(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    return {
        "stateDictKeyHash": hash_text("\n".join(keys)),
        "shapeSignature": hash_text(
            "\n".join(f"{key}:{tuple(state_dict[key].shape)}" for key in keys)
        ),
        "dtypeSignature": hash_text(
            "\n".join(f"{key}:{state_dict[key].dtype}" for key in keys)
        ),
    }


class WeightsPackageInspector:
    def __init__(
        self,
        allowed_roots: list[Path] | None = None,
        limits: InspectorLimits | None = None,
    ) -> None:
        roots = allowed_roots if allowed_roots is not None else settings.weights_protocol_allowed_roots()
        self.allowed_roots = [Path(root).expanduser().resolve() for root in roots]
        self.limits = limits or InspectorLimits.from_settings()

    def inspect(self, request: ModelPackageInspectRequest) -> InspectionReport:
        report = InspectionReport()
        manifest = request.manifest
        descriptor = request.descriptor
        report.manifestSha256 = canonical_json_sha256(manifest.model_dump(mode="json"))
        report.descriptorSha256 = canonical_json_sha256(descriptor.model_dump(mode="json"))

        self._check_protocol_metadata(report, manifest, descriptor)
        if report.errors:
            return report

        weights_path = self._resolve_allowed_file(request.weightsPath, report)
        if weights_path is None:
            return report

        self._compare(
            report,
            "weights-file-name",
            weights_path.name == manifest.weights.fileName,
            ProtocolErrorCode.DESCRIPTOR_MISMATCH,
            "Actual weights file name does not match manifest.weights.fileName.",
            "manifest.weights.fileName",
        )

        report.sizeBytes = weights_path.stat().st_size
        if report.sizeBytes > self.limits.max_file_bytes:
            self._error(
                report,
                ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                f"Weights file exceeds maxFileBytes={self.limits.max_file_bytes}.",
                "weightsPath",
            )
            return report
        self._compare(
            report,
            "weights-size",
            report.sizeBytes == manifest.weights.sizeBytes,
            ProtocolErrorCode.WEIGHTS_SIZE_MISMATCH,
            "Actual weights size does not match manifest.weights.sizeBytes.",
            "manifest.weights.sizeBytes",
        )

        report.weightsSha256 = sha256_file(weights_path)
        self._compare(
            report,
            "weights-sha256",
            report.weightsSha256.lower() == manifest.weights.sha256.lower(),
            ProtocolErrorCode.WEIGHTS_SHA256_MISMATCH,
            "Actual weights SHA256 does not match manifest.weights.sha256.",
            "manifest.weights.sha256",
        )
        if report.errors:
            return report

        try:
            payload = self._safe_torch_load(weights_path)
        except Exception as exc:
            self._error(
                report,
                ProtocolErrorCode.INVALID_STATE_DICT,
                f"Safe weights-only loading failed: {type(exc).__name__}.",
                "weightsPath",
            )
            return report

        if not isinstance(payload, dict) or set(payload.keys()) != {SUPPORTED_WRAPPER_KEY}:
            self._error(
                report,
                ProtocolErrorCode.INVALID_TOP_LEVEL,
                "Protocol v1 requires exactly one top-level key: state_dict.",
                "weights",
            )
            return report

        state_dict = payload[SUPPORTED_WRAPPER_KEY]
        if not isinstance(state_dict, OrderedDict):
            self._error(
                report,
                ProtocolErrorCode.INVALID_STATE_DICT,
                "state_dict must be collections.OrderedDict[str, Tensor].",
                "weights.state_dict",
            )
            return report
        if not state_dict:
            self._error(
                report,
                ProtocolErrorCode.INVALID_STATE_DICT,
                "state_dict must not be empty.",
                "weights.state_dict",
            )
            return report

        if not self._inspect_state_values(report, state_dict):
            return report

        signatures = state_signatures(state_dict)
        report.stateDictKeyHash = signatures["stateDictKeyHash"]
        report.shapeSignature = signatures["shapeSignature"]
        report.dtypeSignature = signatures["dtypeSignature"]

        self._compare_contracts(report, manifest, descriptor)
        report.passed = not report.errors
        return report

    def _safe_torch_load(self, weights_path: Path) -> Any:
        return torch.load(weights_path, map_location="cpu", weights_only=True)

    def _check_protocol_metadata(
        self,
        report: InspectionReport,
        manifest: WeightsPackageManifest,
        descriptor: ModelDescriptor,
    ) -> None:
        versions_valid = (
            manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION
            and descriptor.schemaVersion == SUPPORTED_SCHEMA_VERSION
            and manifest.weightsFormatVersion == SUPPORTED_WEIGHTS_FORMAT_VERSION
        )
        self._compare(
            report,
            "schema-version",
            versions_valid,
            ProtocolErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            "Only protocol schemaVersion=1.0 and weightsFormatVersion=1.0 are supported.",
            "schemaVersion",
        )
        self._compare(
            report,
            "artifact-type",
            manifest.artifactType in SUPPORTED_ARTIFACT_TYPES,
            ProtocolErrorCode.INVALID_ARTIFACT_TYPE,
            "Protocol v1 accepts CLIENT_WEIGHTS or server-generated FEDERATED_WEIGHTS artifacts.",
            "manifest.artifactType",
        )
        self._compare(
            report,
            "weights-format",
            manifest.weights.format == SUPPORTED_WEIGHTS_FORMAT
            and manifest.weights.wrapperKey == SUPPORTED_WRAPPER_KEY,
            ProtocolErrorCode.INVALID_SCHEMA,
            "Weights format must be PYTORCH_STATE_DICT with wrapperKey=state_dict.",
            "manifest.weights",
        )
        self._compare(
            report,
            "descriptor-sha256",
            report.descriptorSha256 == manifest.descriptorSha256,
            ProtocolErrorCode.DESCRIPTOR_MISMATCH,
            "Descriptor canonical SHA256 does not match manifest.descriptorSha256.",
            "manifest.descriptorSha256",
        )
        self._compare(
            report,
            "model-definition-reference",
            manifest.modelDefinition == descriptor.modelDefinition,
            ProtocolErrorCode.DEFINITION_MISMATCH,
            "Manifest and descriptor reference different model definitions.",
            "manifest.modelDefinition",
        )
        self._compare(
            report,
            "parameter-semantics",
            manifest.parameterSemantics == descriptor.parameterSemantics,
            ProtocolErrorCode.DESCRIPTOR_MISMATCH,
            "Manifest and descriptor parameterSemantics differ.",
            "manifest.parameterSemantics",
        )
        self._compare(
            report,
            "class-contract",
            descriptor.classes.count == len(descriptor.classes.order),
            ProtocolErrorCode.DESCRIPTOR_MISMATCH,
            "Descriptor class count does not match class order length.",
            "descriptor.classes",
        )

    def _resolve_allowed_file(self, raw_path: str, report: InspectionReport) -> Path | None:
        candidate = Path(raw_path).expanduser()
        absolute_candidate = candidate if candidate.is_absolute() else Path.cwd() / candidate
        normalized_candidate = absolute_candidate.resolve(strict=False)

        matching_root: Path | None = None
        for root in self.allowed_roots:
            try:
                normalized_candidate.relative_to(root)
                matching_root = root
                break
            except ValueError:
                continue

        if matching_root is None or normalized_candidate == matching_root:
            self._error(
                report,
                ProtocolErrorCode.PATH_NOT_ALLOWED,
                "Weights path is outside configured protocol storage roots.",
                "weightsPath",
            )
            return None

        lexical_candidate = absolute_candidate.absolute()
        try:
            lexical_relative = lexical_candidate.relative_to(matching_root)
            current = matching_root
            for part in lexical_relative.parts:
                current = current / part
                if current.is_symlink():
                    self._error(
                        report,
                        ProtocolErrorCode.PATH_NOT_ALLOWED,
                        "Symbolic links are not allowed in weights paths.",
                        "weightsPath",
                    )
                    return None
        except ValueError:
            # A traversal or parent symlink may change the lexical path; the
            # resolved containment check above remains authoritative.
            pass

        if not normalized_candidate.exists() or not normalized_candidate.is_file():
            self._error(
                report,
                ProtocolErrorCode.WEIGHTS_FILE_NOT_FOUND,
                "Weights file does not exist or is not a regular file.",
                "weightsPath",
            )
            return None
        return normalized_candidate

    def _inspect_state_values(
        self,
        report: InspectionReport,
        state_dict: OrderedDict[str, torch.Tensor],
    ) -> bool:
        report.keyCount = len(state_dict)
        report.tensorCount = len(state_dict)
        if len(state_dict) > self.limits.max_tensor_count:
            self._error(
                report,
                ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                f"Tensor count exceeds maxTensorCount={self.limits.max_tensor_count}.",
                "weights.state_dict",
            )
            return False

        total_elements = 0
        dtype_counts: Counter[str] = Counter()
        for key, value in state_dict.items():
            if not isinstance(key, str) or len(key) > self.limits.max_key_length:
                self._error(
                    report,
                    ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    f"State key is not a string or exceeds maxKeyLength={self.limits.max_key_length}.",
                    "weights.state_dict",
                )
                return False
            if not isinstance(value, torch.Tensor):
                self._error(
                    report,
                    ProtocolErrorCode.NON_TENSOR_VALUE,
                    f"state_dict value for key '{key}' is not a Tensor.",
                    "weights.state_dict",
                )
                return False
            if value.device.type != "cpu":
                self._error(
                    report,
                    ProtocolErrorCode.INVALID_STATE_DICT,
                    f"Tensor '{key}' was not loaded on CPU.",
                    "weights.state_dict",
                )
                return False
            if value.layout != torch.strided or value.is_quantized:
                self._error(
                    report,
                    ProtocolErrorCode.UNSUPPORTED_DTYPE,
                    f"Tensor '{key}' uses a sparse or quantized representation.",
                    "weights.state_dict",
                )
                return False
            if value.dim() > self.limits.max_tensor_dimensions:
                self._error(
                    report,
                    ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    f"Tensor '{key}' exceeds maxTensorDimensions={self.limits.max_tensor_dimensions}.",
                    "weights.state_dict",
                )
                return False

            element_count = value.numel()
            if element_count > self.limits.max_single_tensor_elements:
                self._error(
                    report,
                    ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    f"Tensor '{key}' exceeds maxSingleTensorElements={self.limits.max_single_tensor_elements}.",
                    "weights.state_dict",
                )
                return False
            total_elements += element_count
            if total_elements > self.limits.max_total_elements:
                self._error(
                    report,
                    ProtocolErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    f"State exceeds maxTotalElements={self.limits.max_total_elements}.",
                    "weights.state_dict",
                )
                return False

            dtype_name = str(value.dtype).removeprefix("torch.")
            if dtype_name not in ALLOWED_DTYPES:
                self._error(
                    report,
                    ProtocolErrorCode.UNSUPPORTED_DTYPE,
                    f"Tensor '{key}' uses unsupported dtype '{dtype_name}'.",
                    "weights.state_dict",
                )
                return False
            dtype_counts[dtype_name] += 1

            if torch.is_floating_point(value):
                report.floatingTensorCount += 1
                report.nanElements += int(torch.isnan(value).sum().item())
                report.positiveInfElements += int(torch.isposinf(value).sum().item())
                report.negativeInfElements += int(torch.isneginf(value).sum().item())
            else:
                report.nonFloatingTensorCount += 1

        report.elementCount = total_elements
        report.dtypeCounts = dict(sorted(dtype_counts.items()))
        if report.nanElements or report.positiveInfElements or report.negativeInfElements:
            self._error(
                report,
                ProtocolErrorCode.NON_FINITE,
                "Floating tensors contain NaN or infinity values.",
                "weights.state_dict",
            )
            return False
        return True

    def _compare_contracts(
        self,
        report: InspectionReport,
        manifest: WeightsPackageManifest,
        descriptor: ModelDescriptor,
    ) -> None:
        state = manifest.state
        contract = descriptor.stateContract
        self._compare(
            report,
            "key-count",
            report.keyCount == state.keyCount == contract.keyCount,
            ProtocolErrorCode.KEY_COUNT_MISMATCH,
            "Actual, manifest, and descriptor key counts differ.",
            "manifest.state.keyCount",
        )
        self._compare(
            report,
            "tensor-count",
            report.tensorCount == state.tensorCount == contract.tensorCount,
            ProtocolErrorCode.KEY_COUNT_MISMATCH,
            "Actual, manifest, and descriptor tensor counts differ.",
            "manifest.state.tensorCount",
        )
        self._compare(
            report,
            "element-count",
            report.elementCount == state.elementCount == contract.elementCount,
            ProtocolErrorCode.DESCRIPTOR_MISMATCH,
            "Actual, manifest, and descriptor element counts differ.",
            "manifest.state.elementCount",
        )
        self._compare(
            report,
            "key-signature",
            report.stateDictKeyHash == state.stateDictKeyHash == contract.stateDictKeyHash,
            ProtocolErrorCode.KEY_HASH_MISMATCH,
            "Ordered state_dict key signature mismatch.",
            "manifest.state.stateDictKeyHash",
        )
        self._compare(
            report,
            "shape-signature",
            report.shapeSignature == state.shapeSignature == contract.shapeSignature,
            ProtocolErrorCode.SHAPE_SIGNATURE_MISMATCH,
            "Ordered state_dict shape signature mismatch.",
            "manifest.state.shapeSignature",
        )
        self._compare(
            report,
            "dtype-signature",
            report.dtypeSignature == state.dtypeSignature == contract.clientDtypeSignature,
            ProtocolErrorCode.DTYPE_SIGNATURE_MISMATCH,
            "Ordered state_dict dtype signature mismatch.",
            "manifest.state.dtypeSignature",
        )
        self._compare(
            report,
            "dtype-counts",
            report.dtypeCounts == dict(sorted(state.dtypeCounts.items())),
            ProtocolErrorCode.DTYPE_SIGNATURE_MISMATCH,
            "Actual dtype counts differ from manifest state summary.",
            "manifest.state.dtypeCounts",
        )

    def _compare(
        self,
        report: InspectionReport,
        name: str,
        passed: bool,
        code: ProtocolErrorCode,
        message: str,
        field: str,
    ) -> None:
        report.checks.append(
            InspectionCheck(
                name=name,
                passed=passed,
                code=None if passed else code,
                message="passed" if passed else message,
            )
        )
        if not passed:
            self._error(report, code, message, field)

    @staticmethod
    def _error(
        report: InspectionReport,
        code: ProtocolErrorCode,
        message: str,
        field: str | None = None,
    ) -> None:
        report.errors.append(ProtocolIssue(code=code, message=message, field=field))


weights_package_inspector = WeightsPackageInspector()
