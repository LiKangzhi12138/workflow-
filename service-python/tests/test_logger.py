import logging
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.core.config import Settings
from app.core.logger import _build_logger


class LoggerSettings:
    def __init__(self, file_logging_enabled: bool, log_file: Path) -> None:
        self.PY_LOG_LEVEL = "INFO"
        self.PY_FILE_LOG_ENABLED = file_logging_enabled
        self.PY_LOG_FILE = str(log_file)

    def log_file_path(self) -> Path:
        return Path(self.PY_LOG_FILE)


class LoggerConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        logger = logging.getLogger("workflow-python")
        for handler in list(logger.handlers):
            handler.close()
            logger.removeHandler(handler)
        self.temp_dir.cleanup()

    def test_file_logging_disabled_uses_stream_only(self) -> None:
        log_file = Path(self.temp_dir.name) / "service-python.log"
        logger = _build_logger(LoggerSettings(False, log_file))

        self.assertEqual(1, len(logger.handlers))
        self.assertIsInstance(logger.handlers[0], logging.StreamHandler)
        self.assertNotIsInstance(logger.handlers[0], logging.FileHandler)
        self.assertFalse(log_file.exists())

    def test_file_logging_enabled_adds_file_handler(self) -> None:
        log_file = Path(self.temp_dir.name) / "nested" / "service-python.log"
        logger = _build_logger(LoggerSettings(True, log_file))

        self.assertEqual(2, len(logger.handlers))
        self.assertTrue(any(isinstance(handler, logging.FileHandler) for handler in logger.handlers))
        self.assertTrue(log_file.exists())

    def test_disabled_file_logging_does_not_create_log_directory(self) -> None:
        root = Path(self.temp_dir.name)
        log_root = root / "logs"
        environment = {
            "PYTHON_STORAGE_ROOT": str(root / "storage"),
            "PYTHON_VALIDATION_OUTPUT_ROOT": str(root / "storage" / "results"),
            "PYTHON_VALIDATION_SAMPLE_ROOT": str(root / "storage" / "samples"),
            "PYTHON_JOB_STORE_FILE": str(root / "storage" / "jobs" / "jobs.json"),
            "PYTHON_LOG_ROOT": str(log_root),
            "PYTHON_LOG_FILE": str(log_root / "service-python.log"),
            "PYTHON_FILE_LOG_ENABLED": "false",
        }

        with patch.dict(os.environ, environment, clear=True):
            current_settings = Settings()
            current_settings.ensure_directories()

        self.assertFalse(log_root.exists())


if __name__ == "__main__":
    unittest.main()
