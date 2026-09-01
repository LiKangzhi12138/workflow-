from typing import Any, Dict

from app.core.constants import ALLOWED_TRANSITIONS
from app.services.persistence_service import load_jobs_from_disk, save_jobs_to_disk

JOB_STORE: Dict[str, Dict[str, Any]] = load_jobs_from_disk() or {}


def create_job(job_data: dict):
    job_id = job_data["jobId"]
    if job_id in JOB_STORE:
        return JOB_STORE[job_id]

    JOB_STORE[job_id] = {
        "jobId": job_id,
        "jobType": job_data.get("jobType"),
        "workflowId": job_data.get("workflowId"),
        "standaloneValidationId": job_data.get("standaloneValidationId"),
        "status": "ACCEPTED",
        "progress": 0,
        "message": "job accepted",
        "resultFilePath": None,
        "errorMessage": None,
        "callbackUrl": job_data.get("callbackUrl"),
        "callbackSecret": job_data.get("callbackSecret"),
    }
    save_jobs_to_disk(JOB_STORE)
    return JOB_STORE[job_id]


def get_job(job_id: str):
    return JOB_STORE.get(job_id)


def is_valid_transition(old_status: str, new_status: str) -> bool:
    if old_status not in ALLOWED_TRANSITIONS:
        return False
    return new_status in ALLOWED_TRANSITIONS[old_status]


def update_job(job_id, **kwargs):
    if job_id not in JOB_STORE:
        return

    job = JOB_STORE[job_id]
    old_status = job.get("status")
    new_status = kwargs.get("status")

    if new_status and old_status and not is_valid_transition(old_status, new_status):
        return

    job.update(kwargs)
    save_jobs_to_disk(JOB_STORE)
