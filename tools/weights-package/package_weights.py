from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import uuid
from collections import Counter, OrderedDict
from pathlib import Path
from typing import Any

import torch


ALLOWED_DTYPES = {"float16", "float32", "int64"}


def canonical_sha256(value: Any) -> str:
    data = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def load_safe_state(path: Path) -> OrderedDict[str, torch.Tensor]:
    payload = torch.load(path, map_location="cpu", weights_only=True)
    if not isinstance(payload, dict) or set(payload.keys()) != {"state_dict"}:
        raise ValueError("Protocol v1 source must contain exactly the top-level key 'state_dict'.")
    state = payload["state_dict"]
    if not isinstance(state, OrderedDict) or not state:
        raise ValueError("state_dict must be a non-empty OrderedDict[str, Tensor].")
    for key, tensor in state.items():
        if not isinstance(key, str) or not isinstance(tensor, torch.Tensor):
            raise ValueError("Every state_dict entry must be str -> Tensor.")
        dtype = str(tensor.dtype).removeprefix("torch.")
        if dtype not in ALLOWED_DTYPES:
            raise ValueError(f"Unsupported dtype: {dtype}")
        if torch.is_floating_point(tensor) and not bool(torch.isfinite(tensor).all()):
            raise ValueError(f"Non-finite values detected in tensor: {key}")
    return state


def build_package(weights_source: Path, definition_path: Path, output_dir: Path, created_by: str) -> None:
    definition = json.loads(definition_path.read_text(encoding="utf-8"))
    state = load_safe_state(weights_source)
    output_dir.mkdir(parents=True, exist_ok=True)
    weights_output = output_dir / "weights.pt"
    descriptor_output = output_dir / "descriptor.json"
    manifest_output = output_dir / "manifest.json"
    for target in (weights_output, descriptor_output, manifest_output):
        if target.exists():
            raise FileExistsError(f"Refusing to overwrite existing package file: {target}")
    shutil.copyfile(weights_source, weights_output)

    keys = list(state.keys())
    key_hash = text_sha256("\n".join(keys))
    shape_signature = text_sha256("\n".join(f"{key}:{tuple(state[key].shape)}" for key in keys))
    dtype_signature = text_sha256("\n".join(f"{key}:{state[key].dtype}" for key in keys))
    dtype_counts = dict(sorted(Counter(
        str(tensor.dtype).removeprefix("torch.") for tensor in state.values()
    ).items()))
    element_count = sum(tensor.numel() for tensor in state.values())
    definition_reference = {
        "definitionId": definition["definitionId"],
        "definitionSha256": definition["definitionSha256"],
    }
    parameter_semantics = definition["definitionPayload"]["parameterSemantics"]
    descriptor = {
        "schemaVersion": "1.0",
        "descriptorVersion": "1.0",
        "modelFamily": definition["modelFamily"],
        "version": definition["version"],
        "variant": definition["variant"],
        "taskType": definition["taskType"],
        "framework": {"name": definition["framework"], "version": definition["frameworkVersion"]},
        "modelDefinition": definition_reference,
        "classes": {
            "count": definition["classCount"],
            "order": definition["classOrder"],
            "ignoreIndex": definition.get("ignoreIndex"),
        },
        "stateContract": {
            "keyCount": len(state),
            "tensorCount": len(state),
            "elementCount": element_count,
            "stateDictKeyHash": key_hash,
            "shapeSignature": shape_signature,
            "clientDtypeSignature": dtype_signature,
        },
        "parameterSemantics": parameter_semantics,
        "inputSpec": definition["inputSpec"],
        "preprocessing": definition["definitionPayload"].get("preprocessing", {}),
        "runtimeRequirements": {"runtimeProfileId": definition["runtimeProfileId"]},
        "extensions": {"architectureSignature": definition["architectureSignature"]},
    }
    descriptor_output.write_text(json.dumps(descriptor, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = {
        "schemaVersion": "1.0",
        "weightsFormatVersion": "1.0",
        "packageId": str(uuid.uuid4()),
        "artifactType": "CLIENT_WEIGHTS",
        "descriptorSha256": canonical_sha256(descriptor),
        "modelDefinition": definition_reference,
        "architectureSignature": definition["architectureSignature"],
        "parameterSemantics": parameter_semantics,
        "weights": {
            "format": "PYTORCH_STATE_DICT",
            "wrapperKey": "state_dict",
            "fileName": "weights.pt",
            "sizeBytes": weights_output.stat().st_size,
            "sha256": file_sha256(weights_output),
        },
        "state": {
            "keyCount": len(state),
            "tensorCount": len(state),
            "elementCount": element_count,
            "stateDictKeyHash": key_hash,
            "shapeSignature": shape_signature,
            "dtypeSignature": dtype_signature,
            "dtypeCounts": dtype_counts,
        },
        "aggregationContribution": 1.0,
        "createdBy": {"type": "TRUSTED_LOCAL_PACKAGING_TOOL", "name": created_by},
    }
    manifest_output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "passed": True,
        "weights": str(weights_output),
        "sha256": manifest["weights"]["sha256"],
        "tensorCount": len(state),
        "manifest": str(manifest_output),
        "descriptor": str(descriptor_output),
    }, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a Protocol v1 package from trusted weights-only input.")
    parser.add_argument("--weights", required=True, type=Path)
    parser.add_argument("--definition", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--created-by", default="workflow-client")
    args = parser.parse_args()
    build_package(args.weights.resolve(), args.definition.resolve(), args.output_dir.resolve(), args.created_by)


if __name__ == "__main__":
    main()
