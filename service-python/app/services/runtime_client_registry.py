from __future__ import annotations

from app.services.internimage_runtime_client import internimage_runtime_client
from app.services.yolo_runtime_client import yolo_runtime_client


class RuntimeClientRegistry:
    def __init__(self, clients: dict[str, object] | None = None) -> None:
        self._clients = clients or {
            "YOLO_RUNTIME_V1": yolo_runtime_client,
            "INTERNIMAGE_RUNTIME_V1": internimage_runtime_client,
        }

    def get(self, runtime_profile_id: str):
        return self._clients.get(runtime_profile_id)


runtime_client_registry = RuntimeClientRegistry()
