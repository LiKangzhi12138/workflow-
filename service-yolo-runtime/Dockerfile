FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime

ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HOME=/home/runtime \
    XDG_CONFIG_HOME=/home/runtime/.config \
    YOLO_RUNTIME_HOST=0.0.0.0 \
    YOLO_RUNTIME_PORT=8010 \
    YOLO_RUNTIME_PROFILE_ID=YOLO_RUNTIME_V1

RUN apt-get update \
    && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY service-yolo-runtime/requirements.txt /app/requirements.txt

# The base image owns the CUDA-enabled torch/torchvision pair. Install every
# other dependency first, then install Ultralytics without dependency resolution
# so pip cannot replace the verified GPU framework packages.
RUN python -c "import torch, torchvision; assert torch.__version__.split('+')[0] == '2.7.1'; assert torchvision.__version__.split('+')[0] == '0.22.1'" \
    && grep -v '^ultralytics==' /app/requirements.txt > /tmp/runtime-requirements.txt \
    && python -m pip install --index-url "${PIP_INDEX_URL}" --no-cache-dir -r /tmp/runtime-requirements.txt \
    && python -m pip install --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps ultralytics==8.4.41 \
    && python -c "import torch, torchvision, ultralytics; assert torch.__version__.split('+')[0] == '2.7.1'; assert torchvision.__version__.split('+')[0] == '0.22.1'; assert ultralytics.__version__ == '8.4.41'"

COPY service-yolo-runtime/app /app/app
COPY service-yolo-runtime/certify_runtime.py /app/certify_runtime.py

RUN useradd --create-home --uid 10001 runtime \
    && install -d -m 0750 -o runtime -g runtime /home/runtime/.config/Ultralytics \
    && chown -R runtime:runtime /app /home/runtime

USER runtime

EXPOSE 8010

HEALTHCHECK --interval=30s --timeout=10s --retries=5 --start-period=60s \
    CMD python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('http://127.0.0.1:8010/internal/runtime/health', timeout=5)); raise SystemExit(0 if d.get('runtimeReady') is True else 1)"

CMD ["sh", "-c", "uvicorn app.main:app --host ${YOLO_RUNTIME_HOST:-0.0.0.0} --port ${YOLO_RUNTIME_PORT:-8010}"]
