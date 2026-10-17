from __future__ import annotations

import json

from app.core.config import settings
from app.services.result_service import save_result


def test_segmentation_result_keeps_dataset_identity_and_real_metrics(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "validation_output_root_path", lambda: tmp_path)
    result = save_result(
        "segmentation-job",
        job_type="WORKFLOW_VALIDATION",
        workflow_id=38,
        dataset_asset_id=42,
        metrics={
            "taskType": "SEMANTIC_SEGMENTATION",
            "metricsMode": "GROUND_TRUTH",
            "metricType": "SEGMENTATION_GROUND_TRUTH",
            "mIoU": 0.5,
            "mAcc": 0.6,
            "aAcc": 0.7,
            "perClass": [],
        },
        validation_evidence={"checkpointUsed": False, "strictLoad": True},
    )
    saved = json.loads((tmp_path / result["fileName"]).read_text(encoding="utf-8"))
    assert saved["datasetAssetId"] == 42
    assert saved["taskType"] == "SEMANTIC_SEGMENTATION"
    assert saved["metrics"]["mIoU"] == 0.5
    assert "map50" not in saved["metrics"]
    assert "accuracy" not in saved["metrics"]
    assert saved["validationEvidence"] == {"checkpointUsed": False, "strictLoad": True}
