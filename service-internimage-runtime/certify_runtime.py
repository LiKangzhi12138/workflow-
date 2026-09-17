from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from app.definition_check import EXPECTED_ARCHITECTURE_SIGNATURE, canonical_sha256
from app.model_template import EXPECTED_ELEMENT_COUNT, EXPECTED_TENSOR_COUNT, build_model


def certify(definition_path: Path, weights_path: Path) -> dict:
    definition = json.loads(definition_path.read_text(encoding="utf-8"))
    architecture = definition.get("definitionPayload", {}).get("architecture")
    if canonical_sha256(architecture) != EXPECTED_ARCHITECTURE_SIGNATURE:
        raise ValueError("Definition architecture signature mismatch")

    package = torch.load(weights_path, map_location="cpu", weights_only=True)
    if not isinstance(package, dict) or set(package) != {"state_dict"}:
        raise ValueError("Weights must use the Protocol v1 state_dict wrapper")
    state_dict = package["state_dict"]
    if len(state_dict) != EXPECTED_TENSOR_COUNT:
        raise ValueError("Unexpected tensor count")
    if sum(int(tensor.numel()) for tensor in state_dict.values()) != EXPECTED_ELEMENT_COUNT:
        raise ValueError("Unexpected element count")

    model = build_model()
    incompatible = model.load_state_dict(state_dict, strict=True)
    return {
        "passed": not incompatible.missing_keys and not incompatible.unexpected_keys,
        "weightsOnly": True,
        "strictLoad": True,
        "checkpointUsed": False,
        "baseCheckpointUsed": False,
        "pretrainedUsed": False,
        "tensorCount": len(state_dict),
        "elementCount": sum(int(tensor.numel()) for tensor in state_dict.values()),
        "missingKeyCount": len(incompatible.missing_keys),
        "unexpectedKeyCount": len(incompatible.unexpected_keys),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Certify trusted InternImage weights-only reconstruction")
    parser.add_argument("--definition", required=True, type=Path)
    parser.add_argument("--weights", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(certify(args.definition, args.weights), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
