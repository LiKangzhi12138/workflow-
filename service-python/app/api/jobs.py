from fastapi import APIRouter, Body, HTTPException, Request

from app.core.config import settings
from app.core.logger import logger
from app.models.request_models import (
    CreateJobRequest,
    WEIGHTS_PROTOCOL_V1,
    STANDALONE_VALIDATION,
    WORKFLOW_VALIDATION,
)
from app.models.response_models import CreateJobResponse, JobStatusData, JobStatusResponse
from app.services.job_service import JOB_STORE, get_job
from app.services.persistence_service import save_jobs_to_disk
from app.workers.task_runner import run_job_async

router = APIRouter()


def validate_create_job_request(req: CreateJobRequest) -> None:
    if req.jobType == WORKFLOW_VALIDATION:
        if req.workflowId is None:
            raise HTTPException(
                status_code=422,
                detail="workflowId is required when jobType=WORKFLOW_VALIDATION",
            )
        if req.validationMode == WEIGHTS_PROTOCOL_V1:
            if (
                req.runtimeProfileId is None
                or req.trustedModelDefinition is None
                or req.globalWeights is None
            ):
                raise HTTPException(
                    status_code=422,
                    detail="runtimeProfileId, trustedModelDefinition and globalWeights are required for weights protocol v1",
                )
        return

    if req.jobType == STANDALONE_VALIDATION:
        if req.standaloneValidationId is None:
            raise HTTPException(
                status_code=422,
                detail="standaloneValidationId is required when jobType=STANDALONE_VALIDATION",
            )
        return

    raise HTTPException(status_code=422, detail=f"Unsupported jobType: {req.jobType}")


@router.post("/internal/jobs", response_model=CreateJobResponse)
async def create_job(http_request: Request, payload: CreateJobRequest = Body(...)):
    raw_body_bytes = await http_request.body()
    validate_create_job_request(payload)

    logger.info(
        "create_job request received, path=%s, method=%s, contentType=%s, rawBodyLength=%s, jobType=%s, validationMode=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, modelPath=%s, datasetPath=%s, callbackUrl=%s",
        http_request.url.path,
        http_request.method,
        http_request.headers.get("content-type"),
        len(raw_body_bytes),
        payload.jobType,
        payload.validationMode,
        payload.workflowId,
        payload.standaloneValidationId,
        payload.jobId,
        payload.modelPath,
        payload.datasetPath,
        payload.callbackUrl,
    )

    existing = JOB_STORE.get(payload.jobId)
    if existing:
        logger.warning(
            "duplicate job create ignored, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
            existing.get("jobType"),
            existing.get("workflowId"),
            existing.get("standaloneValidationId"),
            payload.jobId,
        )
        return {
            "code": "OK",
            "message": "job already exists",
            "data": {
                "jobId": existing["jobId"],
                "status": existing["status"],
            },
        }

    JOB_STORE[payload.jobId] = {
        "jobId": payload.jobId,
        "jobType": payload.jobType,
        "workflowId": payload.workflowId,
        "standaloneValidationId": payload.standaloneValidationId,
        "status": "ACCEPTED",
        "progress": 0,
        "message": "job accepted",
        "resultFilePath": None,
        "errorMessage": None,
        "callbackUrl": payload.callbackUrl,
        "callbackSecret": payload.callbackSecret or settings.WORKFLOW_CALLBACK_SECRET,
        "modelPath": payload.modelPath,
        "datasetPath": payload.datasetPath,
        "algorithmType": payload.algorithmType,
        "validationMode": payload.validationMode,
        "runtimeProfileId": payload.runtimeProfileId,
        "trustedModelDefinition": payload.trustedModelDefinition,
        "globalWeights": payload.globalWeights.model_dump() if payload.globalWeights else None,
    }
    save_jobs_to_disk(JOB_STORE)

    logger.info(
        "create_job accepted, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
        payload.jobType,
        payload.workflowId,
        payload.standaloneValidationId,
        payload.jobId,
    )
    run_job_async(payload.jobId)

    return {
        "code": "OK",
        "message": "job accepted",
        "data": {
            "jobId": payload.jobId,
            "status": "ACCEPTED",
        },
    }


@router.get("/internal/jobs/{jobId}", response_model=JobStatusResponse)
def get_job_status(jobId: str):
    job = get_job(jobId)

    if not job:
        raise HTTPException(status_code=404, detail="job not found")

    data = JobStatusData(
        jobId=job["jobId"],
        jobType=job.get("jobType", WORKFLOW_VALIDATION if job.get("workflowId") is not None else STANDALONE_VALIDATION),
        workflowId=job.get("workflowId"),
        standaloneValidationId=job.get("standaloneValidationId"),
        status=job["status"],
        progress=job["progress"],
        message=job["message"],
        resultFilePath=job.get("resultFilePath"),
        errorMessage=job.get("errorMessage"),
    )

    return JobStatusResponse(code="OK", message="success", data=data.model_dump())
