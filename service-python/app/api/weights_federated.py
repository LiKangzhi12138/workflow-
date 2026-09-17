from fastapi import APIRouter, HTTPException

from app.core.config import settings
from app.schemas.model_protocol import WeightsFedAvgRequest, WeightsFedAvgResult
from app.services.weights_fedavg_v1_service import WeightsFedAvgError, weights_fedavg_v1_service


router = APIRouter()


@router.post("/internal/federated/aggregate-weights-v1", response_model=WeightsFedAvgResult)
def aggregate_weights_v1(payload: WeightsFedAvgRequest) -> WeightsFedAvgResult:
    if not settings.WEIGHTS_PROTOCOL_V1_ENABLED or not settings.WEIGHTS_FEDAVG_V1_ENABLED:
        raise HTTPException(status_code=404, detail="Weights-only FedAvg v1 is disabled.")
    try:
        return weights_fedavg_v1_service.aggregate(payload)
    except WeightsFedAvgError as exc:
        return WeightsFedAvgResult(
            passed=False,
            reasonCode=exc.code,
            message=str(exc),
            workflowId=payload.workflowId,
            modelDefinitionId=payload.trustedModelDefinition.definitionId,
            modelDefinitionCode=payload.trustedModelDefinition.code,
            inputCount=len(payload.inputs),
            inputAssetIds=[item.assetId for item in payload.inputs],
            aggregationMethod=payload.aggregationMethod,
        )
