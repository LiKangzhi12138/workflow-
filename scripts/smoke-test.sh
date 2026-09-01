#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${PROJECT_ROOT}/.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[FAIL] Env file not found: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

BASE_URL="${BASE_URL:-http://127.0.0.1:${PUBLIC_HTTP_PORT}}"
COOKIE_JAR="$(mktemp)"
TMP_BODY="$(mktemp)"
SERVER_USER="smoke_server_$(date +%s)"
CLIENT_USER="smoke_client_$(date +%s)"
SERVER_EMAIL="${SERVER_USER}@example.com"
CLIENT_EMAIL="${CLIENT_USER}@example.com"
PASSWORD="Smoke123!"

cleanup() {
  rm -f "${COOKIE_JAR}" "${TMP_BODY}"
}
trap cleanup EXIT

pass() {
  printf '[PASS] %s\n' "$1"
}

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  exit 1
}

http_expect_200() {
  local url="$1"
  local label="$2"
  local body
  body="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 "${url}")" || fail "${label} request failed: ${url}"
  pass "${label} returned HTTP 200"
  printf '%s' "${body}"
}

http_json_post_expect_ok() {
  local url="$1"
  local payload="$2"
  local label="$3"
  local body
  body="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 \
    -H 'Content-Type: application/json' \
    -X POST \
    -d "${payload}" \
    "${url}")" || fail "${label} request failed"

  if grep -q '"code":"OK"' <<< "${body}"; then
    pass "${label} returned business OK"
  else
    printf '%s\n' "${body}" >&2
    fail "${label} did not return business OK"
  fi
}

echo "[INFO] Smoke testing gateway at ${BASE_URL}"

ROOT_HTML="$(http_expect_200 "${BASE_URL}/" "Frontend home")"
grep -qi '<html' <<< "${ROOT_HTML}" || fail "Frontend home does not look like HTML"
pass "Frontend home returned HTML"

HEALTHZ="$(http_expect_200 "${BASE_URL}/healthz" "Nginx health")"
grep -q 'ok' <<< "${HEALTHZ}" || fail "Nginx health endpoint did not return ok"
pass "Nginx health response is correct"

JAVA_HEALTH="$(http_expect_200 "${BASE_URL}/api/health" "Java health")"
grep -q '"code":"OK"' <<< "${JAVA_HEALTH}" || fail "Java health payload is unexpected"
pass "Java health response is correct"

PYTHON_HEALTH="$(http_expect_200 "${BASE_URL}/api/health/python" "Python health via Java")"
grep -q '"code":"OK"' <<< "${PYTHON_HEALTH}" || fail "Python health payload is unexpected"
pass "Python health response is correct"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  docker compose --env-file "${ENV_FILE}" exec -T service-java sh -lc "curl -fsS '${PYTHON_SERVICE_BASE_URL}/internal/ping' >/dev/null" \
    || fail "Java container cannot reach Python service"
  pass "Java container can reach Python service"

  docker compose --env-file "${ENV_FILE}" exec -T service-java sh -lc "test -d '${WORKFLOW_STORAGE_ROOT}' && test -w '${WORKFLOW_STORAGE_ROOT}'" \
    || fail "Java container cannot access shared storage"
  pass "Java container can access shared storage"

  docker compose --env-file "${ENV_FILE}" exec -T service-python sh -lc "test -d '${WORKFLOW_STORAGE_ROOT}' && test -w '${WORKFLOW_STORAGE_ROOT}'" \
    || fail "Python container cannot access shared storage"
  pass "Python container can access shared storage"
fi

http_json_post_expect_ok \
  "${BASE_URL}/api/auth/register" \
  "{\"username\":\"${SERVER_USER}\",\"password\":\"${PASSWORD}\",\"displayName\":\"Smoke Server\",\"email\":\"${SERVER_EMAIL}\",\"roleCode\":\"SERVER\"}" \
  "Register server user"

http_json_post_expect_ok \
  "${BASE_URL}/api/auth/register" \
  "{\"username\":\"${CLIENT_USER}\",\"password\":\"${PASSWORD}\",\"displayName\":\"Smoke Client\",\"email\":\"${CLIENT_EMAIL}\",\"roleCode\":\"CLIENT\"}" \
  "Register client user"

LOGIN_BODY="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 \
  -H 'Content-Type: application/json' \
  -c "${COOKIE_JAR}" \
  -b "${COOKIE_JAR}" \
  -X POST \
  -d "{\"username\":\"${CLIENT_USER}\",\"password\":\"${PASSWORD}\"}" \
  "${BASE_URL}/api/auth/login")" || fail "Client login request failed"

grep -q '"code":"OK"' <<< "${LOGIN_BODY}" || fail "Client login did not return business OK"
pass "Client login returned business OK"

AUTH_WORKFLOWS="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 \
  -c "${COOKIE_JAR}" \
  -b "${COOKIE_JAR}" \
  "${BASE_URL}/api/workflows?pageNum=1&pageSize=1")" || fail "Workflow list request failed"
grep -q '"code":"OK"' <<< "${AUTH_WORKFLOWS}" || fail "Workflow list did not return business OK"
pass "Workflow list is reachable after login"

AUTH_MODELS="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 \
  -c "${COOKIE_JAR}" \
  -b "${COOKIE_JAR}" \
  "${BASE_URL}/api/models?pageNum=1&pageSize=1")" || fail "Model list request failed"
grep -q '"code":"OK"' <<< "${AUTH_MODELS}" || fail "Model list did not return business OK"
pass "Model list is reachable after login"

SERVER_LIST="$(curl --silent --show-error --fail --connect-timeout 10 --max-time 60 \
  -c "${COOKIE_JAR}" \
  -b "${COOKIE_JAR}" \
  "${BASE_URL}/api/users/servers")" || fail "Server user list request failed"
grep -q '"code":"OK"' <<< "${SERVER_LIST}" || fail "Server user list did not return business OK"
grep -q "${SERVER_USER}" <<< "${SERVER_LIST}" || fail "Server user list does not contain the smoke server account"
pass "Server user list is reachable after login"

UPLOAD_STATUS="$(curl --silent --show-error --output "${TMP_BODY}" --write-out '%{http_code}' \
  --connect-timeout 10 --max-time 60 \
  -H 'Content-Type: application/json' \
  -c "${COOKIE_JAR}" \
  -b "${COOKIE_JAR}" \
  -X POST \
  -d '{}' \
  "${BASE_URL}/api/workflow-uploads/init")"

case "${UPLOAD_STATUS}" in
  200|400|422)
    pass "Upload init endpoint is reachable (HTTP ${UPLOAD_STATUS})"
    ;;
  *)
    echo "[INFO] Response body from upload init:" >&2
    cat "${TMP_BODY}" >&2
    fail "Upload init endpoint returned unexpected HTTP status ${UPLOAD_STATUS}"
    ;;
esac

echo "[PASS] Smoke test completed successfully"
