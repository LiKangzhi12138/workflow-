from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()


def _get_int(*keys: str, default: int) -> int:
    for key in keys:
        value = os.getenv(key)
        if value not in (None, ""):
            return int(value)
    return default


def _get_float(*keys: str, default: float) -> float:
    for key in keys:
        value = os.getenv(key)
        if value not in (None, ""):
            return float(value)
    return default


def _get_bool(*keys: str, default: bool) -> bool:
    for key in keys:
        value = os.getenv(key)
        if value not in (None, ""):
            normalized = value.strip().lower()
            if normalized in {"1", "true", "yes", "on"}:
                return True
            if normalized in {"0", "false", "no", "off"}:
                return False
            raise ValueError(f"environment variable {key} must be a boolean value")
    return default


def _get_str(*keys: str, default: str) -> str:
    for key in keys:
        value = os.getenv(key)
        if value not in (None, ""):
            return value
    return default


class Settings:
    def __init__(self) -> None:
        self.APP_NAME = _get_str("APP_NAME", default="workflow-python-service")
        self.APP_HOST = _get_str("PYTHON_SERVICE_HOST", "APP_HOST", default="0.0.0.0")
        self.APP_PORT = _get_int("PYTHON_SERVICE_PORT", "APP_PORT", default=8000)

        self.JAVA_CALLBACK_BASE = _get_str(
            "JAVA_CALLBACK_BASE_URL",
            "JAVA_CALLBACK_BASE",
            default="http://service-java:8080",
        )
        self.WORKFLOW_CALLBACK_SECRET = _get_str(
            "PYTHON_CALLBACK_SECRET",
            "WORKFLOW_CALLBACK_SECRET",
            "JAVA_CALLBACK_SECRET",
            default="",
        )
        self.JAVA_CALLBACK_SECRET = self.WORKFLOW_CALLBACK_SECRET

        self.PY_STORAGE_ROOT = self._normalize_path(
            _get_str("PYTHON_STORAGE_ROOT", "PY_STORAGE_ROOT", default="/opt/workflow-platform/storage/python")
        )
        self.PY_VALIDATION_OUTPUT_ROOT = self._normalize_path(
            _get_str(
                "PYTHON_VALIDATION_OUTPUT_ROOT",
                default=str(Path(self.PY_STORAGE_ROOT) / "results"),
            )
        )
        self.PY_VALIDATION_SAMPLE_ROOT = self._normalize_path(
            _get_str(
                "PYTHON_VALIDATION_SAMPLE_ROOT",
                default=str(Path(self.PY_STORAGE_ROOT) / "result-samples"),
            )
        )
        self.PY_JOB_STORE_FILE = self._normalize_path(
            _get_str(
                "PYTHON_JOB_STORE_FILE",
                default=str(Path(self.PY_STORAGE_ROOT) / "jobs" / "jobs.json"),
            )
        )
        self.PY_LOG_ROOT = self._normalize_path(
            _get_str("PYTHON_LOG_ROOT", default="/opt/workflow-platform/logs/python")
        )
        self.PY_LOG_FILE = self._normalize_path(
            _get_str(
                "PYTHON_LOG_FILE",
                default=str(Path(self.PY_LOG_ROOT) / "service-python.log"),
            )
        )
        self.PY_FILE_LOG_ENABLED = _get_bool("PYTHON_FILE_LOG_ENABLED", default=False)
        self.PY_LOG_LEVEL = _get_str("PYTHON_LOG_LEVEL", "PY_LOG_LEVEL", default="INFO").upper()
        self.PY_JOB_TIMEOUT_SECONDS = _get_int("PY_JOB_TIMEOUT_SECONDS", default=7200)

        # Protocol v1 is additive and remains disabled until the control plane
        # starts sending weights-only packages in a later stage.
        self.WEIGHTS_PROTOCOL_V1_ENABLED = _get_bool(
            "WEIGHTS_PROTOCOL_V1_ENABLED",
            default=False,
        )
        self.WEIGHTS_FEDAVG_V1_ENABLED = _get_bool(
            "WEIGHTS_FEDAVG_V1_ENABLED",
            default=False,
        )
        self.WEIGHTS_VALIDATION_V1_ENABLED = _get_bool(
            "WEIGHTS_VALIDATION_V1_ENABLED",
            default=False,
        )
        self.WEIGHTS_PROTOCOL_ALLOWED_ROOTS = _get_str(
            "WEIGHTS_PROTOCOL_ALLOWED_ROOTS",
            default=str(Path(self.PY_STORAGE_ROOT).parent),
        )
        self.WEIGHTS_PROTOCOL_MAX_FILE_BYTES = _get_int(
            "WEIGHTS_PROTOCOL_MAX_FILE_BYTES",
            default=500 * 1024 * 1024,
        )
        self.WEIGHTS_PROTOCOL_MAX_TENSOR_COUNT = _get_int(
            "WEIGHTS_PROTOCOL_MAX_TENSOR_COUNT",
            default=10_000,
        )
        self.WEIGHTS_PROTOCOL_MAX_TOTAL_ELEMENTS = _get_int(
            "WEIGHTS_PROTOCOL_MAX_TOTAL_ELEMENTS",
            default=250_000_000,
        )
        self.WEIGHTS_PROTOCOL_MAX_SINGLE_TENSOR_ELEMENTS = _get_int(
            "WEIGHTS_PROTOCOL_MAX_SINGLE_TENSOR_ELEMENTS",
            default=100_000_000,
        )
        self.WEIGHTS_PROTOCOL_MAX_TENSOR_DIMENSIONS = _get_int(
            "WEIGHTS_PROTOCOL_MAX_TENSOR_DIMENSIONS",
            default=8,
        )
        self.WEIGHTS_PROTOCOL_MAX_KEY_LENGTH = _get_int(
            "WEIGHTS_PROTOCOL_MAX_KEY_LENGTH",
            default=512,
        )
        shared_storage_root = Path(self.PY_STORAGE_ROOT).parent
        self.WEIGHTS_FEDAVG_INPUT_ROOT = self._normalize_path(
            _get_str(
                "WORKFLOW_WEIGHTS_ASSET_ROOT",
                "WEIGHTS_FEDAVG_INPUT_ROOT",
                default=str(shared_storage_root / "weights-assets"),
            )
        )
        self.WEIGHTS_FEDAVG_OUTPUT_ROOT = self._normalize_path(
            _get_str(
                "WORKFLOW_FEDERATED_MODEL_ROOT",
                "WEIGHTS_FEDAVG_OUTPUT_ROOT",
                default=str(shared_storage_root / "federated-models"),
            )
        )
        self.MODEL_DEFINITION_REGISTRY_V1_ENABLED = _get_bool(
            "MODEL_DEFINITION_REGISTRY_V1_ENABLED",
            default=False,
        )
        self.MODEL_RUNTIME_PROBE_CONTEXT = _get_str(
            "MODEL_RUNTIME_PROBE_CONTEXT",
            default="",
        ).strip().upper()
        self.YOLO_RUNTIME_BASE_URL = _get_str(
            "YOLO_RUNTIME_BASE_URL",
            default="http://yolo-runtime:8010",
        ).rstrip("/")
        self.YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS = _get_float(
            "YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS",
            default=2.0,
        )
        self.YOLO_RUNTIME_READ_TIMEOUT_SECONDS = _get_float(
            "YOLO_RUNTIME_READ_TIMEOUT_SECONDS",
            default=10.0,
        )
        self.YOLO_RUNTIME_VALIDATION_READ_TIMEOUT_SECONDS = _get_float(
            "YOLO_RUNTIME_VALIDATION_READ_TIMEOUT_SECONDS",
            default=7200.0,
        )
        self.INTERNIMAGE_RUNTIME_V1_ENABLED = _get_bool(
            "INTERNIMAGE_RUNTIME_V1_ENABLED",
            default=False,
        )
        self.INTERNIMAGE_RUNTIME_BASE_URL = _get_str(
            "INTERNIMAGE_RUNTIME_BASE_URL",
            default="http://internimage-runtime:8020",
        ).rstrip("/")
        self.INTERNIMAGE_RUNTIME_CONNECT_TIMEOUT_SECONDS = _get_float(
            "INTERNIMAGE_RUNTIME_CONNECT_TIMEOUT_SECONDS",
            default=2.0,
        )
        self.INTERNIMAGE_RUNTIME_READ_TIMEOUT_SECONDS = _get_float(
            "INTERNIMAGE_RUNTIME_READ_TIMEOUT_SECONDS",
            default=60.0,
        )

        self.YOLO_CONFIDENCE_THRESHOLD = _get_float("YOLO_CONFIDENCE_THRESHOLD", default=0.25)
        self.YOLO_MAX_IMAGES = _get_int("YOLO_MAX_IMAGES", default=1000)

    def _normalize_path(self, value: str) -> str:
        return str(Path(value).expanduser().resolve())

    def storage_root_path(self) -> Path:
        return Path(self.PY_STORAGE_ROOT)

    def validation_output_root_path(self) -> Path:
        return Path(self.PY_VALIDATION_OUTPUT_ROOT)

    def validation_sample_root_path(self) -> Path:
        return Path(self.PY_VALIDATION_SAMPLE_ROOT)

    def job_store_file_path(self) -> Path:
        return Path(self.PY_JOB_STORE_FILE)

    def log_file_path(self) -> Path:
        return Path(self.PY_LOG_FILE)

    def weights_protocol_allowed_roots(self) -> list[Path]:
        value = self.WEIGHTS_PROTOCOL_ALLOWED_ROOTS.replace(",", os.pathsep)
        return [
            Path(item.strip()).expanduser().resolve()
            for item in value.split(os.pathsep)
            if item.strip()
        ]

    def validation_sample_dir(self, job_id: str) -> Path:
        return self.validation_sample_root_path() / job_id

    def ensure_directories(self) -> None:
        directories = [
            self.storage_root_path(),
            self.validation_output_root_path(),
            self.validation_sample_root_path(),
            self.job_store_file_path().parent,
        ]
        if self.PY_FILE_LOG_ENABLED:
            directories.append(self.log_file_path().parent)
        for path in directories:
            path.mkdir(parents=True, exist_ok=True)

    def masked_callback_secret(self) -> str:
        if not self.WORKFLOW_CALLBACK_SECRET:
            return "(not-set)"
        if len(self.WORKFLOW_CALLBACK_SECRET) <= 4:
            return "****"
        return (
            self.WORKFLOW_CALLBACK_SECRET[:2]
            + "****"
            + self.WORKFLOW_CALLBACK_SECRET[-2:]
        )

    def log_summary(self, logger) -> None:
        logger.info(
            "python startup config: appName=%s, host=%s, port=%s, storageRoot=%s, validationOutputRoot=%s, validationSampleRoot=%s, jobStoreFile=%s, fileLogEnabled=%s, javaCallbackBase=%s, callbackSecret=%s",
            self.APP_NAME,
            self.APP_HOST,
            self.APP_PORT,
            self.PY_STORAGE_ROOT,
            self.PY_VALIDATION_OUTPUT_ROOT,
            self.PY_VALIDATION_SAMPLE_ROOT,
            self.PY_JOB_STORE_FILE,
            self.PY_FILE_LOG_ENABLED,
            self.JAVA_CALLBACK_BASE,
            self.masked_callback_secret(),
        )


settings = Settings()
