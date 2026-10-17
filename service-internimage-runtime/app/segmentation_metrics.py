from __future__ import annotations

import numpy as np


class SegmentationMetricError(ValueError):
    pass


class ConfusionMetrics:
    def __init__(self, class_names: list[str], ignore_index: int) -> None:
        self.class_names = class_names
        self.ignore_index = ignore_index
        self.matrix = np.zeros((len(class_names), len(class_names)), dtype=np.int64)
        self.valid_pixels = 0

    def add(self, prediction: np.ndarray, target: np.ndarray) -> None:
        if prediction.shape != target.shape or prediction.ndim != 2 or target.ndim != 2:
            raise SegmentationMetricError("Prediction and mask must be matching label maps.")
        count = len(self.class_names)
        allowed = (target == self.ignore_index) | ((target >= 0) & (target < count))
        if not bool(np.all(allowed)):
            raise SegmentationMetricError("Mask contains a label outside the trusted class order.")
        valid = target != self.ignore_index
        if not bool(np.all((prediction[valid] >= 0) & (prediction[valid] < count))):
            raise SegmentationMetricError("Prediction contains an invalid class label.")
        self.valid_pixels += int(np.count_nonzero(valid))
        bins = np.bincount(
            count * target[valid].astype(np.int64) + prediction[valid].astype(np.int64),
            minlength=count * count,
        )
        self.matrix += bins.reshape((count, count))

    def result(self) -> dict:
        if self.valid_pixels == 0:
            raise SegmentationMetricError("Dataset contains no non-ignored ground-truth pixels.")
        intersections = np.diag(self.matrix)
        targets = self.matrix.sum(axis=1)
        predictions = self.matrix.sum(axis=0)
        unions = targets + predictions - intersections
        per_class = []
        ious = []
        accuracies = []
        for index, name in enumerate(self.class_names):
            iou = float(intersections[index] / unions[index]) if unions[index] else None
            accuracy = float(intersections[index] / targets[index]) if targets[index] else None
            per_class.append({"classId": index, "className": name, "iou": iou, "accuracy": accuracy})
            if iou is not None:
                ious.append(iou)
            if accuracy is not None:
                accuracies.append(accuracy)
        return {
            "taskType": "SEMANTIC_SEGMENTATION",
            "metricType": "SEGMENTATION_GROUND_TRUTH",
            "metricsMode": "GROUND_TRUTH",
            "mIoU": sum(ious) / len(ious) if ious else None,
            "mAcc": sum(accuracies) / len(accuracies) if accuracies else None,
            "aAcc": float(intersections.sum() / self.valid_pixels),
            "perClass": per_class,
            "validPixelCount": self.valid_pixels,
        }
