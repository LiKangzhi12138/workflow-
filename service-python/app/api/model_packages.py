from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.schemas.model_protocol import (
    InspectionReport,
    ModelPackageInspectRequest,
    ModelPackageValidationRequest,
    ModelPackageValidationResult,
)
from app.services.model_package_validation_service import model_package_validation_service
from app.services.weights_package_inspector import weights_package_inspector


router = APIRouter()


@router.post("/internal/model-packages/inspect", response_model=InspectionReport)
def inspect_model_package(payload: ModelPackageInspectRequest) -> InspectionReport:
    if not settings.WEIGHTS_PROTOCOL_V1_ENABLED:
        raise HTTPException(status_code=404, detail="Weights protocol v1 is disabled.")
    return weights_package_inspector.inspect(payload)


@router.post(
    "/internal/model-packages/validate-upload",
    response_model=ModelPackageValidationResult,
)
def validate_uploaded_model_package(
    payload: ModelPackageValidationRequest,
) -> ModelPackageValidationResult:
    if not settings.WEIGHTS_PROTOCOL_V1_ENABLED:
        raise HTTPException(status_code=404, detail="Weights protocol v1 is disabled.")
    return model_package_validation_service.validate(payload)
