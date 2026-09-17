from typing import Any, Literal, Optional

from pydantic import BaseModel


WORKFLOW_VALIDATION = "WORKFLOW_VALIDATION"
STANDALONE_VALIDATION = "STANDALONE_VALIDATION"
LEGACY_CHECKPOINT = "LEGACY_CHECKPOINT"
WEIGHTS_PROTOCOL_V1 = "WEIGHTS_PROTOCOL_V1"


class GlobalWeightsValidationInput(BaseModel):
    assetId: int
    weightsPath: str
    expectedSha256: str
    manifestPath: str
    descriptorPath: str
    inspectionReportPath: str
    aggregationReportPath: str
    expectedTensorCount: int
    expectedElementCount: int
    expectedStateDictKeyHash: str
    expectedShapeSignature: str
    expectedDtypeSignature: str


class CreateJobRequest(BaseModel):
    jobId: str
    jobType: Literal["WORKFLOW_VALIDATION", "STANDALONE_VALIDATION"]
    workflowId: Optional[int] = None
    standaloneValidationId: Optional[int] = None
    modelPath: str
    datasetPath: str
    algorithmType: str
    callbackUrl: str
    callbackSecret: str
    validationMode: Literal["LEGACY_CHECKPOINT", "WEIGHTS_PROTOCOL_V1"] = LEGACY_CHECKPOINT
    runtimeProfileId: Optional[str] = None
    trustedModelDefinition: Optional[dict[str, Any]] = None
    globalWeights: Optional[GlobalWeightsValidationInput] = None
