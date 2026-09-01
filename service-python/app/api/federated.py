from fastapi import APIRouter, Body, HTTPException, Request
from pydantic import BaseModel

from app.core.logger import logger
from app.services.federated_aggregation_service import (
    FederatedAggregationError,
    federated_aggregation_service,
)

router = APIRouter()


class FederatedAggregateRequest(BaseModel):
    workflowId: int
    strategy: str
    modelPaths: list[str]
    outputModelPath: str
    dpEnabled: bool | None = False
    dpEpsilon: float | None = None
    dpDelta: float | None = None
    dpClipNorm: float | None = None
    dpNoiseMultiplier: float | None = None
    shuffleEnabled: bool | None = False
    shuffleBatchNo: str | None = None
    secureAggregationEnabled: bool | None = False
    secureAggregationMode: str | None = "PLAIN"


@router.post("/internal/federated/aggregate")
async def aggregate_models(
    http_request: Request,
    payload: FederatedAggregateRequest = Body(...),
):
    raw_body_bytes = await http_request.body()
    raw_body = raw_body_bytes.decode("utf-8", errors="ignore")
    content_type = http_request.headers.get("content-type")
    accept = http_request.headers.get("accept")
    headers = dict(http_request.headers)

    logger.info(
        "federated aggregate request received, path=%s, headers=%s, contentType=%s, accept=%s, rawBodyLength=%s, rawBody=%s, parsedRequest=%s, modelPathsCount=%s",
        http_request.url.path,
        headers,
        content_type,
        accept,
        len(raw_body_bytes),
        raw_body,
        payload.model_dump(),
        len(payload.modelPaths),
    )

    if payload.strategy.upper() not in {"FEDAVG", "FEDML"}:
        logger.warning(
            "federated aggregate rejected because strategy is unsupported, workflowId=%s, strategy=%s",
            payload.workflowId,
            payload.strategy,
        )
        raise HTTPException(status_code=422, detail=f"Unsupported strategy: {payload.strategy}")

    try:
        logger.info(
            "federated aggregate starting, workflowId=%s, strategy=%s, modelPaths=%s, outputModelPath=%s",
            payload.workflowId,
            payload.strategy,
            payload.modelPaths,
            payload.outputModelPath,
        )
        data = federated_aggregation_service.aggregate_fedavg(
            payload.modelPaths,
            payload.outputModelPath,
            strategy=payload.strategy.upper(),
            dp_context={
                "enabled": bool(payload.dpEnabled),
                "epsilon": payload.dpEpsilon,
                "delta": payload.dpDelta,
                "clipNorm": payload.dpClipNorm,
                "noiseMultiplier": payload.dpNoiseMultiplier,
            },
            shuffle_context={
                "enabled": bool(payload.shuffleEnabled),
                "batchNo": payload.shuffleBatchNo,
            },
            secure_aggregation_context={
                "enabled": bool(payload.secureAggregationEnabled),
                "mode": payload.secureAggregationMode or "PLAIN",
            },
        )
        logger.info(
            "federated aggregate completed, workflowId=%s, strategy=%s, globalModelPath=%s, summary=%s",
            payload.workflowId,
            payload.strategy,
            data.get("globalModelPath"),
            data.get("summary"),
        )
        return {
            "code": "OK",
            "message": "federated aggregation completed",
            "data": data,
        }
    except FederatedAggregationError as exc:
        summary = str(exc)
        logger.warning(
            "federated aggregate business failure, workflowId=%s, strategy=%s, message=%s",
            payload.workflowId,
            payload.strategy,
            summary,
        )
        return {
            "code": "ERROR",
            "message": summary,
            "data": {
                "status": "FAILED",
                "strategy": payload.strategy,
                "sourceModelCount": len(payload.modelPaths),
                "globalModelPath": None,
                "summary": summary,
                "errorMessage": summary,
                "detailMessage": summary,
            },
        }
    except Exception as exc:
        summary = "联邦学习聚合失败：Python 聚合服务内部异常，请查看 Python 日志"
        logger.exception(
            "federated aggregate system failure, workflowId=%s, strategy=%s",
            payload.workflowId,
            payload.strategy,
        )
        return {
            "code": "ERROR",
            "message": summary,
            "data": {
                "status": "FAILED",
                "strategy": payload.strategy,
                "sourceModelCount": len(payload.modelPaths),
                "globalModelPath": None,
                "summary": summary,
                "errorMessage": summary,
                "detailMessage": str(exc),
            },
        }
