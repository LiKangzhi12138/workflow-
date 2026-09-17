FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-devel AS dcnv3-builder

ARG PIP_INDEX_URL=https://pypi.org/simple
ARG TORCH_CUDA_ARCH_LIST=12.0
ARG MAX_JOBS=4

ENV PIP_NO_CACHE_DIR=1 \
    FORCE_CUDA=1 \
    TORCH_CUDA_ARCH_LIST=${TORCH_CUDA_ARCH_LIST} \
    MAX_JOBS=${MAX_JOBS}

WORKDIR /build

COPY service-internimage-runtime/ops_dcnv3 /build/ops_dcnv3
COPY service-internimage-runtime/build_support /build/build_support

RUN python -c "import torch,torchvision; assert torch.__version__.split('+')[0]=='2.7.1'; assert torchvision.__version__.split('+')[0]=='0.22.1'; assert torch.version.cuda=='12.8'" \
    && python -m pip download --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps --no-binary=:all: mmcv-full==1.6.2 -d /build/mmcv-download \
    && python -m pip download --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps --no-binary=:all: --no-build-isolation timm==0.6.11 -d /build/timm-download

# Patch and audit in a separate fail-closed layer before native compilation.
RUN python /build/build_support/prepare_mmcv_source.py \
    /build/mmcv-download/mmcv-full-1.6.2.tar.gz \
    /build/mmcv-source

# Patch and audit timm 0.6.11 for Python 3.11 before building its wheel.
RUN python /build/build_support/prepare_timm_source.py \
    /build/timm-download/timm-0.6.11.tar.gz \
    /build/timm-source

RUN MMCV_WITH_OPS=1 FORCE_CUDA=1 python -m pip wheel --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps --no-build-isolation /build/mmcv-source/mmcv-full-1.6.2 -w /build/wheels \
    && python -m pip wheel --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps --no-build-isolation /build/ops_dcnv3 -w /build/wheels \
    && python -m pip wheel --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps --no-build-isolation /build/timm-source/timm-0.6.11 -w /build/wheels


FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime

ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HOME=/home/runtime \
    INTERNIMAGE_RUNTIME_HOST=0.0.0.0 \
    INTERNIMAGE_RUNTIME_PORT=8020 \
    INTERNIMAGE_RUNTIME_PROFILE_ID=INTERNIMAGE_RUNTIME_V1

RUN apt-get update \
    && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY service-internimage-runtime/requirements.txt /app/requirements.txt
COPY --from=dcnv3-builder /build/wheels /tmp/dcnv3-wheels

# The base image owns CUDA torch/torchvision. The pinned DCNv3 wheel is built
# against that same pair and installed without dependency resolution.
RUN python -c "import torch,torchvision; assert torch.__version__.split('+')[0]=='2.7.1'; assert torchvision.__version__.split('+')[0]=='0.22.1'; assert torch.version.cuda=='12.8'" \
    && grep -Ev '^(mmcv-full|mmsegmentation|timm)==' /app/requirements.txt > /tmp/runtime-requirements.txt \
    && python -m pip install --index-url "${PIP_INDEX_URL}" --no-cache-dir -r /tmp/runtime-requirements.txt \
    && python -m pip install --no-cache-dir --no-deps /tmp/dcnv3-wheels/mmcv_full-1.6.2-*.whl \
    && python -m pip install --index-url "${PIP_INDEX_URL}" --no-cache-dir --no-deps mmsegmentation==0.27.0 \
    && set -- /tmp/dcnv3-wheels/timm-0.6.11-*.whl \
    && [ "$#" -eq 1 ] && [ -f "$1" ] \
    && python -m pip install --no-cache-dir --no-deps "$1" \
    && python -c "import timm,timm.models; from timm.models.maxxvit import MaxxVitCfg; a=MaxxVitCfg(); b=MaxxVitCfg(); assert timm.__version__=='0.6.11'; assert a.conv_cfg is not b.conv_cfg; assert a.transformer_cfg is not b.transformer_cfg" \
    && set -- /tmp/dcnv3-wheels/dcnv3-*.whl \
    && [ "$#" -eq 1 ] && [ -f "$1" ] \
    && python -m pip install --no-cache-dir --no-deps "$1" \
    && python -c "import torch,torchvision,mmcv,mmseg,timm,DCNv3; assert torch.__version__.split('+')[0]=='2.7.1'; assert torchvision.__version__.split('+')[0]=='0.22.1'; assert torch.version.cuda=='12.8'; assert mmcv.__version__=='1.6.2'; assert mmseg.__version__=='0.27.0'; assert timm.__version__=='0.6.11'" \
    && rm -rf /tmp/dcnv3-wheels

COPY service-internimage-runtime/app /app/app
COPY service-internimage-runtime/certify_runtime.py /app/certify_runtime.py
COPY service-internimage-runtime/runtime_vendor /app/runtime_vendor
COPY service-internimage-runtime/ops_dcnv3/functions /app/ops_dcnv3/functions
COPY service-internimage-runtime/ops_dcnv3/modules /app/ops_dcnv3/modules
COPY service-internimage-runtime/ops_dcnv3/__init__.py /app/ops_dcnv3/__init__.py

RUN useradd --create-home --uid 10002 runtime \
    && chown -R runtime:runtime /app /home/runtime

USER runtime

EXPOSE 8020

HEALTHCHECK --interval=30s --timeout=15s --retries=5 --start-period=180s \
    CMD python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('http://127.0.0.1:8020/internal/runtime/health',timeout=10)); raise SystemExit(0 if d.get('runtimeReady') is True else 1)"

CMD ["sh", "-c", "uvicorn app.main:app --host ${INTERNIMAGE_RUNTIME_HOST:-0.0.0.0} --port ${INTERNIMAGE_RUNTIME_PORT:-8020}"]
