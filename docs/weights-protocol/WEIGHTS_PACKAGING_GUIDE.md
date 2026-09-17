# Weights Protocol v1 Packaging Guide

All commands in this guide are for **Windows CMD**.

## 1. Responsibility Boundary

The browser does not parse PyTorch and the platform server never performs unsafe
full-checkpoint extraction. The supplied CLI accepts only an already
weights-only Protocol v1 source:

```python
{"state_dict": OrderedDict[str, Tensor]}
```

It loads with `weights_only=True`, validates Tensor-only state, the dtype
allowlist (`float16`, `float32`, `int64`), and finite floating values, then emits
the package metadata.

If a training system currently produces a full checkpoint, export the
weights-only wrapper inside that trusted training environment or its trusted
ModelAdapter. Do not submit unknown checkpoints to an unsafe local converter,
and do not upload a full checkpoint through the V1 endpoint.

## 2. Inputs And Outputs

Inputs:

- an already weights-only `.pt` file;
- a trusted ModelDefinition JSON;
- a new/empty output directory;
- a short creator label.

Outputs:

```text
weights.pt
manifest.json
descriptor.json
```

The tool refuses to overwrite any of these files.

## 3. Package The Golden YOLO Weights

```cmd
cd /d D:\workspace\workflow-platform
python tools\weights-package\package_weights.py --weights D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt --definition service-python\app\model_definitions\YOLOV8N_SHEEP_V1.json --output-dir .runtime\stage2a-manual-package --created-by local-yolo-training
```

Use another new output directory if that path already exists.

Expected summary includes:

```text
passed=true
tensorCount=355
sha256=aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
```

## 4. Verify Files And SHA

```cmd
dir .runtime\stage2a-manual-package
certutil -hashfile .runtime\stage2a-manual-package\weights.pt SHA256
certutil -hashfile D:\workspace\federated-weights-lab\yolo\artifacts\client_a\weights_only.pt SHA256
```

Both weights hashes must be:

```text
aad6f23b3f27db65b159ed37da86996aa1cc5171a99293b3aee61535f40d1f5c
```

Review `manifest.json` and `descriptor.json` without editing the positive-test
copies:

```cmd
type .runtime\stage2a-manual-package\manifest.json
type .runtime\stage2a-manual-package\descriptor.json
```

Expected identity includes `YOLOV8N_SHEEP_V1`, Definition SHA
`302bbb5e00b03c3a14eabf316be8cdc5bbbd5e0de235c28a7a6cb0b491a22cd2`,
and architecture signature
`2510fa3d9b145fe4cc5072bca168770b7989c291729a967fb9b75717cd9db74a`.

## 5. Upload Preparation

For a Definition-based workflow whose upload contract is `WEIGHTS_V1`, select:

```text
Weights:    .runtime\stage2a-manual-package\weights.pt
Manifest:   .runtime\stage2a-manual-package\manifest.json
Descriptor: .runtime\stage2a-manual-package\descriptor.json
```

The frontend computes SHA and uses the existing AES upload flow for weights.
The metadata remains an untrusted declaration and is fully checked against the
workflow-bound server Definition.

## 6. Safety Notes

- Never replace `weights_only=True` with normal pickle loading.
- Never use package metadata to choose the server ModelDefinition.
- Never edit classes/version/variant to make an incompatible model appear valid.
- Never add `model`, `ema`, optimizer, epoch, arguments, or custom objects to the
  top-level wrapper.
- Never place a base checkpoint (`yolov8n.pt`, `best.pt`, pretrained `.pth`) in
  the package.
