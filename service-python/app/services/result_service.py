import json
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from app.core.config import settings


def save_result(
    job_id: str,
    *,
    job_type: str,
    metrics: Optional[Dict[str, Any]] = None,
    validation_result: Optional[Dict[str, Any]] = None,
    workflow_id: Optional[int] = None,
    standalone_validation_id: Optional[int] = None,
):
    result_dir = settings.validation_output_root_path()
    result_dir.mkdir(parents=True, exist_ok=True)

    file_name = f"{job_id}_result.json"
    file_path = result_dir / file_name

    safe_metrics = dict(metrics or {})
    safe_validation_result = dict(validation_result or {})

    fallback = bool(
        safe_metrics.get("fallback")
        or safe_validation_result.get("fallback")
    )
    fallback_reason = (
        safe_metrics.get("fallbackReason")
        or safe_metrics.get("fallback_reason")
        or safe_validation_result.get("fallbackReason")
        or safe_validation_result.get("fallback_reason")
    )

    result_data = {
        "jobId": job_id,
        "jobType": job_type,
        "workflowId": workflow_id,
        "standaloneValidationId": standalone_validation_id,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "metrics": safe_metrics,
        "fallback": fallback,
        "fallbackReason": fallback_reason,
        "visualizationMode": safe_validation_result.get("visualization_mode", "METRICS_ONLY"),
        "hasAnnotatedImages": bool(safe_validation_result.get("has_annotated_images")),
        "sampleResults": _normalize_sample_results(safe_validation_result.get("sample_results")),
        "sourceSummary": {
            "modelPath": safe_validation_result.get("model_path"),
            "datasetPath": safe_validation_result.get("dataset_path"),
            "yoloVersion": safe_validation_result.get("yolo_version"),
            "totalImages": safe_validation_result.get("total_images"),
        },
    }

    with file_path.open("w", encoding="utf-8") as file:
        json.dump(result_data, file, indent=2, ensure_ascii=False)

    print_message = (
        "saved validation result file, "
        f"jobId={job_id}, jobType={job_type}, "
        f"sampleResultCount={len(result_data['sampleResults'])}, "
        f"resultFilePath={file_path}"
    )
    try:
        from app.core.logger import logger

        logger.info(print_message)
    except Exception:
        pass

    return {"fileName": file_name, "filePath": str(file_path)}


def _normalize_sample_results(sample_results: Any) -> List[Dict[str, Any]]:
    if not isinstance(sample_results, list):
        return []

    normalized: List[Dict[str, Any]] = []
    for item in sample_results:
        if not isinstance(item, dict):
            continue

        normalized_predictions = []
        raw_predictions = item.get("predictions")
        if isinstance(raw_predictions, list):
            for prediction in raw_predictions:
                if not isinstance(prediction, dict):
                    continue
                normalized_predictions.append(
                    {
                        "label": prediction.get("label"),
                        "confidence": prediction.get("confidence"),
                        "category": prediction.get("category"),
                        "bbox": prediction.get("bbox"),
                    }
                )

        normalized.append(
            {
                "imageName": item.get("image_name") or item.get("imageName"),
                "imagePath": item.get("image_path") or item.get("imagePath"),
                "annotatedImagePath": item.get("annotated_image_path")
                or item.get("annotatedImagePath")
                or item.get("rendered_image_path")
                or item.get("renderedImagePath"),
                "annotatedImageAvailable": bool(
                    item.get("annotated_image_available")
                    or item.get("annotatedImageAvailable")
                    or item.get("rendered_image_path")
                    or item.get("renderedImagePath")
                ),
                "predictionCount": item.get("prediction_count")
                or item.get("predictionCount")
                or len(normalized_predictions),
                "predictions": normalized_predictions,
            }
        )
    return normalized
