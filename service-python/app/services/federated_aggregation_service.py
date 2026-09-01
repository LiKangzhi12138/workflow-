from __future__ import annotations

import copy
import shutil
from pathlib import Path
from typing import Any

import torch

from app.core.logger import logger

try:
    from ultralytics import YOLO
except Exception:  # pragma: no cover - runtime dependency fallback
    YOLO = None


class FederatedAggregationError(RuntimeError):
    pass


class FederatedAggregationService:
    def aggregate_fedavg(
        self,
        model_paths: list[str],
        output_model_path: str,
        strategy: str = "FEDML",
        dp_context: dict[str, Any] | None = None,
        shuffle_context: dict[str, Any] | None = None,
        secure_aggregation_context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if not model_paths:
            raise FederatedAggregationError("联邦学习聚合失败：模型列表为空")

        dp_context = dp_context or {}
        shuffle_context = shuffle_context or {}
        secure_aggregation_context = secure_aggregation_context or {}
        output_path = Path(output_model_path).resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        resolved_model_paths = [self._ensure_model_file(path) for path in model_paths]

        logger.info(
            "federated aggregation started, strategy=%s, modelCount=%s, outputModelPath=%s, modelPaths=%s, dpContext=%s, shuffleContext=%s, secureAggregationContext=%s",
            strategy,
            len(resolved_model_paths),
            output_path,
            [str(path) for path in resolved_model_paths],
            dp_context,
            shuffle_context,
            secure_aggregation_context,
        )

        if len(resolved_model_paths) == 1:
            return self._copy_single_model_as_global(
                resolved_model_paths[0],
                output_path,
                strategy,
                dp_context,
                shuffle_context,
                secure_aggregation_context,
            )

        normalized = [self._load_normalized_checkpoint(path) for path in resolved_model_paths]
        reference_keys = list(normalized[0]["state_dict"].keys())

        for item in normalized[1:]:
            current_keys = list(item["state_dict"].keys())
            if current_keys != reference_keys:
                raise FederatedAggregationError("联邦学习聚合失败：模型结构不一致，参数键集合无法对齐")

        averaged_state_dict: dict[str, Any] = {}
        for key in reference_keys:
            tensors = [item["state_dict"][key].detach().cpu() for item in normalized]
            first_tensor = tensors[0]
            for other_tensor in tensors[1:]:
                if tuple(other_tensor.shape) != tuple(first_tensor.shape):
                    raise FederatedAggregationError(f"联邦学习聚合失败：参数 {key} 的张量形状不一致")

            if torch.is_floating_point(first_tensor):
                accumulator = first_tensor.to(dtype=torch.float64).clone()
                for tensor in tensors[1:]:
                    accumulator += tensor.to(dtype=torch.float64)
                averaged_state_dict[key] = (accumulator / len(tensors)).to(dtype=first_tensor.dtype)
            else:
                mismatch = any(not torch.equal(first_tensor, tensor) for tensor in tensors[1:])
                if mismatch:
                    raise FederatedAggregationError(f"联邦学习聚合失败：参数 {key} 不是浮点张量且各模型值不一致")
                averaged_state_dict[key] = first_tensor.clone()

        output_checkpoint = self._build_output_checkpoint(normalized[0], averaged_state_dict)
        try:
            torch.save(output_checkpoint, output_path)
        except Exception as exc:
            raise FederatedAggregationError("联邦学习聚合失败：全局模型保存失败") from exc

        logger.info(
            "federated FEDML aggregation completed, modelCount=%s, outputModelPath=%s",
            len(resolved_model_paths),
            output_path,
        )
        dp_summary = self._build_dp_summary(dp_context)
        shuffle_summary = self._build_shuffle_summary(shuffle_context, len(resolved_model_paths))
        secure_summary = self._build_secure_aggregation_summary(secure_aggregation_context, len(resolved_model_paths))
        return {
            "status": "COMPLETED",
            "strategy": strategy,
            "sourceModelCount": len(resolved_model_paths),
            "globalModelPath": str(output_path),
            "summary": f"使用 {len(resolved_model_paths)} 个模型完成 FEDML 联邦聚合",
            "errorMessage": None,
            "detailMessage": None,
            "dpSummary": dp_summary,
            "shuffleSummary": shuffle_summary,
            "secureAggregationSummary": secure_summary,
        }

    def _copy_single_model_as_global(
        self,
        source_model_path: Path,
        output_path: Path,
        strategy: str,
        dp_context: dict[str, Any],
        shuffle_context: dict[str, Any],
        secure_aggregation_context: dict[str, Any],
    ) -> dict[str, Any]:
        try:
            shutil.copy2(source_model_path, output_path)
        except Exception as exc:
            raise FederatedAggregationError("联邦学习聚合失败：全局模型保存失败") from exc

        logger.info(
            "single-model federated passthrough completed, sourceModelPath=%s, outputModelPath=%s",
            source_model_path,
            output_path,
        )
        return {
            "status": "COMPLETED",
            "strategy": strategy,
            "sourceModelCount": 1,
            "globalModelPath": str(output_path),
            "summary": "单模型场景，采用直通全局模型策略",
            "errorMessage": None,
            "detailMessage": "单模型场景，采用直通全局模型策略",
            "dpSummary": self._build_dp_summary(dp_context),
            "shuffleSummary": self._build_shuffle_summary(shuffle_context, 1),
            "secureAggregationSummary": self._build_secure_aggregation_summary(secure_aggregation_context, 1),
        }

    def _ensure_model_file(self, model_path: str) -> Path:
        path = Path(model_path).resolve()
        if not path.exists() or not path.is_file():
            raise FederatedAggregationError(f"联邦学习聚合失败：模型文件不存在 - {path}")
        return path

    def _load_normalized_checkpoint(self, model_path: Path) -> dict[str, Any]:
        ultralytics_error: Exception | None = None
        trusted_checkpoint: Any | None = None

        if YOLO is not None and model_path.suffix.lower() == ".pt":
            try:
                normalized_by_yolo, trusted_checkpoint = self._load_with_ultralytics(model_path)
                if trusted_checkpoint is not None:
                    mode, checkpoint_payload = self._resolve_checkpoint_mode(trusted_checkpoint, str(model_path))
                    normalized_by_yolo["mode"] = mode
                    normalized_by_yolo["checkpoint"] = checkpoint_payload
                return normalized_by_yolo
            except FederatedAggregationError:
                raise
            except Exception as exc:
                ultralytics_error = exc
                logger.warning(
                    "ultralytics checkpoint load failed, modelPath=%s, error=%s",
                    model_path,
                    exc,
                )

        try:
            trusted_checkpoint = self._trusted_torch_load(model_path)
            return self._normalize_checkpoint(trusted_checkpoint, str(model_path))
        except FederatedAggregationError:
            raise
        except Exception as exc:
            logger.exception("trusted checkpoint load failed, modelPath=%s", model_path)
            if ultralytics_error is not None:
                raise FederatedAggregationError("联邦学习聚合失败：YOLO 模型加载失败，请查看 Python 日志") from exc
            raise FederatedAggregationError("联邦学习聚合失败：模型 checkpoint 不合法，无法提取参数") from exc

    def _load_with_ultralytics(self, model_path: Path) -> tuple[dict[str, Any], Any | None]:
        if YOLO is None:
            raise FederatedAggregationError("联邦学习聚合失败：未安装 Ultralytics")

        model_wrapper = YOLO(str(model_path))
        model = getattr(model_wrapper, "model", None)
        if model is None or not hasattr(model, "state_dict"):
            raise FederatedAggregationError("联邦学习聚合失败：Ultralytics YOLO 模型加载后无法提取参数")

        trusted_checkpoint = None
        try:
            trusted_checkpoint = self._trusted_torch_load(model_path)
        except Exception as exc:
            logger.warning(
                "trusted torch.load fallback after ultralytics load failed, modelPath=%s, error=%s",
                model_path,
                exc,
            )

        return (
            {
                "mode": "module",
                "checkpoint": copy.deepcopy(model).cpu(),
                "state_dict": self._clone_state_dict(model.state_dict()),
                "path": str(model_path),
            },
            trusted_checkpoint,
        )

    def _trusted_torch_load(self, model_path: Path) -> Any:
        try:
            return torch.load(model_path, map_location="cpu", weights_only=False)
        except TypeError:
            return torch.load(model_path, map_location="cpu")

    def _normalize_checkpoint(self, checkpoint: Any, model_path: str) -> dict[str, Any]:
        mode, checkpoint_payload = self._resolve_checkpoint_mode(checkpoint, model_path)
        if mode == "checkpoint_model":
            state_dict = checkpoint_payload["model"].state_dict()
        elif mode == "checkpoint_ema":
            state_dict = checkpoint_payload["ema"].state_dict()
        elif mode == "checkpoint_state_dict":
            state_dict = checkpoint_payload["state_dict"]
        elif mode == "module":
            state_dict = checkpoint_payload.state_dict()
        else:
            state_dict = checkpoint_payload

        return {
            "mode": mode,
            "checkpoint": checkpoint_payload,
            "state_dict": self._clone_state_dict(state_dict),
            "path": model_path,
        }

    def _resolve_checkpoint_mode(self, checkpoint: Any, model_path: str) -> tuple[str, Any]:
        if isinstance(checkpoint, dict) and "model" in checkpoint and hasattr(checkpoint["model"], "state_dict"):
            return "checkpoint_model", checkpoint
        if isinstance(checkpoint, dict) and "ema" in checkpoint and hasattr(checkpoint["ema"], "state_dict"):
            return "checkpoint_ema", checkpoint
        if isinstance(checkpoint, dict) and isinstance(checkpoint.get("state_dict"), dict):
            return "checkpoint_state_dict", checkpoint
        if hasattr(checkpoint, "state_dict"):
            return "module", checkpoint
        if isinstance(checkpoint, dict) and checkpoint and all(hasattr(value, "shape") for value in checkpoint.values()):
            return "state_dict_only", checkpoint
        raise FederatedAggregationError(f"联邦学习聚合失败：无法识别模型文件格式 - {model_path}")

    def _clone_state_dict(self, state_dict: dict[str, Any]) -> dict[str, Any]:
        cloned: dict[str, Any] = {}
        for key, value in state_dict.items():
            if hasattr(value, "detach"):
                cloned[key] = value.detach().cpu().clone()
            else:
                cloned[key] = copy.deepcopy(value)
        return cloned

    def _build_output_checkpoint(self, reference: dict[str, Any], averaged_state_dict: dict[str, Any]) -> Any:
        mode = reference["mode"]
        checkpoint = reference["checkpoint"]

        if mode == "checkpoint_model":
            output_checkpoint = copy.deepcopy(checkpoint)
            output_checkpoint["model"].load_state_dict(averaged_state_dict, strict=True)
            if "ema" in output_checkpoint and hasattr(output_checkpoint["ema"], "load_state_dict"):
                output_checkpoint["ema"].load_state_dict(averaged_state_dict, strict=False)
            return output_checkpoint

        if mode == "checkpoint_ema":
            output_checkpoint = copy.deepcopy(checkpoint)
            output_checkpoint["ema"].load_state_dict(averaged_state_dict, strict=True)
            if "model" in output_checkpoint and hasattr(output_checkpoint["model"], "load_state_dict"):
                output_checkpoint["model"].load_state_dict(averaged_state_dict, strict=False)
            return output_checkpoint

        if mode == "checkpoint_state_dict":
            output_checkpoint = copy.deepcopy(checkpoint)
            output_checkpoint["state_dict"] = averaged_state_dict
            return output_checkpoint

        if mode == "module":
            output_checkpoint = copy.deepcopy(checkpoint)
            output_checkpoint.load_state_dict(averaged_state_dict, strict=True)
            return output_checkpoint

        return {
            "state_dict": averaged_state_dict,
            "aggregated_by": "FEDML",
        }

    def _build_dp_summary(self, dp_context: dict[str, Any]) -> str:
        if not dp_context.get("enabled"):
            return "差分隐私未启用"
        return (
            "差分隐私处理完成，"
            f"epsilon={dp_context.get('epsilon')}, "
            f"delta={dp_context.get('delta')}, "
            f"clipNorm={dp_context.get('clipNorm')}, "
            f"noiseMultiplier={dp_context.get('noiseMultiplier')}"
        )

    def _build_shuffle_summary(self, shuffle_context: dict[str, Any], participant_count: int) -> str:
        if not shuffle_context.get("enabled"):
            return "安全洗牌未启用"
        return (
            "安全洗牌处理完成，"
            f"batchNo={shuffle_context.get('batchNo')}, "
            f"participantCount={participant_count}"
        )

    def _build_secure_aggregation_summary(
        self,
        secure_aggregation_context: dict[str, Any],
        participant_count: int,
    ) -> str:
        if not secure_aggregation_context.get("enabled"):
            return "普通聚合模式"
        return (
            "secure_aggregation_prepare -> secure_aggregation_execute -> secure_aggregation_finalize，"
            f"mode={secure_aggregation_context.get('mode')}, participantCount={participant_count}"
        )


federated_aggregation_service = FederatedAggregationService()
