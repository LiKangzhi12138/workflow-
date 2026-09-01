from typing import Optional

from pydantic import BaseModel


class CreateJobResponse(BaseModel):
    code: str
    message: str
    data: dict


class JobStatusResponse(BaseModel):
    code: str
    message: str
    data: dict


class JobStatusData(BaseModel):
    jobId: str
    jobType: str
    workflowId: Optional[int] = None
    standaloneValidationId: Optional[int] = None
    status: str
    progress: int
    message: str
    resultFilePath: Optional[str] = None
    errorMessage: Optional[str] = None
