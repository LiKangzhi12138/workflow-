from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RuntimeHealthResponse(StrictModel):
    runtimeProfileId: str
    runtimeType: str
    probeContext: str
    pythonVersion: str
    torchAvailable: bool
    torchVersion: str | None = None
    torchvisionAvailable: bool
    torchvisionVersion: str | None = None
    ultralyticsAvailable: bool = False
    ultralyticsVersion: str | None = None
    mmcvAvailable: bool
    mmcvVersion: str | None = None
    mmengineAvailable: bool
    mmengineVersion: str | None = None
    mmsegAvailable: bool
    mmsegVersion: str | None = None
    internimageAvailable: bool
    internimageVersion: str | None = None
    python311CompatibilityPatch: bool
    dcnv3Available: bool
    dcnv3Version: str | None = None
    dcnv3SelfCheckPassed: bool
    minimalForwardCheckPassed: bool
    cudaAvailable: bool
    cudaBuildVersion: str | None = None
    gpuAvailable: bool
    gpuName: str | None = None
    runtimeReady: bool
    errors: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class DefinitionCheckRequest(StrictModel):
    modelDefinition: dict[str, Any]
    runtimeProfileId: str


class ModelAvailabilityResult(StrictModel):
    available: bool
    availabilityState: str
    definitionId: str | int
    definitionCode: str
    modelFamily: str
    variant: str
    taskType: str
    adapterAvailable: bool | None = None
    runtimeAvailable: bool | None = None
    definitionValid: bool | None = None
    definitionEnabled: bool | None = None
    runtimeEvaluated: bool | None = None
    selfCheckPassed: bool | None = None
    probeContext: str | None = None
    detectedPythonVersion: str | None = None
    detectedFrameworkVersion: str | None = None
    detectedTorchVersion: str | None = None
    cudaAvailable: bool | None = None
    gpuName: str | None = None
    reasonCode: str
    message: str
    checkpointUsed: bool = False
    baseCheckpointUsed: bool = False
    pretrainedUsed: bool = False
    constructionMode: str | None = None
    tensorCount: int | None = None
    elementCount: int | None = None


class StateTensorMetadata(StrictModel):
    key: str
    shape: list[int]
    dtype: str


class WeightsCompatibilityRequest(StrictModel):
    modelDefinition: dict[str, Any]
    runtimeProfileId: str
    state: list[StateTensorMetadata]


class WeightsCompatibilityResult(StrictModel):
    compatible: bool
    reasonCode: str
    message: str
    expectedTensorCount: int | None = None
    actualTensorCount: int | None = None
    expectedElementCount: int | None = None
    actualElementCount: int | None = None
    missingKeyCount: int = 0
    unexpectedKeyCount: int = 0
    shapeMismatchCount: int = 0
    dtypeMismatchCount: int = 0
