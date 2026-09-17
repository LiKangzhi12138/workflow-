from __future__ import annotations

import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.core.config import settings
from app.models.request_models import CreateJobRequest
from app.services.job_service import JOB_STORE
from app.schemas.model_protocol import (
    ModelDefinition,
    ModelDescriptor,
    RuntimeWeightsValidationResult,
    WeightsPackageManifest,
)
from app.services.weights_validation_v1_service import (
    WeightsValidationV1Error,
    WeightsValidationV1Service,
)
from app.services.yolo_runtime_client import YoloRuntimeClientError
from app.workers import task_runner


DEFINITION_PATH = SERVICE_ROOT / "app" / "model_definitions" / "YOLOV8N_SHEEP_V1.json"
HASH_A = "a" * 64
HASH_B = "b" * 64
HASH_C = "c" * 64
WEIGHTS_SHA = "d" * 64


class WeightsValidationV1ServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.definition = ModelDefinition.model_validate(
            json.loads(DEFINITION_PATH.read_text(encoding="utf-8"))
        )
        self.descriptor = ModelDescriptor.model_validate({
            "schemaVersion": "1.0",
            "descriptorVersion": "1.0",
            "modelFamily": self.definition.modelFamily,
            "version": self.definition.version,
            "variant": self.definition.variant,
            "taskType": self.definition.taskType,
            "framework": {"name": self.definition.framework, "version": self.definition.frameworkVersion},
            "modelDefinition": {
                "definitionId": self.definition.definitionId,
                "definitionSha256": self.definition.definitionSha256,
            },
            "classes": {"count": 1, "order": ["sheep"], "ignoreIndex": None},
            "stateContract": {
                "keyCount": 355,
                "tensorCount": 355,
                "elementCount": 3021500,
                "stateDictKeyHash": HASH_A,
                "shapeSignature": HASH_B,
                "clientDtypeSignature": HASH_C,
            },
            "parameterSemantics": "EMA_SNAPSHOT",
            "inputSpec": self.definition.inputSpec,
            "preprocessing": self.definition.definitionPayload.get("preprocessing", {}),
            "runtimeRequirements": {"runtimeProfileId": "YOLO_RUNTIME_V1"},
            "extensions": {"architectureSignature": self.definition.architectureSignature},
        })
        self.manifest = WeightsPackageManifest.model_validate({
            "schemaVersion": "1.0",
            "weightsFormatVersion": "1.0",
            "packageId": "global-37",
            "artifactType": "FEDERATED_WEIGHTS",
            "descriptorSha256": "e" * 64,
            "modelDefinition": {
                "definitionId": self.definition.definitionId,
                "definitionSha256": self.definition.definitionSha256,
            },
            "architectureSignature": self.definition.architectureSignature,
            "parameterSemantics": "EMA_SNAPSHOT",
            "weights": {
                "format": "PYTORCH_STATE_DICT",
                "wrapperKey": "state_dict",
                "fileName": "global_weights.pt",
                "sizeBytes": 123,
                "sha256": WEIGHTS_SHA,
            },
            "state": {
                "keyCount": 355,
                "tensorCount": 355,
                "elementCount": 3021500,
                "stateDictKeyHash": HASH_A,
                "shapeSignature": HASH_B,
                "dtypeSignature": HASH_C,
                "dtypeCounts": {"float32": 298, "int64": 57},
            },
            "aggregationContribution": 1.0,
            "createdBy": {"type": "SERVER_WEIGHTS_FEDAVG_V1"},
        })
        self.inspection = SimpleNamespace(
            passed=True,
            weightsSha256=WEIGHTS_SHA,
            tensorCount=355,
            elementCount=3021500,
            stateDictKeyHash=HASH_A,
            shapeSignature=HASH_B,
            dtypeSignature=HASH_C,
        )

    def test_dispatches_inspected_global_weights_and_materializes_runtime_sample(self) -> None:
        runtime_result = self.runtime_result()
        runtime_result.validationResult["sample_results"] = [{
            "image_name": "sample.jpg",
            "rendered_image_base64": base64.b64encode(b"jpeg-data").decode("ascii"),
            "predictions": [],
        }]
        with tempfile.TemporaryDirectory() as root:
            old_root = settings.PY_VALIDATION_SAMPLE_ROOT
            settings.PY_VALIDATION_SAMPLE_ROOT = root
            try:
                with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True), patch(
                    "app.services.weights_validation_v1_service.weights_package_inspector.inspect",
                    return_value=self.inspection,
                ) as inspect, patch(
                    "app.services.weights_validation_v1_service.yolo_runtime_client.validate_weights",
                    return_value=runtime_result,
                ) as runtime, patch.object(
                    WeightsValidationV1Service,
                    "_read_model",
                    side_effect=[self.manifest, self.descriptor],
                ):
                    result = WeightsValidationV1Service().validate_job(
                        self.job(),
                        sample_output_directory=str(Path(root) / "job-1"),
                    )
            finally:
                settings.PY_VALIDATION_SAMPLE_ROOT = old_root

        self.assertTrue(result.passed)
        inspect.assert_called_once()
        runtime.assert_called_once()
        sample = result.validationResult["sample_results"][0]
        self.assertNotIn("rendered_image_base64", sample)
        self.assertTrue(sample["annotated_image_available"])

    def test_rejects_trusted_definition_identity_mismatch_before_runtime(self) -> None:
        wrong_manifest = self.manifest.model_copy(deep=True)
        wrong_manifest.modelDefinition.definitionId = "OTHER_MODEL_V1"
        with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True), patch(
            "app.services.weights_validation_v1_service.weights_package_inspector.inspect",
            return_value=self.inspection,
        ), patch(
            "app.services.weights_validation_v1_service.yolo_runtime_client.validate_weights",
        ) as runtime, patch.object(
            WeightsValidationV1Service,
            "_read_model",
            side_effect=[wrong_manifest, self.descriptor],
        ):
            with self.assertRaises(WeightsValidationV1Error) as error:
                WeightsValidationV1Service().validate_job(
                    self.job(),
                    sample_output_directory="unused",
                )
        self.assertEqual("WEIGHTS_VALIDATION_V1_DEFINITION_MISMATCH", error.exception.code)
        runtime.assert_not_called()

    def test_runtime_unavailable_fails_closed(self) -> None:
        with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True), patch(
            "app.services.weights_validation_v1_service.weights_package_inspector.inspect",
            return_value=self.inspection,
        ), patch(
            "app.services.weights_validation_v1_service.yolo_runtime_client.validate_weights",
            side_effect=YoloRuntimeClientError("unreachable"),
        ), patch.object(
            WeightsValidationV1Service,
            "_read_model",
            side_effect=[self.manifest, self.descriptor],
        ):
            with self.assertRaises(WeightsValidationV1Error) as error:
                WeightsValidationV1Service().validate_job(
                    self.job(),
                    sample_output_directory="unused",
                )
        self.assertEqual("WEIGHTS_VALIDATION_V1_RUNTIME_FAILED", error.exception.code)

    def test_unsupported_runtime_profile_is_rejected_before_dispatch(self) -> None:
        job = self.job()
        job["runtimeProfileId"] = "UNKNOWN_RUNTIME_V1"

        with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True), patch(
            "app.services.weights_validation_v1_service.yolo_runtime_client.validate_weights",
        ) as runtime:
            with self.assertRaises(WeightsValidationV1Error) as error:
                WeightsValidationV1Service().validate_job(
                    job,
                    sample_output_directory="unused",
                )

        self.assertEqual("RUNTIME_PROFILE_NOT_FOUND", error.exception.code)
        runtime.assert_not_called()

    def test_inspected_sha_mismatch_is_rejected_before_runtime(self) -> None:
        wrong_inspection = SimpleNamespace(**vars(self.inspection))
        wrong_inspection.weightsSha256 = "f" * 64

        with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True), patch(
            "app.services.weights_validation_v1_service.weights_package_inspector.inspect",
            return_value=wrong_inspection,
        ), patch(
            "app.services.weights_validation_v1_service.yolo_runtime_client.validate_weights",
        ) as runtime, patch.object(
            WeightsValidationV1Service,
            "_read_model",
            side_effect=[self.manifest, self.descriptor],
        ):
            with self.assertRaises(WeightsValidationV1Error) as error:
                WeightsValidationV1Service().validate_job(
                    self.job(),
                    sample_output_directory="unused",
                )

        self.assertEqual("WEIGHTS_VALIDATION_V1_SHA_MISMATCH", error.exception.code)
        runtime.assert_not_called()

    def test_invalid_trusted_definition_is_rejected_with_safe_error(self) -> None:
        job = self.job()
        job["trustedModelDefinition"] = {"definitionId": self.definition.definitionId}

        with patch.object(settings, "WEIGHTS_VALIDATION_V1_ENABLED", True):
            with self.assertRaises(WeightsValidationV1Error) as error:
                WeightsValidationV1Service().validate_job(
                    job,
                    sample_output_directory="unused",
                )

        self.assertEqual("WEIGHTS_VALIDATION_V1_DEFINITION_INVALID", error.exception.code)
        self.assertEqual("The trusted ModelDefinition is invalid.", str(error.exception))

    def test_job_schema_requires_v1_contract_fields_at_api_validation_layer(self) -> None:
        request = CreateJobRequest(
            jobId="job-1",
            jobType="WORKFLOW_VALIDATION",
            workflowId=37,
            modelPath="/storage/global_weights.pt",
            datasetPath="/storage/datasets/sheep",
            algorithmType="YOLOv8",
            callbackUrl="http://service-java/callback",
            callbackSecret="secret",
            validationMode="WEIGHTS_PROTOCOL_V1",
            runtimeProfileId="YOLO_RUNTIME_V1",
            trustedModelDefinition=self.definition.model_dump(mode="json"),
            globalWeights=self.job()["globalWeights"],
        )
        self.assertEqual("WEIGHTS_PROTOCOL_V1", request.validationMode)
        self.assertEqual(77, request.globalWeights.assetId)

    def test_async_job_uses_v1_dispatcher_and_keeps_callback_contract(self) -> None:
        job = self.job()
        job.update({
            "jobType": "WORKFLOW_VALIDATION",
            "standaloneValidationId": None,
            "validationMode": "WEIGHTS_PROTOCOL_V1",
            "callbackUrl": "http://service-java/callback",
            "callbackSecret": "secret",
            "modelPath": job["globalWeights"]["weightsPath"],
        })
        JOB_STORE["job-1"] = job
        updates = []

        class ImmediateThread:
            def __init__(self, target, daemon):
                self.target = target

            def start(self):
                self.target()

        with patch("app.workers.task_runner.threading.Thread", ImmediateThread), patch(
            "app.workers.task_runner.time.sleep"
        ), patch("app.workers.task_runner.update_job", side_effect=lambda *args, **kwargs: updates.append((args, kwargs))), patch(
            "app.services.weights_validation_v1_service.weights_validation_v1_service.validate_job",
            return_value=self.runtime_result(),
        ) as dispatcher, patch(
            "app.workers.task_runner.save_result",
            return_value={"fileName": "job-1_result.json", "filePath": "/storage/job-1_result.json"},
        ):
            task_runner.run_job_async("job-1")

        dispatcher.assert_called_once()
        self.assertEqual("COMPLETED", updates[-1][1]["status"])
        self.assertEqual(100, updates[-1][1]["progress"])
        JOB_STORE.pop("job-1", None)

    def test_async_job_reports_v1_dispatch_failure(self) -> None:
        job = self.job()
        job.update({
            "jobType": "WORKFLOW_VALIDATION",
            "standaloneValidationId": None,
            "validationMode": "WEIGHTS_PROTOCOL_V1",
            "callbackUrl": "http://service-java/callback",
            "callbackSecret": "secret",
            "modelPath": job["globalWeights"]["weightsPath"],
        })
        JOB_STORE["job-1"] = job
        updates = []

        class ImmediateThread:
            def __init__(self, target, daemon):
                self.target = target

            def start(self):
                self.target()

        with patch("app.workers.task_runner.threading.Thread", ImmediateThread), patch(
            "app.workers.task_runner.time.sleep"
        ), patch("app.workers.task_runner.update_job", side_effect=lambda *args, **kwargs: updates.append((args, kwargs))), patch(
            "app.services.weights_validation_v1_service.weights_validation_v1_service.validate_job",
            side_effect=WeightsValidationV1Error("RUNTIME_NOT_READY", "Model runtime is unavailable."),
        ):
            task_runner.run_job_async("job-1")

        self.assertEqual("FAILED", updates[-1][1]["status"])
        self.assertEqual("Model runtime is unavailable.", updates[-1][1]["error_message"])
        JOB_STORE.pop("job-1", None)

    def test_update_job_callback_payload_keeps_job_type_and_workflow_identity(self) -> None:
        JOB_STORE["job-callback"] = {
            "jobId": "job-callback",
            "jobType": "WORKFLOW_VALIDATION",
            "workflowId": 37,
            "standaloneValidationId": None,
            "status": "VALIDATING",
            "callbackUrl": "http://service-java/callback",
            "callbackSecret": "secret",
        }
        with patch("app.workers.task_runner.save_jobs_to_disk"), patch(
            "app.workers.task_runner.callback_to_java", return_value=(True, None)
        ) as callback:
            task_runner.update_job(
                "job-callback",
                status="COMPLETED",
                progress=100,
                message="done",
                metrics={"map50": 0.9},
                result_file={"fileName": "result.json", "filePath": "/storage/result.json"},
            )
        payload = callback.call_args.args[1]
        self.assertEqual("WORKFLOW_VALIDATION", payload["jobType"])
        self.assertEqual(37, payload["workflowId"])
        self.assertIsNone(payload["standaloneValidationId"])
        JOB_STORE.pop("job-callback", None)

    def job(self) -> dict:
        return {
            "jobId": "job-1",
            "workflowId": 37,
            "datasetPath": "/storage/dataset",
            "algorithmType": "YOLOv8",
            "runtimeProfileId": "YOLO_RUNTIME_V1",
            "trustedModelDefinition": self.definition.model_dump(mode="json"),
            "globalWeights": {
                "assetId": 77,
                "weightsPath": "/storage/global_weights.pt",
                "expectedSha256": WEIGHTS_SHA,
                "manifestPath": "/storage/aggregation-manifest.json",
                "descriptorPath": "/storage/descriptor.json",
                "inspectionReportPath": "/storage/inspection-report.json",
                "aggregationReportPath": "/storage/aggregation-report.json",
                "expectedTensorCount": 355,
                "expectedElementCount": 3021500,
                "expectedStateDictKeyHash": HASH_A,
                "expectedShapeSignature": HASH_B,
                "expectedDtypeSignature": HASH_C,
            },
        }

    def runtime_result(self) -> RuntimeWeightsValidationResult:
        return RuntimeWeightsValidationResult(
            passed=True,
            reasonCode="WEIGHTS_VALIDATION_COMPLETED",
            message="ok",
            runtimeProfileId="YOLO_RUNTIME_V1",
            modelDefinitionId=self.definition.definitionId,
            modelDefinitionCode=self.definition.code,
            definitionSha256=self.definition.definitionSha256,
            architectureSignature=self.definition.architectureSignature,
            weightsSha256=WEIGHTS_SHA,
            reconstruction={
                "weightsOnly": True,
                "checkpointUsed": False,
                "baseCheckpointUsed": False,
                "strictLoad": True,
                "missingKeyCount": 0,
                "unexpectedKeyCount": 0,
            },
            metrics={"map50": 0.9},
            validationResult={"sample_results": []},
            validationEvidence={"validationMode": "WEIGHTS_PROTOCOL_V1"},
        )


if __name__ == "__main__":
    unittest.main()
