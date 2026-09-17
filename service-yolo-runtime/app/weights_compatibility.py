from __future__ import annotations

import copy
import importlib
from collections import OrderedDict
from typing import Any

from app.definition_check import check_definition
from app.schemas import WeightsCompatibilityResult


def _dtype_compatible(expected: str, actual: str) -> bool:
    if expected.startswith("float"):
        return actual in {"float16", "float32"}
    return expected == actual


def check_weights_compatibility(
    definition: dict[str, Any], runtime_profile_id: str, state
) -> WeightsCompatibilityResult:
    availability = check_definition(definition, runtime_profile_id)
    if not availability.available:
        return WeightsCompatibilityResult(
            compatible=False,
            reasonCode="WEIGHTS_COMPATIBILITY_FAILED",
            message="YOLO Definition or runtime self-check is not available.",
            actualTensorCount=len(state),
        )

    try:
        tasks = importlib.import_module("ultralytics.nn.tasks")
        model = tasks.DetectionModel(
            cfg=copy.deepcopy(definition["definitionPayload"]["architecture"]),
            ch=int(definition["definitionPayload"]["inputChannels"]),
            nc=int(definition["classCount"]),
            verbose=False,
        )
        expected = OrderedDict(
            (
                key,
                (list(tensor.shape), str(tensor.dtype).removeprefix("torch.")),
            )
            for key, tensor in model.state_dict().items()
        )
    except Exception as exc:
        return WeightsCompatibilityResult(
            compatible=False,
            reasonCode="WEIGHTS_COMPATIBILITY_FAILED",
            message=f"Expected state contract build failed: {type(exc).__name__}.",
            actualTensorCount=len(state),
        )

    actual = OrderedDict((item.key, (item.shape, item.dtype)) for item in state)
    missing = [key for key in expected if key not in actual]
    unexpected = [key for key in actual if key not in expected]
    shape_mismatches = [
        key for key in expected.keys() & actual.keys() if expected[key][0] != actual[key][0]
    ]
    dtype_mismatches = [
        key
        for key in expected.keys() & actual.keys()
        if not _dtype_compatible(expected[key][1], actual[key][1])
    ]
    order_matches = list(expected.keys()) == list(actual.keys())
    compatible = not (missing or unexpected or shape_mismatches or dtype_mismatches) and order_matches
    reason = "AVAILABLE" if compatible else (
        "WEIGHTS_KEY_MISMATCH" if missing or unexpected or not order_matches
        else "WEIGHTS_SHAPE_MISMATCH" if shape_mismatches
        else "WEIGHTS_DTYPE_UNSUPPORTED"
    )
    return WeightsCompatibilityResult(
        compatible=compatible,
        reasonCode=reason,
        message=(
            "Weights state contract matches the architecture-only YOLO model."
            if compatible
            else "Weights state contract does not match the architecture-only YOLO model."
        ),
        expectedTensorCount=len(expected),
        actualTensorCount=len(actual),
        missingKeyCount=len(missing),
        unexpectedKeyCount=len(unexpected),
        shapeMismatchCount=len(shape_mismatches),
        dtypeMismatchCount=len(dtype_mismatches),
    )
