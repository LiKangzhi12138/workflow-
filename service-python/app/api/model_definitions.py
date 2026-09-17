from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.schemas.model_protocol import ModelAvailabilityResult, ModelDefinitionCheckRequest
from app.services.model_definition_check_service import model_definition_check_service


router = APIRouter()


@router.post("/internal/model-definitions/check", response_model=ModelAvailabilityResult)
def check_model_definition(payload: ModelDefinitionCheckRequest) -> ModelAvailabilityResult:
    if not settings.MODEL_DEFINITION_REGISTRY_V1_ENABLED:
        raise HTTPException(status_code=404, detail="ModelDefinition Registry v1 is disabled.")
    return model_definition_check_service.check(
        payload.modelDefinition,
        payload.runtimeProfileId,
    )
