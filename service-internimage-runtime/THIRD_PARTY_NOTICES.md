# Third-party source provenance

The files under `runtime_vendor/intern_image.py` and `ops_dcnv3/` are pinned from
OpenGVLab InternImage commit `31c962dc6c1ceb23e580772f7daaa6944694fbe6`, as
captured by the verified InternImage weights-lab handoff. They retain their
upstream copyright headers and MIT license notices.

The DCNv3 CUDA source is compiled inside the image. No host-built `.so`, model
checkpoint, pretrained weights, or weights-lab path is copied into the image.
