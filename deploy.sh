#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-up}"
TARGET="${2:-}"
ENV_FILE="${PROJECT_ROOT}/.env"

usage() {
  cat <<'EOF'
Usage:
  ./deploy.sh preflight
  ./deploy.sh config
  ./deploy.sh up
  ./deploy.sh down
  ./deploy.sh restart
  ./deploy.sh logs [service-name]
  ./deploy.sh ps
  ./deploy.sh init-db
  ./deploy.sh smoke
EOF
}

ensure_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    cp "${PROJECT_ROOT}/.env.example" "${ENV_FILE}"
    echo "[WARN] .env was missing. A new .env has been created from .env.example." >&2
    echo "[WARN] Edit ${ENV_FILE} and replace example secrets before running deploy commands." >&2
    exit 1
  fi
}

load_env() {
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
}

ensure_host_dirs() {
  mkdir -p "${HOST_STORAGE_ROOT}" "${HOST_MYSQL_DATA_ROOT}"
}

run_preflight() {
  "${PROJECT_ROOT}/scripts/preflight-check.sh" "${ENV_FILE}"
}

compose() {
  docker compose --env-file "${ENV_FILE}" "$@"
}

run_config() {
  compose config
}

run_up() {
  run_preflight
  ensure_host_dirs
  compose up -d --build
}

run_down() {
  compose down
}

run_restart() {
  run_preflight
  ensure_host_dirs
  compose down
  compose up -d --build
}

run_logs() {
  if [[ -n "${TARGET}" ]]; then
    compose logs -f "${TARGET}"
  else
    compose logs -f
  fi
}

run_ps() {
  compose ps
}

run_init_db() {
  "${PROJECT_ROOT}/scripts/init-db.sh" "${ENV_FILE}"
}

run_smoke() {
  "${PROJECT_ROOT}/scripts/smoke-test.sh" "${ENV_FILE}"
}

cd "${PROJECT_ROOT}"
ensure_env_file

if [[ -x "${PROJECT_ROOT}/deploy.sh" ]]; then
  :
else
  chmod +x "${PROJECT_ROOT}/deploy.sh" 2>/dev/null || true
fi

case "${ACTION}" in
  preflight)
    run_preflight
    ;;
  config)
    load_env
    run_config
    ;;
  up)
    load_env
    run_up
    ;;
  down)
    load_env
    run_down
    ;;
  restart)
    load_env
    run_restart
    ;;
  logs)
    load_env
    run_logs
    ;;
  ps)
    load_env
    run_ps
    ;;
  init-db)
    load_env
    run_init_db
    ;;
  smoke)
    load_env
    run_smoke
    ;;
  *)
    usage
    exit 1
    ;;
esac
