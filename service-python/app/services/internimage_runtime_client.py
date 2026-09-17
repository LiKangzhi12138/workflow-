from __future__ import annotations

import requests
from pydantic import ValidationError

from app.core.config import settings
from app.core.logger import logger
from app.schemas.model_protocol import (
    InternImageModelAvailabilityResult,
    ModelDefinition,
    RuntimeHealthResponse,
    RuntimeWeightsCompatibilityRequest,
    RuntimeWeightsCompatibilityResult,
)
from app.services.runtime_client_error import RuntimeClientError


class InternImageRuntimeClientError(RuntimeClientError):
    pass


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

    def _request(self, method: str, path: str, response_type, **kwargs):
        try:
            response = self.session.request(
                method,
                self.base_url + path,
                timeout=self.timeout,
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
            raise InternImageRuntimeClientError(
                "InternImage Runtime response validation failed."
            ) from exc
        except Exception as exc:
            raise InternImageRuntimeClientError(
                f"InternImage Runtime request failed: {type(exc).__name__}"
            ) from exc


internimage_runtime_client = InternImageRuntimeClient()
