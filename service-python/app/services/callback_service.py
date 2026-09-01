import hashlib
import json
import time

import requests

from app.core.config import settings
from app.core.logger import logger


def canonical_json(payload: dict) -> str:
    return json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def generate_signature(payload: dict, secret: str):
    raw = canonical_json(payload) + secret
    return hashlib.sha256(raw.encode()).hexdigest()


def summarize_text(value, max_length=200):
    if value is None:
        return ""
    compact = " ".join(str(value).split())
    if len(compact) <= max_length:
        return compact
    return compact[: max_length - 3] + "..."


def callback_to_java(url, payload, retry=3, secret=None):
    resolved_secret = secret or settings.WORKFLOW_CALLBACK_SECRET
    if not resolved_secret:
        logger.error(
            "callback secret missing, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=false, failureReason=missing_callback_secret",
            url,
            payload.get("jobType"),
            payload.get("workflowId"),
            payload.get("standaloneValidationId"),
            payload.get("jobId"),
            payload.get("status"),
        )
        return False, "missing_callback_secret"

    body = dict(payload)
    try:
        body["sign"] = generate_signature(payload, resolved_secret)
    except Exception as exc:
        failure_reason = f"sign_generate_failed:{type(exc).__name__}:{exc}"
        logger.exception(
            "callback sign generation failed, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=false, failureReason=%s",
            url,
            payload.get("jobType"),
            payload.get("workflowId"),
            payload.get("standaloneValidationId"),
            payload.get("jobId"),
            payload.get("status"),
            failure_reason,
        )
        return False, failure_reason

    sign_generated = bool(body.get("sign"))
    headers = {"Content-Type": "application/json"}
    last_failure_reason = None

    for attempt in range(retry):
        try:
            logger.info(
                "callback attempt %s, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=%s",
                attempt + 1,
                url,
                payload.get("jobType"),
                payload.get("workflowId"),
                payload.get("standaloneValidationId"),
                payload.get("jobId"),
                payload.get("status"),
                sign_generated,
            )

            resp = requests.post(url, json=body, headers=headers, timeout=5)
            body_summary = summarize_text(resp.text)
            logger.info(
                "callback response, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=%s, httpStatusCode=%s, responseBodySummary=%s",
                url,
                payload.get("jobType"),
                payload.get("workflowId"),
                payload.get("standaloneValidationId"),
                payload.get("jobId"),
                payload.get("status"),
                sign_generated,
                resp.status_code,
                body_summary,
            )

            if resp.status_code == 200:
                return True, None

            last_failure_reason = f"http_{resp.status_code}:{body_summary or 'empty_response'}"
            logger.warning(
                "callback non-200 response, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=%s, httpStatusCode=%s, responseBodySummary=%s, failureReason=%s",
                url,
                payload.get("jobType"),
                payload.get("workflowId"),
                payload.get("standaloneValidationId"),
                payload.get("jobId"),
                payload.get("status"),
                sign_generated,
                resp.status_code,
                body_summary,
                last_failure_reason,
            )
        except Exception as exc:
            last_failure_reason = f"{type(exc).__name__}:{exc}"
            logger.exception(
                "callback error %s, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=%s, failureReason=%s",
                attempt + 1,
                url,
                payload.get("jobType"),
                payload.get("workflowId"),
                payload.get("standaloneValidationId"),
                payload.get("jobId"),
                payload.get("status"),
                sign_generated,
                last_failure_reason,
            )

        time.sleep(2)

    logger.error(
        "callback failed permanently, callbackUrl=%s, jobType=%s, workflowId=%s, standaloneValidationId=%s, jobId=%s, status=%s, signGenerated=%s, failureReason=%s",
        url,
        payload.get("jobType"),
        payload.get("workflowId"),
        payload.get("standaloneValidationId"),
        payload.get("jobId"),
        payload.get("status"),
        sign_generated,
        last_failure_reason or "unknown",
    )
    return False, last_failure_reason or "unknown"
