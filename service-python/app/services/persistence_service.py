import json

from app.core.config import settings


def save_jobs_to_disk(job_store: dict):
    job_file = settings.job_store_file_path()
    job_file.parent.mkdir(parents=True, exist_ok=True)
    with job_file.open("w", encoding="utf-8") as f:
        json.dump(job_store, f, indent=2)


def load_jobs_from_disk():
    job_file = settings.job_store_file_path()
    if not job_file.exists():
        return {}

    with job_file.open("r", encoding="utf-8") as f:
        return json.load(f)
