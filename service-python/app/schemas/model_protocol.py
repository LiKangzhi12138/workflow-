from __future__ import annotations

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictProtocolModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DefinitionStatus(str, Enum):
    ENABLED = "ENABLED"
    DISABLED = "DISABLED"
    DRAFT = "DRAFT"


class RuntimeStatus(str, Enum):
    ENABLED = "ENABLED"
    DISABLED = "DISABLED"


class AvailabilityReasonCode(str, Enum):
    AVAILABLE = "AVAILABLE"
    DEFINITION_INVALID = "DEFINITION_INVALID"
    ADAPTER_NOT_REGISTERED = "ADAPTER_NOT_REGISTERED"
    RUNTIME_NOT_READY = "RUNTIME_NOT_READY"
    FRAMEWORK_NOT_AVAILABLE = "FRAMEWORK_NOT_AVAILABLE"
    CUDA_NOT_AVAILABLE = "CUDA_NOT_AVAILABLE"
    COMPILED_OP_NOT_AVAILABLE = "COMPILED_OP_NOT_AVAILABLE"
    DEFINITION_SHA_MISMATCH = "DEFINITION_SHA_MISMATCH"
    ARCHITECTURE_SIGNATURE_MISMATCH = "ARCHITECTURE_SIGNATURE_MISMATCH"
    CLASS_ORDER_MISMATCH = "CLASS_ORDER_MISMATCH"
    RUNTIME_PROFILE_NOT_FOUND = "RUNTIME_PROFILE_NOT_FOUND"
    FRAMEWORK_VERSION_MISMATCH = "FRAMEWORK_VERSION_MISMATCH"
    PYTHON_VERSION_MISMATCH = "PYTHON_VERSION_MISMATCH"
    SELF_CHECK_FAILED = "SELF_CHECK_FAILED"
    RUNTIME_CHECK_FAILED = "RUNTIME_CHECK_FAILED"
    DISABLED = "DISABLED"
    NOT_EVALUATED = "NOT_EVALUATED"


class ProtocolErrorCode(str, Enum):
    INVALID_SCHEMA = "INVALID_SCHEMA"
    UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
    INVALID_ARTIFACT_TYPE = "INVALID_ARTIFACT_TYPE"
    WEIGHTS_FILE_NOT_FOUND = "WEIGHTS_FILE_NOT_FOUND"
    WEIGHTS_SIZE_MISMATCH = "WEIGHTS_SIZE_MISMATCH"
    WEIGHTS_SHA256_MISMATCH = "WEIGHTS_SHA256_MISMATCH"
    INVALID_TOP_LEVEL = "INVALID_TOP_LEVEL"
    INVALID_STATE_DICT = "INVALID_STATE_DICT"
    NON_TENSOR_VALUE = "NON_TENSOR_VALUE"
    KEY_COUNT_MISMATCH = "KEY_COUNT_MISMATCH"
    KEY_HASH_MISMATCH = "KEY_HASH_MISMATCH"
    SHAPE_SIGNATURE_MISMATCH = "SHAPE_SIGNATURE_MISMATCH"
    DTYPE_SIGNATURE_MISMATCH = "DTYPE_SIGNATURE_MISMATCH"
    UNSUPPORTED_DTYPE = "UNSUPPORTED_DTYPE"
    NON_FINITE = "NON_FINITE"
    RESOURCE_LIMIT_EXCEEDED = "RESOURCE_LIMIT_EXCEEDED"
    DESCRIPTOR_MISMATCH = "DESCRIPTOR_MISMATCH"
    DEFINITION_MISMATCH = "DEFINITION_MISMATCH"
    PATH_NOT_ALLOWED = "PATH_NOT_ALLOWED"
    WEIGHTS_ARCHITECTURE_MISMATCH = "WEIGHTS_ARCHITECTURE_MISMATCH"
    WEIGHTS_DEFINITION_MISMATCH = "WEIGHTS_DEFINITION_MISMATCH"
    WEIGHTS_COMPATIBILITY_FAILED = "WEIGHTS_COMPATIBILITY_FAILED"


DefinitionId = str | int


class FrameworkSpec(StrictProtocolModel):
    name: str
    version: str


class DefinitionReference(StrictProtocolModel):
    definitionId: DefinitionId
    definitionSha256: str


class ModelDefinition(StrictProtocolModel):
    schemaVersion: str = "1.0"
    definitionVersion: str
    definitionId: DefinitionId
    code: str
    displayName: str
    modelFamily: str
    version: str
    variant: str
    taskType: str
    framework: str
    frameworkVersion: str
    definitionType: str
    definitionPayload: dict[str, Any]
    classCount: int = Field(ge=0)
    classOrder: list[str]
    ignoreIndex: int | None = None
    inputSpec: dict[str, Any]
    runtimeProfileId: str
    architectureSignature: str
    definitionSha256: str
    status: DefinitionStatus


class RuntimeProfile(StrictProtocolModel):
    schemaVersion: str = "1.0"
    runtimeProfileId: str
    name: str
    runtimeType: str
    supportedModelFamilies: list[str]
    pythonVersion: str
    frameworkRequirements: dict[str, str]
    cudaRequired: bool
    gpuRequired: bool
    adapterType: str
    healthCheckType: str
    status: RuntimeStatus
    runtimeMode: str = "LOCAL"
    serviceName: str | None = None
    baseUrlEnvironmentVariable: str | None = None


class ModelAvailabilityResult(StrictProtocolModel):
    available: bool
    availabilityState: str = "UNAVAILABLE"
    definitionId: DefinitionId
    definitionCode: str
    modelFamily: str
    variant: str
    taskType: str
    adapterAvailable: bool | None
    runtimeAvailable: bool | None
    definitionValid: bool | None
    definitionEnabled: bool | None = None
    runtimeEvaluated: bool | None = None
    selfCheckPassed: bool | None = None
    probeContext: str | None = None
    detectedPythonVersion: str | None = None
    detectedFrameworkVersion: str | None = None
    detectedTorchVersion: str | None = None
    cudaAvailable: bool | None = None
    gpuName: str | None = None
    reasonCode: AvailabilityReasonCode
    message: str


class InternImageModelAvailabilityResult(ModelAvailabilityResult):
    checkpointUsed: Literal[False]
    baseCheckpointUsed: Literal[False]
    pretrainedUsed: Literal[False]
    constructionMode: Literal["TRUSTED_CONFIG_TEMPLATE"]
    tensorCount: int | None = Field(default=None, ge=0)
    elementCount: int | None = Field(default=None, ge=0)


class RuntimeProbeResult(StrictProtocolModel):
    runtimeProfileId: str
    probeContext: str
    evaluated: bool
    ready: bool | None
    pythonAvailable: bool
    detectedPythonVersion: str
    torchAvailable: bool
    detectedTorchVersion: str | None = None
    detectedTorchvisionVersion: str | None = None
    frameworkAvailable: bool
    detectedFrameworkVersion: str | None = None
    frameworkVersionMatch: bool | None = None
    cudaAvailable: bool | None = None
    cudaBuildVersion: str | None = None
    gpuAvailable: bool | None = None
    gpuName: str | None = None
    reasonCode: AvailabilityReasonCode
    message: str


class RuntimeHealthResponse(StrictProtocolModel):
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
    mmcvAvailable: bool | None = None
    mmcvVersion: str | None = None
    mmengineAvailable: bool | None = None
    mmengineVersion: str | None = None
    mmsegAvailable: bool | None = None
    mmsegVersion: str | None = None
    internimageAvailable: bool | None = None
    internimageVersion: str | None = None
    python311CompatibilityPatch: bool | None = None
    dcnv3Available: bool | None = None
    dcnv3Version: str | None = None
    dcnv3SelfCheckPassed: bool | None = None
    minimalForwardCheckPassed: bool | None = None
    cudaAvailable: bool
    cudaBuildVersion: str | None = None
    gpuAvailable: bool
    gpuName: str | None = None
    runtimeReady: bool
    errors: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class DefinitionSelfCheckResult(StrictProtocolModel):
    passed: bool
    modelType: str | None = None
    classCount: int | None = None
    names: dict[int, str] = Field(default_factory=dict)
    stride: list[float] = Field(default_factory=list)
    architectureSignature: str | None = None
    checkpointUsed: bool = False
    reasonCode: AvailabilityReasonCode
    message: str


class ModelDefinitionCheckRequest(StrictProtocolModel):
    modelDefinition: ModelDefinition
    runtimeProfileId: str


class ClassContract(StrictProtocolModel):
    count: int = Field(ge=0)
    order: list[str]
    ignoreIndex: int | None = None


class StateContract(StrictProtocolModel):
    keyCount: int = Field(ge=0)
    tensorCount: int = Field(ge=0)
    elementCount: int = Field(ge=0)
    stateDictKeyHash: str
    shapeSignature: str
    clientDtypeSignature: str


class ModelDescriptor(StrictProtocolModel):
    schemaVersion: str = "1.0"
    descriptorVersion: str
    modelFamily: str
    version: str
    variant: str
    taskType: str
    framework: FrameworkSpec
    modelDefinition: DefinitionReference
    classes: ClassContract
    stateContract: StateContract
    parameterSemantics: str
    inputSpec: dict[str, Any]
    preprocessing: dict[str, Any]
    runtimeRequirements: dict[str, Any]
    extensions: dict[str, Any] = Field(default_factory=dict)


class WeightsFileSpec(StrictProtocolModel):
    format: str
    wrapperKey: str
    fileName: str
    sizeBytes: int = Field(ge=0)
    sha256: str


class WeightsStateSummary(StrictProtocolModel):
    keyCount: int = Field(ge=0)
    tensorCount: int = Field(ge=0)
    elementCount: int = Field(ge=0)
    stateDictKeyHash: str
    shapeSignature: str
    dtypeSignature: str
    dtypeCounts: dict[str, int]


class WeightsPackageManifest(StrictProtocolModel):
    schemaVersion: str = "1.0"
    weightsFormatVersion: str
    packageId: str
    artifactType: str
    descriptorSha256: str
    modelDefinition: DefinitionReference
    architectureSignature: str | None = None
    parameterSemantics: str
    weights: WeightsFileSpec
    state: WeightsStateSummary
    aggregationContribution: float = Field(gt=0)
    createdBy: dict[str, Any]


class ProtocolIssue(StrictProtocolModel):
    code: ProtocolErrorCode
    message: str
    field: str | None = None


class InspectionCheck(StrictProtocolModel):
    name: str
    passed: bool
    code: ProtocolErrorCode | None = None
    message: str


class InspectionReport(StrictProtocolModel):
    passed: bool = False
    errors: list[ProtocolIssue] = Field(default_factory=list)
    warnings: list[ProtocolIssue] = Field(default_factory=list)
    weightsSha256: str | None = None
    manifestSha256: str | None = None
    descriptorSha256: str | None = None
    keyCount: int | None = None
    tensorCount: int | None = None
    elementCount: int | None = None
    stateDictKeyHash: str | None = None
    shapeSignature: str | None = None
    dtypeSignature: str | None = None
    dtypeCounts: dict[str, int] = Field(default_factory=dict)
    floatingTensorCount: int = 0
    nonFloatingTensorCount: int = 0
    nanElements: int = 0
    positiveInfElements: int = 0
    negativeInfElements: int = 0
    sizeBytes: int | None = None
    checks: list[InspectionCheck] = Field(default_factory=list)


class ModelPackageInspectRequest(StrictProtocolModel):
    weightsPath: str
    manifest: WeightsPackageManifest
    descriptor: ModelDescriptor


class StateTensorMetadata(StrictProtocolModel):
    key: str
    shape: list[int]
    dtype: str


class RuntimeWeightsCompatibilityRequest(StrictProtocolModel):
    modelDefinition: ModelDefinition
    runtimeProfileId: str
    state: list[StateTensorMetadata]


class RuntimeWeightsCompatibilityResult(StrictProtocolModel):
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


class ModelPackageValidationRequest(StrictProtocolModel):
    weightsPath: str
    manifest: WeightsPackageManifest
    descriptor: ModelDescriptor
    trustedModelDefinition: ModelDefinition


class ModelPackageValidationResult(StrictProtocolModel):
    passed: bool
    reasonCode: str
    message: str
    inspection: InspectionReport
    compatibility: RuntimeWeightsCompatibilityResult | None = None


class WeightsFedAvgInput(StrictProtocolModel):
    assetId: int
    uploadId: int
    weightsPath: str
    expectedSha256: str
    manifestPath: str
    descriptorPath: str
    inspectionReportPath: str


class WeightsFedAvgRequest(StrictProtocolModel):
    workflowId: int
    trustedModelDefinition: ModelDefinition
    inputs: list[WeightsFedAvgInput] = Field(min_length=1)
    outputDirectory: str
    aggregationMethod: str = "EQUAL_FEDAVG"


class WeightsFedAvgResult(StrictProtocolModel):
    passed: bool
    reasonCode: str
    message: str
    workflowId: int
    modelDefinitionId: DefinitionId
    modelDefinitionCode: str
    inputCount: int
    inputAssetIds: list[int] = Field(default_factory=list)
    aggregationMethod: str
    aggregationWeights: list[float] = Field(default_factory=list)
    outputWeightsPath: str | None = None
    outputManifestPath: str | None = None
    outputDescriptorPath: str | None = None
    outputInspectionPath: str | None = None
    outputReportPath: str | None = None
    outputSha256: str | None = None
    inspection: InspectionReport | None = None


class RuntimeGlobalWeightsInput(StrictProtocolModel):
    assetId: int
    weightsPath: str
    expectedSha256: str
    expectedTensorCount: int = Field(ge=1)
    expectedElementCount: int = Field(ge=1)
    expectedStateDictKeyHash: str
    expectedShapeSignature: str
    expectedDtypeSignature: str


class RuntimeWeightsValidationRequest(StrictProtocolModel):
    workflowId: int
    jobId: str
    modelDefinition: ModelDefinition
    runtimeProfileId: str
    globalWeights: RuntimeGlobalWeightsInput
    datasetPath: str
    sampleOutputDirectory: str
    algorithmType: str


class RuntimeWeightsValidationResult(StrictProtocolModel):
    passed: bool
    reasonCode: str
    message: str
    runtimeProfileId: str
    modelDefinitionId: DefinitionId
    modelDefinitionCode: str
    definitionSha256: str
    architectureSignature: str
    weightsSha256: str
    reconstruction: dict[str, Any]
    metrics: dict[str, Any]
    validationResult: dict[str, Any]
    validationEvidence: dict[str, Any]
