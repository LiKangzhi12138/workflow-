from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.federated import router as federated_router
from app.api.health import router as health_router
from app.api.jobs import router as jobs_router
from app.core.config import settings
from app.core.logger import logger

app = FastAPI(title=settings.APP_NAME)

app.include_router(health_router)
app.include_router(jobs_router)
app.include_router(federated_router)


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(request: Request, exc: RequestValidationError):
    raw_body_bytes = await request.body()
    raw_body = raw_body_bytes.decode("utf-8", errors="ignore")
    summary = None

    if request.url.path == "/internal/federated/aggregate":
        summary = (
            "联邦学习聚合失败：请求体为空"
            if len(raw_body_bytes) == 0
            else "联邦学习聚合失败：接口字段不匹配"
        )
    elif request.url.path == "/internal/jobs":
        summary = (
            "Python 创建任务失败：请求体为空"
            if len(raw_body_bytes) == 0
            else "Python 创建任务失败：接口字段不匹配"
        )

    logger.error(
        "request validation failed, path=%s, method=%s, headers=%s, contentType=%s, rawBodyLength=%s, rawBody=%s, summary=%s, errors=%s",
        request.url.path,
        request.method,
        dict(request.headers),
        request.headers.get("content-type"),
        len(raw_body_bytes),
        raw_body,
        summary,
        exc.errors(),
    )
    content = {"detail": exc.errors()}
    if summary:
        content["summary"] = summary
    return JSONResponse(status_code=422, content=content)


@app.on_event("startup")
def on_startup():
    settings.ensure_directories()
    settings.log_summary(logger)


@app.get("/internal/ping")
def ping():
    return {
        "code": "OK",
        "message": "python ok"
    }
