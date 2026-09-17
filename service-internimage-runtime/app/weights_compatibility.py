from __future__ import annotations

import math
from collections import OrderedDict
from typing import Any

from app.definition_check import check_definition
from app.model_template import build_model
from app.schemas import StateTensorMetadata, WeightsCompatibilityResult


def _dtype_compatible(expected: str, actual: str) -> bool:
    if expected.startswith("float"):
        return actual in {"float16", "float32"}
    return expected == actual


def _element_count(state: OrderedDict[str, tuple[list[int], str]]) -> int:
    return sum(math.prod(shape) for shape, _dtype in state.values())


def check_weights_compatibility(
    definition: dict[str, Any],
    runtime_profile_id: str,
    state: list[StateTensorMetadata],
) -> WeightsCompatibilityResult:
    availability = check_definition(definition, runtime_profile_id)
    if not availability.available:
        return WeightsCompatibilityResult(
            compatible=False,
            reasonCode="WEIGHTS_COMPATIBILITY_FAILED",
            message="InternImage Definition or runtime self-check is not available.",
            actualTensorCount=len(state),
        )

    try:
        model = build_model()
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

    keys = [item.key for item in state]
    if len(keys) != len(set(keys)) or any(
        any(dimension < 0 for dimension in item.shape) for item in state
    ):
        return WeightsCompatibilityResult(
            compatible=False,
            reasonCode="WEIGHTS_KEY_MISMATCH",
            message="Weights metadata contains duplicate keys or invalid shapes.",
            expectedTensorCount=len(expected),
            actualTensorCount=len(state),
            expectedElementCount=_element_count(expected),
        )

    actual = OrderedDict((item.key, (item.shape, item.dtype)) for item in state)
    missing = [key for key in expected if key not in actual]
    unexpected = [key for key in actual if key not in expected]
    common_keys = expected.keys() & actual.keys()
    shape_mismatches = [
        key for key in common_keys if expected[key][0] != actual[key][0]
    ]
    dtype_mismatches = [
        key
        for key in common_keys
        if not _dtype_compatible(expected[key][1], actual[key][1])
    ]
    order_matches = list(expected.keys()) == list(actual.keys())
    expected_elements = _element_count(expected)
    actual_elements = _element_count(actual)
    element_count_matches = expected_elements == actual_elements
    compatible = not (
        missing
        or unexpected
        or shape_mismatches
        or dtype_mismatches
        or not order_matches
        or not element_count_matches
    )
    reason = (
        "AVAILABLE"
        if compatible
        else "WEIGHTS_KEY_MISMATCH"
        if missing or unexpected or not order_matches
        else "WEIGHTS_SHAPE_MISMATCH"
        if shape_mismatches or not element_count_matches
        else "WEIGHTS_DTYPE_UNSUPPORTED"
    )
    return WeightsCompatibilityResult(
        compatible=compatible,
        reasonCode=reason,
        message=(
            "Weights state contract matches the architecture-only InternImage model."
            if compatible
            else "Weights state contract does not match the architecture-only InternImage model."
        ),
        expectedTensorCount=len(expected),
        actualTensorCount=len(actual),
        expectedElementCount=expected_elements,
        actualElementCount=actual_elements,
        missingKeyCount=len(missing),
        unexpectedKeyCount=len(unexpected),
        shapeMismatchCount=len(shape_mismatches),
        dtypeMismatchCount=len(dtype_mismatches),
    )
