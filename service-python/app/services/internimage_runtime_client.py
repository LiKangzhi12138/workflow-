from __future__ import annotations

import requests
from requests.exceptions import HTTPError
from pydantic import ValidationError

from app.core.config import settings
from app.core.logger import logger
from app.schemas.model_protocol import (
    InternImageModelAvailabilityResult,
    ModelDefinition,
    RuntimeHealthResponse,
    RuntimeWeightsCompatibilityRequest,
    RuntimeWeightsCompatibilityResult,
    RuntimeWeightsValidationRequest,
    RuntimeWeightsValidationResult,
    RuntimeDatasetCheckRequest,
    RuntimeDatasetCheckResult,
)
from app.services.runtime_client_error import RuntimeClientError


class InternImageRuntimeClientError(RuntimeClientError):
    def __init__(self, reason_code: str, message: str) -> None:
        super().__init__(message)
        self.reason_code = reason_code


SAFE_RUNTIME_ERRORS = {
    "MASK_MISSING": "图片与标签文件名不匹配，或缺少对应的 PNG 标签。",
    "MASK_LABEL_INVALID": "分割标签包含模型定义之外的类别值。",
    "DATASET_INVALID": "分割数据集需要可读取、尺寸一致的 images/ 与 masks/ 文件。",
    "PATH_NOT_ALLOWED": "验证数据集不在受控存储目录内。",
    "GLOBAL_ASSET_INVALID": "全局权重资产无效。",
    "WEIGHTS_SHA_MISMATCH": "全局权重校验值不一致。",
    "WEIGHTS_STATE_MISMATCH": "全局权重结构与可信证据不一致。",
    "WEIGHTS_DTYPE_UNSUPPORTED": "全局权重的数据类型与模型不兼容。",
    "WEIGHTS_NON_FINITE": "全局权重包含非法数值。",
    "STRICT_LOAD_FAILED": "全局权重无法严格加载到可信模型结构。",
    "RUNTIME_UNAVAILABLE": "模型运行环境暂不可用。",
    "RUNTIME_BUSY": "模型运行环境正忙，请稍后重试。",
    "MODEL_REBUILD_FAILED": "可信 InternImage 模型结构重建失败。",
    "WEIGHTS_LOAD_FAILED": "全局权重无法安全读取。",
    "CUDA_SETUP_FAILED": "InternImage GPU 初始化失败。",
    "CUDA_OUT_OF_MEMORY": "GPU 显存不足，无法完成 InternImage 推理。",
    "CUDA_INFERENCE_FAILED": "InternImage GPU 前向推理失败。",
    "MODEL_FORWARD_FAILED": "InternImage 模型前向推理失败。",
    "INPUT_PREPROCESS_FAILED": "验证图像预处理失败。",
    "OUTPUT_DECODE_FAILED": "InternImage 预测结果格式异常。",
    "SAMPLE_RENDER_FAILED": "分割样例图生成失败。",
    "METRICS_FAILED": "真实分割指标计算失败。",
    "INFERENCE_FAILED": "模型推理失败，请查看运行服务日志。",
    "METRIC_FAILED": "分割指标计算失败，请检查标签。",
}


class InternImageRuntimeClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        connect_timeout: float | None = None,
        read_timeout: float | None = None,
        session=None,
    ) -> None:
        self.base_url = (base_url or settings.INTERNIMAGE_RUNTIME_BASE_URL).rstrip("/")
        self.timeout = (
            connect_timeout or settings.INTERNIMAGE_RUNTIME_CONNECT_TIMEOUT_SECONDS,
            read_timeout or settings.INTERNIMAGE_RUNTIME_READ_TIMEOUT_SECONDS,
        )
        self.session = session or requests

    def health(self) -> RuntimeHealthResponse:
        return self._request("GET", "/internal/runtime/health", RuntimeHealthResponse)

    def check_definition(
        self,
        definition: ModelDefinition,
        runtime_profile_id: str,
    ) -> InternImageModelAvailabilityResult:
        return self._request(
            "POST",
            "/internal/model-definitions/check",
            InternImageModelAvailabilityResult,
            json={
                "modelDefinition": definition.model_dump(mode="json"),
                "runtimeProfileId": runtime_profile_id,
            },
        )

    def check_weights_compatibility(
        self,
        request: RuntimeWeightsCompatibilityRequest,
    ) -> RuntimeWeightsCompatibilityResult:
        return self._request(
            "POST",
            "/internal/model-definitions/weights-compatibility",
            RuntimeWeightsCompatibilityResult,
            json=request.model_dump(mode="json"),
        )

    def validate_weights(self, request: RuntimeWeightsValidationRequest) -> RuntimeWeightsValidationResult:
        return self._request(
            "POST", "/internal/runtime/validate-weights-v1", RuntimeWeightsValidationResult,
            json=request.model_dump(mode="json"),
            timeout=(self.timeout[0], settings.INTERNIMAGE_RUNTIME_VALIDATION_READ_TIMEOUT_SECONDS),
        )

    def check_dataset(self, request: RuntimeDatasetCheckRequest) -> RuntimeDatasetCheckResult:
        return self._request(
            "POST", "/internal/runtime/check-dataset-v1", RuntimeDatasetCheckResult,
            json=request.model_dump(mode="json"),
            timeout=(self.timeout[0], settings.INTERNIMAGE_RUNTIME_VALIDATION_READ_TIMEOUT_SECONDS),
        )

    def _request(self, method: str, path: str, response_type, **kwargs):
        try:
            response = self.session.request(
                method,
                self.base_url + path,
                timeout=kwargs.pop("timeout", self.timeout),
                **kwargs,
            )
            response.raise_for_status()
            return response_type.model_validate(response.json())
        except ValidationError as exc:
            errors = [
                {
                    "field": ".".join(str(part) for part in error["loc"]),
                    "type": error["type"],
                    "message": error["msg"],
                }
                for error in exc.errors(include_url=False)
            ]
            logger.warning(
                "InternImage Runtime response validation failed: method=%s, path=%s, schema=%s, errors=%s",
                method,
                path,
                response_type.__name__,
                errors,
            )
            raise InternImageRuntimeClientError("RUNTIME_RESPONSE_INVALID", "模型运行服务响应格式不正确。") from exc
        except HTTPError as exc:
            response = exc.response
            status = response.status_code if response is not None else 0
            detail = None
            if response is not None:
                try:
                    detail = response.json().get("detail")
                except (ValueError, AttributeError):
                    pass
            if status == 422 and isinstance(detail, dict):
                code = detail.get("reasonCode")
                if code in SAFE_RUNTIME_ERRORS:
                    logger.warning("InternImage Runtime rejected request: path=%s reasonCode=%s", path, code)
                    raise InternImageRuntimeClientError(code, SAFE_RUNTIME_ERRORS[code]) from exc
            if status == 422:
                logger.warning("InternImage Runtime request schema rejected: path=%s", path)
                raise InternImageRuntimeClientError("RUNTIME_REQUEST_SCHEMA_INVALID", "模型运行服务请求格式不匹配。") from exc
            raise InternImageRuntimeClientError("RUNTIME_REQUEST_FAILED", "模型运行服务请求失败，请稍后重试。") from exc
        except InternImageRuntimeClientError:
            raise
        except Exception as exc:
            raise InternImageRuntimeClientError("RUNTIME_UNAVAILABLE", "模型运行服务连接失败，请稍后重试。") from exc


internimage_runtime_client = InternImageRuntimeClient()
