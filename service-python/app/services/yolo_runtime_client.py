from __future__ import annotations

import requests

from app.core.config import settings
from app.schemas.model_protocol import (
    ModelAvailabilityResult,
    ModelDefinition,
    RuntimeHealthResponse,
    RuntimeWeightsCompatibilityRequest,
    RuntimeWeightsCompatibilityResult,
    RuntimeWeightsValidationRequest,
    RuntimeWeightsValidationResult,
)
from app.services.runtime_client_error import RuntimeClientError


class YoloRuntimeClientError(RuntimeClientError):
    pass


class YoloRuntimeClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        connect_timeout: float | None = None,
        read_timeout: float | None = None,
        session=None,
    ) -> None:
        self.base_url = (base_url or settings.YOLO_RUNTIME_BASE_URL).rstrip("/")
        self.timeout = (
            connect_timeout or settings.YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS,
            read_timeout or settings.YOLO_RUNTIME_READ_TIMEOUT_SECONDS,
        )
        self.session = session or requests

    def health(self) -> RuntimeHealthResponse:
        return self._request(
            "GET",
            "/internal/runtime/health",
            RuntimeHealthResponse,
        )

    def check_definition(
        self,
        definition: ModelDefinition,
        runtime_profile_id: str,
    ) -> ModelAvailabilityResult:
        return self._request(
            "POST",
            "/internal/model-definitions/check",
            ModelAvailabilityResult,
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

    def validate_weights(
        self,
        request: RuntimeWeightsValidationRequest,
    ) -> RuntimeWeightsValidationResult:
        return self._request(
            "POST",
            "/internal/runtime/validate-weights-v1",
            RuntimeWeightsValidationResult,
            timeout=(
                settings.YOLO_RUNTIME_CONNECT_TIMEOUT_SECONDS,
                settings.YOLO_RUNTIME_VALIDATION_READ_TIMEOUT_SECONDS,
            ),
            json=request.model_dump(mode="json"),
        )

    def _request(self, method: str, path: str, response_type, **kwargs):
        try:
            timeout = kwargs.pop("timeout", self.timeout)
            response = self.session.request(
                method,
                self.base_url + path,
                timeout=timeout,
                **kwargs,
            )
            response.raise_for_status()
            return response_type.model_validate(response.json())
        except Exception as exc:
            raise YoloRuntimeClientError(
                f"YOLO Runtime request failed: {type(exc).__name__}"
            ) from exc


yolo_runtime_client = YoloRuntimeClient()
