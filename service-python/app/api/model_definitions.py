from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.schemas.model_protocol import (
    ModelAvailabilityResult, ModelDefinitionCheckRequest,
    RuntimeDatasetCheckRequest, RuntimeDatasetCheckResult,
)
from app.services.model_definition_check_service import model_definition_check_service
from app.services.runtime_client_registry import runtime_client_registry
from app.services.runtime_client_error import RuntimeClientError


router = APIRouter()


@router.post("/internal/model-definitions/check", response_model=ModelAvailabilityResult)
def check_model_definition(payload: ModelDefinitionCheckRequest) -> ModelAvailabilityResult:
    if not settings.MODEL_DEFINITION_REGISTRY_V1_ENABLED:
        raise HTTPException(status_code=404, detail="ModelDefinition Registry v1 is disabled.")
    return model_definition_check_service.check(
        payload.modelDefinition,
        payload.runtimeProfileId,
    )


@router.post("/internal/validation/check-dataset-v1", response_model=RuntimeDatasetCheckResult)
def check_validation_dataset(payload: RuntimeDatasetCheckRequest) -> RuntimeDatasetCheckResult:
    if payload.modelDefinition.runtimeProfileId != payload.runtimeProfileId:
        raise HTTPException(status_code=422, detail={"reasonCode": "MODEL_DEFINITION_MISMATCH", "message": "Trusted runtime profile differs from Definition."})
    client = runtime_client_registry.get(payload.runtimeProfileId)
    if client is None or not callable(getattr(client, "check_dataset", None)):
        raise HTTPException(status_code=422, detail={"reasonCode": "RUNTIME_PROFILE_NOT_FOUND", "message": "Dataset preflight is unavailable for this runtime."})
    try:
        return client.check_dataset(payload)
    except RuntimeClientError as exc:
        raise HTTPException(status_code=422, detail={
            "reasonCode": getattr(exc, "reason_code", "RUNTIME_REQUEST_FAILED"),
            "message": str(exc),
        }) from exc
