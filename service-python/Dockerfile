FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libglib2.0-0 libgl1 libgomp1 libsm6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY service-python/requirements.txt /app/requirements.txt

RUN pip install --upgrade pip \
    && pip install --extra-index-url https://download.pytorch.org/whl/cpu -r /app/requirements.txt \
    && python -c "import cv2, PIL, torch, ultralytics; print('python-deps-ok')"

COPY service-python/app /app/app

EXPOSE 8000

CMD ["sh", "-c", "uvicorn app.main:app --host ${PYTHON_SERVICE_HOST:-0.0.0.0} --port ${PYTHON_SERVICE_PORT:-8000}"]
