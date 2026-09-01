import logging
import os
import shutil
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np

from app.core.config import settings

logger = logging.getLogger(__name__)

CROP_KEYWORDS = [
    "crop",
    "crops",
    "plant",
    "wheat",
    "corn",
    "rice",
    "maize",
    "soybean",
    "cotton",
    "vegetable",
    "fruit",
    "tree",
    "flower",
]

LIVESTOCK_KEYWORDS = [
    "cow",
    "cattle",
    "sheep",
    "pig",
    "goat",
    "horse",
    "chicken",
    "duck",
    "poultry",
    "livestock",
    "bull",
    "calf",
    "lamb",
]

SUPPORTED_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


class YoloValidationService:
    def __init__(
        self,
        confidence_threshold: float = 0.25,
        max_images: int = 1000,
        max_sample_results: int = 6,
        max_predictions_per_image: int = 12,
    ):
        self.confidence_threshold = confidence_threshold
        self.max_images = max_images
        self.max_sample_results = max_sample_results
        self.max_predictions_per_image = max_predictions_per_image

    def validate(
        self,
        model_path: str,
        dataset_path: str,
        yolo_version: str,
        progress_callback=None,
        sample_output_dir: Optional[str] = None,
    ) -> Dict[str, Any]:
        result_template = {
            "mAP": 0.0,
            "precision": 0.0,
            "recall": 0.0,
            "map50": 0.0,
            "map50_95": 0.0,
            "accuracy": 0.0,
            "total_images": 0,
            "crop_detections": 0,
            "livestock_detections": 0,
            "per_class_results": {},
            "sample_results": [],
            "visualization_mode": "METRICS_ONLY",
            "has_annotated_images": False,
            "fallback": False,
            "fallback_reason": None,
            "yolo_version": yolo_version,
            "model_path": model_path,
            "dataset_path": dataset_path,
            "error": None,
        }

        try:
            model_exists = os.path.exists(model_path)
            model_is_file = os.path.isfile(model_path)
            dataset_exists = os.path.exists(dataset_path)
            dataset_is_dir = os.path.isdir(dataset_path)
            model_size = os.path.getsize(model_path) if model_exists and model_is_file else -1
            logger.info(
                "YOLO validation input check, modelPath=%s, datasetPath=%s, modelExists=%s, modelIsFile=%s, modelSize=%s, datasetExists=%s, datasetIsDirectory=%s",
                model_path,
                dataset_path,
                model_exists,
                model_is_file,
                model_size,
                dataset_exists,
                dataset_is_dir,
            )
            if not os.path.exists(model_path):
                raise FileNotFoundError(f"Model file does not exist: {model_path}")
            if not os.path.isfile(model_path):
                raise FileNotFoundError(f"Model path is not a file: {model_path}")
            if not os.path.exists(dataset_path):
                raise FileNotFoundError(f"Dataset path does not exist: {dataset_path}")
            if not os.path.isdir(dataset_path):
                raise FileNotFoundError(f"Dataset path is not a directory: {dataset_path}")

            image_paths = self._collect_images(dataset_path)
            if not image_paths:
                raise ValueError(f"No images found under dataset path: {dataset_path}")

            if len(image_paths) > self.max_images:
                logger.warning(
                    "dataset image count exceeds max_images, actual=%s, max=%s",
                    len(image_paths),
                    self.max_images,
                )
                image_paths = image_paths[: self.max_images]

            result_template["total_images"] = len(image_paths)
            logger.info(
                "starting YOLO validation, modelPath=%s, datasetPath=%s, imageCount=%s, yoloVersion=%s",
                model_path,
                dataset_path,
                len(image_paths),
                yolo_version,
            )

            if progress_callback:
                progress_callback(30, f"Loading YOLO model ({yolo_version})")

            from ultralytics import YOLO

            model = YOLO(model_path)

            if progress_callback:
                progress_callback(40, "Running local image inference")

            if sample_output_dir:
                Path(sample_output_dir).mkdir(parents=True, exist_ok=True)

            class_detection_counts: Dict[str, int] = {}
            class_confidences: Dict[str, List[float]] = {}
            sample_results: List[Dict[str, Any]] = []
            processed = 0
            batch_size = 16

            for batch_start in range(0, len(image_paths), batch_size):
                batch = image_paths[batch_start : batch_start + batch_size]
                try:
                    results = model.predict(
                        source=batch,
                        conf=self.confidence_threshold,
                        verbose=False,
                        stream=False,
                    )
                    for single_result, source_image_path in zip(results, batch):
                        if len(sample_results) < self.max_sample_results:
                            sample_results.append(
                                self._build_sample_result(
                                    single_result,
                                    model.names,
                                    source_image_path=source_image_path,
                                    sample_output_dir=sample_output_dir,
                                )
                            )

                        if single_result.boxes is None or len(single_result.boxes) == 0:
                            continue

                        for box in single_result.boxes:
                            cls_id = int(box.cls.item())
                            cls_name = model.names.get(cls_id, f"class_{cls_id}")
                            confidence = float(box.conf.item())

                            class_detection_counts[cls_name] = class_detection_counts.get(cls_name, 0) + 1
                            class_confidences.setdefault(cls_name, []).append(confidence)
                except Exception as batch_error:
                    logger.warning(
                        "batch inference failed, batchIndex=%s, error=%s",
                        batch_start // batch_size + 1,
                        batch_error,
                    )

                processed += len(batch)
                percent = 40 + int(processed / len(image_paths) * 40)
                if progress_callback:
                    progress_callback(percent, f"Processing validation images {processed}/{len(image_paths)}")

            if sample_results:
                result_template["sample_results"] = sample_results
                result_template["visualization_mode"] = "SOURCE_IMAGE_PREDICTIONS"
                annotated_count = sum(1 for item in sample_results if item.get("annotated_image_available"))
                logger.info(
                    "validation sample results prepared, sampleCount=%s, annotatedCount=%s, sampleOutputDir=%s",
                    len(sample_results),
                    annotated_count,
                    sample_output_dir,
                )

            if progress_callback:
                progress_callback(82, "Aggregating validation metrics")

            has_labels = self._check_labels_exist(dataset_path, image_paths[:5])
            if has_labels:
                metrics_result = self._run_yolo_val(model, dataset_path)
                result_template.update(metrics_result)
            else:
                logger.info(
                    "dataset labels not found, switching to estimated metrics fallback, datasetPath=%s",
                    dataset_path,
                )
                estimated_metrics = self._estimate_metrics(
                    class_detection_counts,
                    class_confidences,
                    len(image_paths),
                )
                result_template.update(estimated_metrics)
                result_template["fallback"] = True
                result_template["fallback_reason"] = "labels_missing_estimated_metrics"

            crop_count = 0
            livestock_count = 0
            per_class_results = {}

            for class_name, detection_count in class_detection_counts.items():
                avg_confidence = float(np.mean(class_confidences.get(class_name, [0.0])))
                category = self._classify(class_name)
                per_class_results[class_name] = {
                    "count": detection_count,
                    "avg_confidence": round(avg_confidence, 4),
                    "category": category,
                }
                if category == "crop":
                    crop_count += detection_count
                elif category == "livestock":
                    livestock_count += detection_count

            result_template["crop_detections"] = crop_count
            result_template["livestock_detections"] = livestock_count
            result_template["per_class_results"] = per_class_results

            if progress_callback:
                progress_callback(95, "Validation metrics prepared")

            logger.info(
                "validation metrics prepared, map50=%s, precision=%s, recall=%s, sampleResultCount=%s, fallback=%s",
                result_template["map50"],
                result_template["precision"],
                result_template["recall"],
                len(result_template["sample_results"]),
                result_template["fallback"],
            )
            return result_template
        except Exception as error:
            logger.error("YOLO validation failed: %s", error, exc_info=True)
            result_template["error"] = str(error)
            return result_template

    def _collect_images(self, dataset_path: str) -> List[str]:
        base_dir = Path(dataset_path)
        images_dir = base_dir / "images"
        search_dir = images_dir if images_dir.exists() else base_dir

        paths = []
        for extension in SUPPORTED_IMAGE_EXTS:
            paths.extend(search_dir.rglob(f"*{extension}"))
            paths.extend(search_dir.rglob(f"*{extension.upper()}"))
        return [str(path) for path in paths]

    def _check_labels_exist(self, dataset_path: str, sample_images: List[str]) -> bool:
        labels_dir = Path(dataset_path) / "labels"
        if not labels_dir.exists():
            return False

        for image_path in sample_images:
            label_file = labels_dir / f"{Path(image_path).stem}.txt"
            if label_file.exists():
                return True
        return False

    def _run_yolo_val(self, model, dataset_path: str) -> Dict[str, Any]:
        try:
            data_yaml = Path(dataset_path) / "data.yaml"
            if not data_yaml.exists():
                self._generate_data_yaml(dataset_path, model.names)
                data_yaml = Path(dataset_path) / "data.yaml"

            validation_output = model.val(
                data=str(data_yaml),
                split="val",
                verbose=False,
                plots=False,
            )

            map50 = float(validation_output.box.map50) if hasattr(validation_output.box, "map50") else 0.0
            map50_95 = float(validation_output.box.map) if hasattr(validation_output.box, "map") else 0.0
            precision = float(validation_output.box.mp) if hasattr(validation_output.box, "mp") else 0.0
            recall = float(validation_output.box.mr) if hasattr(validation_output.box, "mr") else 0.0

            return {
                "mAP": round(map50, 4),
                "map50": round(map50, 4),
                "map50_95": round(map50_95, 4),
                "precision": round(precision, 4),
                "recall": round(recall, 4),
                "accuracy": round((precision + recall) / 2, 4) if (precision + recall) > 0 else 0.0,
            }
        except Exception as error:
            logger.warning("YOLO val metrics collection failed, fallback to infer-only metrics, error=%s", error)
            return {}

    def _generate_data_yaml(self, dataset_path: str, names: Dict[int, str]) -> None:
        import yaml

        data = {
            "path": dataset_path,
            "val": "images",
            "nc": len(names),
            "names": list(names.values()),
        }
        yaml_path = Path(dataset_path) / "data.yaml"
        with open(yaml_path, "w", encoding="utf-8") as handle:
            yaml.dump(data, handle, allow_unicode=True)

    def _estimate_metrics(
        self,
        class_counts: Dict[str, int],
        class_confidences: Dict[str, List[float]],
        total_images: int,
    ) -> Dict[str, Any]:
        if not class_counts:
            return {
                "mAP": 0.0,
                "map50": 0.0,
                "map50_95": 0.0,
                "precision": 0.0,
                "recall": 0.0,
                "accuracy": 0.0,
            }

        all_confidences: List[float] = []
        for confidences in class_confidences.values():
            all_confidences.extend(confidences)

        avg_confidence = float(np.mean(all_confidences)) if all_confidences else 0.0
        estimated_precision = round(avg_confidence, 4)
        detected_images = min(sum(class_counts.values()), total_images)
        estimated_recall = round(detected_images / total_images, 4) if total_images > 0 else 0.0
        estimated_map50 = round((estimated_precision + estimated_recall) / 2, 4)

        return {
            "mAP": estimated_map50,
            "map50": estimated_map50,
            "map50_95": round(estimated_map50 * 0.65, 4),
            "precision": estimated_precision,
            "recall": estimated_recall,
            "accuracy": round((estimated_precision + estimated_recall) / 2, 4),
        }

    def _build_sample_result(
        self,
        result,
        names: Dict[int, str],
        source_image_path: Optional[str] = None,
        sample_output_dir: Optional[str] = None,
    ) -> Dict[str, Any]:
        raw_image_path = source_image_path or str(getattr(result, "path", "") or "")
        image_path = self._resolve_sample_source_path(raw_image_path)
        stored_image_path = self._persist_source_sample(image_path, sample_output_dir)
        predictions = []

        if result.boxes is not None and len(result.boxes) > 0:
            for box in result.boxes:
                class_id = int(box.cls.item())
                label = names.get(class_id, f"class_{class_id}")
                confidence = float(box.conf.item())
                bbox = self._extract_bbox(box)
                predictions.append(
                    {
                        "label": label,
                        "confidence": round(confidence, 4),
                        "category": self._classify(label),
                        "bbox": bbox,
                    }
                )

        predictions.sort(key=lambda item: item["confidence"], reverse=True)
        total_prediction_count = len(predictions)
        predictions = predictions[: self.max_predictions_per_image]
        annotated_image_path = self._render_annotated_sample(
            result=result,
            sample_output_dir=sample_output_dir,
            image_path=image_path,
        )

        return {
            "image_name": Path(image_path).name if image_path else "unknown-image",
            "image_path": stored_image_path or image_path,
            "annotated_image_path": annotated_image_path,
            "annotated_image_available": bool(annotated_image_path),
            "rendered_image_path": annotated_image_path,
            "prediction_count": total_prediction_count,
            "predictions": predictions,
        }

    def _resolve_sample_source_path(self, raw_path: str) -> str:
        if not raw_path:
            return ""
        try:
            return str(Path(raw_path).resolve())
        except Exception:
            return raw_path

    def _extract_bbox(self, box) -> List[float]:
        try:
            values = box.xyxy[0].tolist()
            return [round(float(value), 2) for value in values[:4]]
        except Exception:
            return []

    def _render_annotated_sample(
        self,
        result,
        sample_output_dir: Optional[str],
        image_path: str,
    ) -> Optional[str]:
        if not sample_output_dir:
            return None
        try:
            rendered = result.plot()
            if rendered is None:
                return None

            from PIL import Image

            output_dir = Path(sample_output_dir)
            output_dir.mkdir(parents=True, exist_ok=True)
            image_name = Path(image_path).stem if image_path else f"sample_{int(time.time() * 1000)}"
            output_path = output_dir / f"{image_name}_rendered.jpg"

            if getattr(rendered, "ndim", 0) == 3 and rendered.shape[2] >= 3:
                rendered = rendered[:, :, ::-1]
            Image.fromarray(rendered).save(output_path)
            logger.info("rendered annotated sample image, imagePath=%s, outputPath=%s", image_path, output_path)
            return str(output_path.resolve())
        except Exception as error:
            logger.warning("failed to render annotated sample image, imagePath=%s, error=%s", image_path, error)
            return None

    def _persist_source_sample(self, image_path: str, sample_output_dir: Optional[str]) -> Optional[str]:
        if not image_path or not sample_output_dir:
            return None
        try:
            source = Path(image_path)
            if not source.exists() or not source.is_file():
                return None
            output_dir = Path(sample_output_dir)
            output_dir.mkdir(parents=True, exist_ok=True)
            output_path = output_dir / source.name
            if source.resolve() != output_path.resolve():
                shutil.copy2(source, output_path)
            logger.info("copied sample source image, sourcePath=%s, outputPath=%s", source, output_path)
            return str(output_path.resolve())
        except Exception as error:
            logger.warning("failed to persist sample source image, imagePath=%s, error=%s", image_path, error)
            return None

    def _classify(self, class_name: str) -> str:
        lower_name = class_name.lower()
        if any(keyword in lower_name for keyword in CROP_KEYWORDS):
            return "crop"
        if any(keyword in lower_name for keyword in LIVESTOCK_KEYWORDS):
            return "livestock"
        return "other"


yolo_validation_service = YoloValidationService(
    confidence_threshold=settings.YOLO_CONFIDENCE_THRESHOLD,
    max_images=settings.YOLO_MAX_IMAGES,
)
