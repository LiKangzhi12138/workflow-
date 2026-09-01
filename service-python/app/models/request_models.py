from typing import Literal, Optional

from pydantic import BaseModel


WORKFLOW_VALIDATION = "WORKFLOW_VALIDATION"
STANDALONE_VALIDATION = "STANDALONE_VALIDATION"


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
