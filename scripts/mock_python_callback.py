#!/usr/bin/env python
"""
手工模拟 Python -> Java 回调。

示例：
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status PREPARING
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status COMPLETED --result-file-name abc123_result.json --result-file-path D:/tmp/abc123_result.json
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status FAILED --error-message "mock failure"
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status COMPLETED --bad-sign
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status COMPLETED --metrics-json "{\"mAP\":0.9,\"precision\":0.92}"
python scripts/mock_python_callback.py --workflow-id 5 --job-id abc123 --status PREPARING --progress 25 --message "duplicate callback"
"""

import argparse
import hashlib
import json
import sys
from typing import Any

import requests


def canonical_json(payload: dict[str, Any]) -> str:
    return json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def generate_signature(payload: dict[str, Any], secret: str) -> str:
    return hashlib.sha256((canonical_json(payload) + secret).encode("utf-8")).hexdigest()


def parse_json_argument(raw: str | None, field_name: str) -> dict[str, Any] | None:
    if raw is None:
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{field_name} 不是合法 JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"{field_name} 必须是 JSON 对象")
    return value


def build_payload(args: argparse.Namespace) -> dict[str, Any]:
    metrics = parse_json_argument(args.metrics_json, "metrics-json")
    if metrics is None and args.status == "COMPLETED":
        metrics = {
            "mAP": args.metric_map,
            "precision": args.metric_precision,
            "recall": args.metric_recall,
        }

    result_file = parse_json_argument(args.result_file_json, "result-file-json")
    if result_file is None and (args.result_file_name or args.result_file_path):
        result_file = {
            "fileName": args.result_file_name,
            "filePath": args.result_file_path,
        }

    return {
        "jobId": args.job_id,
        "workflowId": args.workflow_id,
        "status": args.status,
        "progress": args.progress,
        "message": args.message,
        "metrics": metrics,
        "resultFile": result_file,
        "errorMessage": args.error_message,
    }


def main():
    parser = argparse.ArgumentParser(description="Send a mock Python callback to Java.")
    parser.add_argument("--url", default="http://127.0.0.1:8080/api/internal/python/jobs/callback")
    parser.add_argument("--secret", default="dev-secret")
    parser.add_argument("--workflow-id", type=int, required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--status", required=True,
                        choices=["ACCEPTED", "PREPARING", "TRAINING_RUNNING", "VALIDATING", "COMPLETED", "FAILED"])
    parser.add_argument("--progress", type=int, default=None)
    parser.add_argument("--message", default="manual callback")
    parser.add_argument("--error-message", default=None)
    parser.add_argument("--metrics-json", default=None, help="Raw JSON object for metrics, e.g. {\"mAP\":0.9}")
    parser.add_argument("--result-file-json", default=None, help="Raw JSON object for resultFile, e.g. {\"fileName\":\"a.json\",\"filePath\":\"D:/a.json\"}")
    parser.add_argument("--result-file-name", default=None)
    parser.add_argument("--result-file-path", default=None)
    parser.add_argument("--metric-map", type=float, default=0.88)
    parser.add_argument("--metric-precision", type=float, default=0.91)
    parser.add_argument("--metric-recall", type=float, default=0.86)
    parser.add_argument("--bad-sign", action="store_true", help="Use an intentionally invalid sign")
    parser.add_argument("--timeout", type=int, default=10)
    args = parser.parse_args()

    default_progress = {
        "ACCEPTED": 10,
        "PREPARING": 25,
        "TRAINING_RUNNING": 60,
        "VALIDATING": 85,
        "COMPLETED": 100,
        "FAILED": 0,
    }
    if args.progress is None:
        args.progress = default_progress[args.status]

    payload = build_payload(args)
    sign = generate_signature(payload, args.secret)
    if args.bad_sign:
        sign = ("0" if sign[-1] != "0" else "1") + sign[1:]

    body = dict(payload)
    body["sign"] = sign

    print("Request URL:", args.url)
    print("Sign Mode:", "bad-sign" if args.bad_sign else "normal-sign")
    print("Payload:")
    print(json.dumps(body, ensure_ascii=False, indent=2))

    try:
        response = requests.post(args.url, json=body, timeout=args.timeout)
    except requests.RequestException as exc:
        print("Request Failed:", exc)
        return 1

    print("HTTP Status:", response.status_code)
    print("Response Body:", response.text)
    return 0 if response.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
