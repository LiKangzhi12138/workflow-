import logging

from app.core.config import settings


def _build_logger(current_settings=settings) -> logging.Logger:
    logger = logging.getLogger("workflow-python")
    logger.setLevel(getattr(logging, current_settings.PY_LOG_LEVEL, logging.INFO))
    logger.handlers.clear()

    formatter = logging.Formatter("%(asctime)s | %(levelname)s | %(message)s")

    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    if current_settings.PY_FILE_LOG_ENABLED:
        current_settings.log_file_path().parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(current_settings.PY_LOG_FILE, encoding="utf-8")
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)

    logger.propagate = False
    return logger


logger = _build_logger()
