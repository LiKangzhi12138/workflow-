import threading
import time
from pathlib import Path

from app.core.config import settings
from app.core.logger import logger
from app.models.request_models import (
    STANDALONE_VALIDATION,
    WEIGHTS_PROTOCOL_V1,
    WORKFLOW_VALIDATION,
)
from app.services.callback_service import callback_to_java
from app.services.job_service import JOB_STORE
from app.services.persistence_service import save_jobs_to_disk
from app.services.result_service import save_result


def resolve_job_type(job: dict) -> str:
    if job.get("jobType"):
        return job["jobType"]
    if job.get("standaloneValidationId") is not None:
        return STANDALONE_VALIDATION
    return WORKFLOW_VALIDATION


def update_job(job_id, status, progress, message, metrics=None, result_file=None, error_message=None):
    job = JOB_STORE.get(job_id)
    if not job:
        logger.error("job not found while updating, jobId=%s", job_id)
        return

    job_type = resolve_job_type(job)
    job["status"] = status
    job["progress"] = progress
    job["message"] = message
    job["errorMessage"] = error_message
    job["resultFilePath"] = result_file.get("filePath") if result_file else None
    save_jobs_to_disk(JOB_STORE)

    payload = {
        "jobId": job_id,
        "jobType": job_type,
        "workflowId": job.get("workflowId"),
        "standaloneValidationId": job.get("standaloneValidationId"),
        "status": status,
        "progress": progress,
        "message": message,
        "metrics": metrics,
        "resultFile": result_file,
        "errorMessage": error_message,
    }

    callback_url = job.get("callbackUrl")
    callback_secret = job.get("callbackSecret") or settings.WORKFLOW_CALLBACK_SECRET

    if not callback_url:
        logger.error(
            "callback url missing, skip callback, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
            job_type,
            job.get("workflowId"),
            job.get("standaloneValidationId"),
            job_id,
        )
        return

    try:
        logger.info(
            "callback payload prepared, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, progress=%s, metricsPrepared=%s, resultFilePrepared=%s, errorMessagePrepared=%s",
            callback_url,
            job_type,
            job.get("workflowId"),
            job.get("standaloneValidationId"),
            job_id,
            status,
            progress,
            metrics is not None,
            bool(result_file and result_file.get("filePath")),
            bool(error_message),
        )
        success, failure_reason = callback_to_java(callback_url, payload, secret=callback_secret)
        if success:
            logger.info(
                "callback payload sent, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s",
                callback_url,
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
                status,
            )
        else:
            logger.warning(
                "callback payload failed after retries, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, failureReason=%s",
                callback_url,
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
                status,
                failure_reason,
            )
    except Exception:
        logger.exception(
            "callback send failed, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s",
            callback_url,
            job_type,
            job.get("workflowId"),
            job.get("standaloneValidationId"),
            job_id,
            status,
        )


def workflow_stages():
    return [
        ("PREPARING", 25, "Preparing workflow validation inputs"),
        ("TRAINING_RUNNING", 60, "Running workflow validation task"),
        ("VALIDATING", 85, "Collecting workflow validation metrics"),
    ]


def standalone_stages():
    return [
        ("VALIDATING", 35, "Loading server-managed model and dataset"),
        ("VALIDATING", 70, "Running standalone validation"),
        ("VALIDATING", 92, "Finalizing standalone validation result"),
    ]


def run_job_async(job_id: str):
    job = JOB_STORE[job_id]
    job_type = resolve_job_type(job)

    logger.info(
        "async job started, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
        job_type,
        job.get("workflowId"),
        job.get("standaloneValidationId"),
        job_id,
    )

    def task():
        try:
            logger.info(
                "task thread started, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
            )
            if job_type == WORKFLOW_VALIDATION:
                logger.info(
                    "job branch selected, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, branch=workflow_validation",
                    job_type,
                    job.get("workflowId"),
                    job.get("standaloneValidationId"),
                    job_id,
                )
                stages = workflow_stages()
            else:
                logger.info(
                    "job branch selected, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, branch=standalone_validation",
                    job_type,
                    job.get("workflowId"),
                    job.get("standaloneValidationId"),
                    job_id,
                )
                stages = standalone_stages()

            for status, progress, message in stages:
                logger.info(
                    "job stage begin, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s",
                    job_type,
                    job.get("workflowId"),
                    job.get("standaloneValidationId"),
                    job_id,
                    status,
                )
                time.sleep(2)
                update_job(job_id, status, progress, message)

            time.sleep(2)

            job_data = JOB_STORE.get(job_id, {})
            model_path = job_data.get("modelPath", "")
            dataset_path = job_data.get("datasetPath", "")
            algorithm_type = job_data.get("algorithmType", "YOLOv10")
            sample_output_dir = str(settings.validation_sample_dir(job_id).resolve())
            model_path_obj = Path(model_path).resolve() if model_path else None
            dataset_path_obj = Path(dataset_path).resolve() if dataset_path else None

            validation_mode = job_data.get("validationMode", "LEGACY_CHECKPOINT")
            logger.info(
                "starting validation dispatch, jobType=%s, validationMode=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, modelPath=%s, datasetPath=%s, algorithmType=%s, sampleOutputDir=%s, modelExists=%s, modelReadable=%s, modelSize=%s, datasetExists=%s, datasetDirectory=%s",
                job_type,
                validation_mode,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
                model_path,
                dataset_path,
                algorithm_type,
                sample_output_dir,
                bool(model_path_obj and model_path_obj.exists()),
                bool(model_path_obj and model_path_obj.is_file()),
                model_path_obj.stat().st_size if model_path_obj and model_path_obj.exists() and model_path_obj.is_file() else -1,
                bool(dataset_path_obj and dataset_path_obj.exists()),
                bool(dataset_path_obj and dataset_path_obj.is_dir()),
            )

            def report_validation_progress(percent, progress_message):
                mapped_percent = 85 + int(percent * 0.10) if job_type == WORKFLOW_VALIDATION else 20 + int(percent * 0.75)
                update_job(
                    job_id,
                    status="VALIDATING",
                    progress=min(mapped_percent, 95),
                    message=progress_message,
                )

            validation_evidence = None
            if validation_mode == WEIGHTS_PROTOCOL_V1:
                report_validation_progress(10, "Checking trusted global weights")
                from app.services.weights_validation_v1_service import weights_validation_v1_service

                runtime_result = weights_validation_v1_service.validate_job(
                    job_data,
                    sample_output_directory=sample_output_dir,
                )
                validation_result = runtime_result.validationResult
                metrics = runtime_result.metrics
                validation_evidence = runtime_result.validationEvidence
            else:
                from app.services.yolo_validation_service import yolo_validation_service

                validation_result = yolo_validation_service.validate(
                    model_path=model_path,
                    dataset_path=dataset_path,
                    yolo_version=algorithm_type,
                    progress_callback=report_validation_progress,
                    sample_output_dir=sample_output_dir,
                )

                if validation_result.get("error"):
                    raise RuntimeError(f"YOLO validation failed: {validation_result['error']}")

                metrics = {
                    "mAP": validation_result.get("map50"),
                    "precision": validation_result.get("precision"),
                    "recall": validation_result.get("recall"),
                    "map50": validation_result.get("map50"),
                    "map50_95": validation_result.get("map50_95"),
                    "accuracy": validation_result.get("accuracy"),
                    "totalImages": validation_result.get("total_images"),
                    "cropDetections": validation_result.get("crop_detections"),
                    "livestockDetections": validation_result.get("livestock_detections"),
                    "perClassResults": validation_result.get("per_class_results"),
                    "fallback": bool(validation_result.get("fallback")),
                    "fallbackReason": validation_result.get("fallback_reason"),
                }

            logger.info(
                "local validation finished, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, accuracy=%s, map50=%s, sampleResultCount=%s, renderedImageMode=%s, metricsFallbackUsed=%s, metricsFallbackReason=%s",
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
                metrics.get("accuracy"),
                metrics.get("map50"),
                len(validation_result.get("sample_results") or []),
                validation_result.get("visualization_mode"),
                metrics.get("fallback"),
                metrics.get("fallbackReason"),
            )

            result = save_result(
                job_id,
                job_type=job_type,
                metrics=metrics,
                validation_result=validation_result,
                validation_evidence=validation_evidence,
                workflow_id=job.get("workflowId"),
                standalone_validation_id=job.get("standaloneValidationId"),
            )

            update_job(
                job_id,
                status="COMPLETED",
                progress=100,
                message="Validation completed",
                metrics=metrics,
                result_file=result,
            )

            logger.info(
                "job finished successfully, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, resultFilePath=%s",
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
                result.get("filePath"),
            )
        except Exception as exc:
            logger.exception(
                "task error, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s",
                job_type,
                job.get("workflowId"),
                job.get("standaloneValidationId"),
                job_id,
            )
            update_job(
                job_id,
                status="FAILED",
                progress=0,
                message="Validation execution failed",
                error_message=str(exc) or "validation execution failed",
            )

    thread = threading.Thread(target=task, daemon=True)
    thread.start()
