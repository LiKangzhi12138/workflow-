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
    ultralyticsAvailable: bool
    ultralyticsVersion: str | None = None
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
    missingKeyCount: int = 0
    unexpectedKeyCount: int = 0
    shapeMismatchCount: int = 0
    dtypeMismatchCount: int = 0


class GlobalWeightsInput(StrictModel):
    assetId: int
    weightsPath: str
    expectedSha256: str
    expectedTensorCount: int = Field(ge=1)
    expectedElementCount: int = Field(ge=1)
    expectedStateDictKeyHash: str
    expectedShapeSignature: str
    expectedDtypeSignature: str


class WeightsValidationRequest(StrictModel):
    workflowId: int
    jobId: str
    modelDefinition: dict[str, Any]
    runtimeProfileId: str
    globalWeights: GlobalWeightsInput
    datasetPath: str
    sampleOutputDirectory: str
    algorithmType: str


class WeightsValidationResult(StrictModel):
    passed: bool
    reasonCode: str
    message: str
    runtimeProfileId: str
    modelDefinitionId: str | int
    modelDefinitionCode: str
    definitionSha256: str
    architectureSignature: str
    weightsSha256: str
    reconstruction: dict[str, Any]
    metrics: dict[str, Any]
    validationResult: dict[str, Any]
    validationEvidence: dict[str, Any]
