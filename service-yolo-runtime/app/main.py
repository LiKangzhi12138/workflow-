from __future__ import annotations

import logging

from fastapi import FastAPI, HTTPException

from app.definition_check import check_definition
from app.runtime_probe import probe_runtime
from app.schemas import (
    DefinitionCheckRequest,
    ModelAvailabilityResult,
    RuntimeHealthResponse,
    WeightsCompatibilityRequest,
    WeightsCompatibilityResult,
    WeightsValidationRequest,
    WeightsValidationResult,
)
from app.weights_compatibility import check_weights_compatibility
from app.weights_validation import WeightsValidationError, validate_weights


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("yolo-runtime")

app = FastAPI(title="workflow-platform-yolo-runtime")


@app.on_event("startup")
def log_runtime_summary() -> None:
    health = probe_runtime()
    logger.info(
        "runtime startup: profile=%s python=%s torch=%s torchvision=%s ultralytics=%s cuda=%s gpu=%s ready=%s",
        health.runtimeProfileId,
        health.pythonVersion,
        health.torchVersion,
        health.torchvisionVersion,
        health.ultralyticsVersion,
        health.cudaAvailable,
        health.gpuName,
        health.runtimeReady,
    )
    if health.errors:
        logger.warning("runtime readiness checks failed: %s", health.errors)


@app.get("/internal/runtime/health", response_model=RuntimeHealthResponse)
def runtime_health() -> RuntimeHealthResponse:
    return probe_runtime()


@app.post(
    "/internal/model-definitions/check",
    response_model=ModelAvailabilityResult,
)
def model_definition_check(
    payload: DefinitionCheckRequest,
) -> ModelAvailabilityResult:
    return check_definition(payload.modelDefinition, payload.runtimeProfileId)


@app.post(
    "/internal/model-definitions/weights-compatibility",
    response_model=WeightsCompatibilityResult,
)
def weights_compatibility_check(
    payload: WeightsCompatibilityRequest,
) -> WeightsCompatibilityResult:
    return check_weights_compatibility(
        payload.modelDefinition,
        payload.runtimeProfileId,
        payload.state,
    )


@app.post(
    "/internal/runtime/validate-weights-v1",
    response_model=WeightsValidationResult,
)
def weights_validation(payload: WeightsValidationRequest) -> WeightsValidationResult:
    logger.info(
        "weights-only validation requested: workflowId=%s jobId=%s assetId=%s definitionId=%s "
        "definitionShaPrefix=%s architectureSignaturePrefix=%s weightsShaPrefix=%s runtimeProfileId=%s",
        payload.workflowId,
        payload.jobId,
        payload.globalWeights.assetId,
        payload.modelDefinition.get("definitionId"),
        str(payload.modelDefinition.get("definitionSha256", ""))[:12],
        str(payload.modelDefinition.get("architectureSignature", ""))[:12],
        payload.globalWeights.expectedSha256[:12],
        payload.runtimeProfileId,
    )
    try:
        return validate_weights(payload)
    except WeightsValidationError as exc:
        raise HTTPException(
            status_code=422,
            detail={"reasonCode": exc.code, "message": str(exc)},
        ) from exc
