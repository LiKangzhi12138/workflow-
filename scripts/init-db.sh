#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${PROJECT_ROOT}/.env}"
SCHEMA_FILE="${PROJECT_ROOT}/docs/sql/000_init_workflow_platform_schema.sql"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[FAIL] Env file not found: ${ENV_FILE}" >&2
  echo "Copy .env.example to .env and edit it first." >&2
  exit 1
fi

if [[ ! -f "${SCHEMA_FILE}" ]]; then
  echo "[FAIL] Schema file not found: ${SCHEMA_FILE}" >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "[FAIL] docker is not installed" >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "[FAIL] docker compose plugin is not installed" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

TABLE_COUNT="$(docker compose --env-file "${ENV_FILE}" exec -T mysql sh -lc "mysql -N -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}';\"")"
TABLE_COUNT="${TABLE_COUNT//$'\r'/}"
TABLE_COUNT="${TABLE_COUNT//$'\n'/}"

if [[ -z "${TABLE_COUNT}" ]]; then
  echo "[FAIL] Unable to determine current table count. Is the mysql container running?" >&2
  exit 1
fi

if [[ "${TABLE_COUNT}" != "0" && "${ALLOW_NONEMPTY_DB_INIT:-0}" != "1" ]]; then
  echo "[FAIL] Database ${MYSQL_DATABASE} already contains ${TABLE_COUNT} tables." >&2
  echo "Set ALLOW_NONEMPTY_DB_INIT=1 only if you really want to overwrite the schema." >&2
  exit 1
fi

echo "[INFO] Importing schema into database ${MYSQL_DATABASE}"
cat "${SCHEMA_FILE}" | docker compose --env-file "${ENV_FILE}" exec -T mysql sh -lc "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --database=\"\$MYSQL_DATABASE\""
echo "[PASS] Schema import completed"
