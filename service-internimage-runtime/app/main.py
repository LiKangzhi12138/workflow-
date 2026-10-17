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
    DatasetCheckRequest,
    DatasetCheckResult,
)
from app.weights_compatibility import check_weights_compatibility
from app.weights_validation import WeightsValidationError, check_dataset, run_validation


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("internimage-runtime")

app = FastAPI(title="workflow-platform-internimage-runtime")


@app.on_event("startup")
def log_runtime_summary() -> None:
    health = probe_runtime()
    logger.info(
        "runtime startup: profile=%s python=%s torch=%s mmcv=%s mmseg=%s internimage=%s "
        "dcnv3=%s dcnv3Forward=%s cuda=%s gpu=%s ready=%s",
        health.runtimeProfileId,
        health.pythonVersion,
        health.torchVersion,
        health.mmcvVersion,
        health.mmsegVersion,
        health.internimageVersion,
        health.dcnv3Available,
        health.dcnv3SelfCheckPassed,
        health.cudaAvailable,
        health.gpuName,
        health.runtimeReady,
    )
    if health.errors:
        logger.warning("runtime readiness checks failed: %s", health.errors)


@app.get("/internal/runtime/health", response_model=RuntimeHealthResponse)
def runtime_health() -> RuntimeHealthResponse:
    return probe_runtime()


@app.post("/internal/model-definitions/check", response_model=ModelAvailabilityResult)
def model_definition_check(payload: DefinitionCheckRequest) -> ModelAvailabilityResult:
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


@app.post("/internal/runtime/validate-weights-v1", response_model=WeightsValidationResult)
def validate_weights(payload: WeightsValidationRequest) -> WeightsValidationResult:
    try:
        return run_validation(payload)
    except WeightsValidationError as exc:
        logger.warning(
            "InternImage weights validation rejected: workflowId=%s jobId=%s assetId=%s "
            "runtimeProfileId=%s stage=%s reasonCode=%s",
            payload.workflowId,
            payload.jobId,
            payload.globalWeights.assetId,
            payload.runtimeProfileId,
            exc.phase or "REQUEST_VALIDATION",
            exc.code,
        )
        raise HTTPException(status_code=422, detail={"reasonCode": exc.code, "message": str(exc)}) from exc


@app.post("/internal/runtime/check-dataset-v1", response_model=DatasetCheckResult)
def dataset_check(payload: DatasetCheckRequest) -> DatasetCheckResult:
    try:
        return check_dataset(payload)
    except WeightsValidationError as exc:
        logger.warning("InternImage dataset rejected: reason=%s", exc.code)
        raise HTTPException(status_code=422, detail={"reasonCode": exc.code, "message": str(exc)}) from exc
