from __future__ import annotations

import copy
import importlib
from typing import Any


TEMPLATE_ID = "INTERNIMAGE_T_UPERNET_WHEAT_V1"
INTERNIMAGE_SOURCE_COMMIT = "31c962dc6c1ceb23e580772f7daaa6944694fbe6"
EXPECTED_TENSOR_COUNT = 728
EXPECTED_ELEMENT_COUNT = 58_963_958


MODEL_CONFIG: dict[str, Any] = {
    "type": "EncoderDecoder",
    "pretrained": None,
    "backbone": {
        "type": "InternImage",
        "core_op": "DCNv3",
        "channels": 64,
        "depths": [4, 4, 18, 4],
        "groups": [4, 8, 16, 32],
        "mlp_ratio": 4.0,
        "drop_path_rate": 0.2,
        "norm_layer": "LN",
        "layer_scale": 1.0,
        "offset_scale": 1.0,
        "post_norm": False,
        "with_cp": True,
        "out_indices": [0, 1, 2, 3],
        "init_cfg": None,
    },
    "decode_head": {
        "type": "UPerHead",
        "in_channels": [64, 128, 256, 512],
        "in_index": [0, 1, 2, 3],
        "pool_scales": [1, 2, 3, 6],
        "channels": 512,
        "dropout_ratio": 0.1,
        "num_classes": 3,
        "norm_cfg": {"type": "GN", "num_groups": 32, "requires_grad": True},
        "align_corners": False,
        "ignore_index": 255,
        "loss_decode": {
            "type": "CrossEntropyLoss",
            "use_sigmoid": False,
            "loss_weight": 1.0,
            "avg_non_ignore": True,
        },
    },
    "auxiliary_head": {
        "type": "FCNHead",
        "in_channels": 256,
        "in_index": 2,
        "channels": 256,
        "num_convs": 1,
        "concat_input": False,
        "dropout_ratio": 0.1,
        "num_classes": 3,
        "norm_cfg": {"type": "GN", "num_groups": 32, "requires_grad": True},
        "align_corners": False,
        "ignore_index": 255,
        "loss_decode": {
            "type": "CrossEntropyLoss",
            "use_sigmoid": False,
            "loss_weight": 0.4,
            "avg_non_ignore": True,
        },
    },
    "train_cfg": {},
    "test_cfg": {"mode": "whole"},
}


TRUSTED_ARCHITECTURE: dict[str, Any] = {
    "templateId": TEMPLATE_ID,
    "modelConfig": MODEL_CONFIG,
    "customOps": ["DCNv3"],
    "stateContract": {
        "tensorCount": EXPECTED_TENSOR_COUNT,
        "elementCount": EXPECTED_ELEMENT_COUNT,
        "dtype": "float32",
    },
    "source": {
        "repository": "OpenGVLab/InternImage",
        "commit": INTERNIMAGE_SOURCE_COMMIT,
        "decoder": "UPerNet",
    },
    "pretrainedPolicy": "FORBIDDEN",
}


def trusted_architecture() -> dict[str, Any]:
    return copy.deepcopy(TRUSTED_ARCHITECTURE)


def build_model():
    # Importing the pinned runtime source registers the trusted backbone only.
    importlib.import_module("runtime_vendor.intern_image")
    builder = importlib.import_module("mmseg.models.builder")
    model = builder.build_segmentor(copy.deepcopy(MODEL_CONFIG))
    return model
