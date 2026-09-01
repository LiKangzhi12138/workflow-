#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${PROJECT_ROOT}/.env}"
EXIT_CODE=0

info() {
  printf '[INFO] %s\n' "$1"
}

pass() {
  printf '[PASS] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1"
}

fail() {
  printf '[FAIL] %s\n' "$1"
  EXIT_CODE=1
}

check_file() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    pass "Found file: ${path#${PROJECT_ROOT}/}"
  else
    fail "Missing file: ${path#${PROJECT_ROOT}/}"
  fi
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

check_port_available() {
  local port="$1"
  local label="$2"
  local occupied=1

  if command_exists ss; then
    if ss -ltn | awk '{print $4}' | grep -Eq "[:.]${port}$"; then
      occupied=0
    fi
  elif command_exists netstat; then
    if netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$"; then
      occupied=0
    fi
  else
    warn "Skip port check for ${label}: neither ss nor netstat is installed"
    return
  fi

  if [[ "${occupied}" -eq 0 ]]; then
    fail "${label} port ${port} is already in use"
  else
    pass "${label} port ${port} is available"
  fi
}

check_writable_dir() {
  local dir="$1"
  local name="$2"
  local probe
  if mkdir -p "${dir}" 2>/dev/null; then
    probe="${dir}/.write-test-$$"
    if touch "${probe}" 2>/dev/null; then
      rm -f "${probe}"
      pass "${name} is writable: ${dir}"
    else
      fail "${name} exists but is not writable: ${dir}"
    fi
  else
    fail "${name} cannot be created: ${dir}"
  fi
}

require_env() {
  local name="$1"
  if [[ -n "${!name:-}" ]]; then
    pass "Env ${name} is set"
  else
    fail "Env ${name} is empty"
  fi
}

PROJECT_FILES=(
  "${PROJECT_ROOT}/docker-compose.yml"
  "${PROJECT_ROOT}/nginx.conf"
  "${PROJECT_ROOT}/frontend-web/Dockerfile"
  "${PROJECT_ROOT}/service-java/Dockerfile"
  "${PROJECT_ROOT}/service-python/Dockerfile"
  "${PROJECT_ROOT}/docs/sql/000_init_workflow_platform_schema.sql"
  "${PROJECT_ROOT}/docker/mysql/init/000_init_workflow_platform_schema.sh"
  "${PROJECT_ROOT}/README_DEPLOY.md"
  "${PROJECT_ROOT}/deploy.sh"
  "${PROJECT_ROOT}/scripts/preflight-check.sh"
  "${PROJECT_ROOT}/scripts/smoke-test.sh"
  "${PROJECT_ROOT}/scripts/init-db.sh"
)

info "workflow-platform deployment preflight"

for file in "${PROJECT_FILES[@]}"; do
  check_file "${file}"
done

if [[ ! -f "${ENV_FILE}" ]]; then
  fail "Env file not found: ${ENV_FILE}. Copy .env.example to .env first."
else
  pass "Found env file: ${ENV_FILE}"
fi

if command_exists docker; then
  pass "docker is installed"
  if docker compose version >/dev/null 2>&1; then
    pass "docker compose plugin is installed"
  else
    fail "docker compose plugin is missing"
  fi
else
  fail "docker is not installed"
fi

if command_exists curl; then
  pass "curl is installed"
else
  fail "curl is not installed"
fi

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a

  REQUIRED_ENV_VARS=(
    PUBLIC_HTTP_PORT
    MYSQL_HOST_PORT
    MYSQL_DATABASE
    MYSQL_USER
    MYSQL_PASSWORD
    MYSQL_ROOT_PASSWORD
    VITE_API_BASE_URL
    NGINX_SERVER_NAME
    CLIENT_MAX_BODY_SIZE
    JAVA_UPSTREAM_URL
    SERVER_PORT
    SPRING_DATASOURCE_URL
    SPRING_DATASOURCE_USERNAME
    SPRING_DATASOURCE_PASSWORD
    WORKFLOW_STORAGE_ROOT
    WORKFLOW_MODEL_UPLOAD_DIR
    WORKFLOW_MODEL_ROOT
    WORKFLOW_VALIDATION_CACHE_ROOT
    WORKFLOW_FEDERATED_MODEL_ROOT
    SPRING_SERVLET_MULTIPART_LOCATION
    PYTHON_SERVICE_BASE_URL
    JAVA_CALLBACK_BASE_URL
    PYTHON_CALLBACK_SECRET
    PYTHON_SERVICE_PORT
    PYTHON_STORAGE_ROOT
    PYTHON_VALIDATION_OUTPUT_ROOT
    PYTHON_VALIDATION_SAMPLE_ROOT
    HOST_STORAGE_ROOT
    HOST_MYSQL_DATA_ROOT
  )

  for var_name in "${REQUIRED_ENV_VARS[@]}"; do
    require_env "${var_name}"
  done

  case "${MYSQL_PASSWORD}" in
    change_this_mysql_password|"")
      fail "MYSQL_PASSWORD still uses the example placeholder"
      ;;
    *)
      pass "MYSQL_PASSWORD is customized"
      ;;
  esac

  case "${MYSQL_ROOT_PASSWORD}" in
    change_this_mysql_root_password|"")
      fail "MYSQL_ROOT_PASSWORD still uses the example placeholder"
      ;;
    *)
      pass "MYSQL_ROOT_PASSWORD is customized"
      ;;
  esac

  case "${SPRING_DATASOURCE_PASSWORD}" in
    change_this_mysql_password|"")
      fail "SPRING_DATASOURCE_PASSWORD still uses the example placeholder"
      ;;
    *)
      pass "SPRING_DATASOURCE_PASSWORD is customized"
      ;;
  esac

  case "${PYTHON_CALLBACK_SECRET}" in
    replace_with_a_long_random_secret|"")
      fail "PYTHON_CALLBACK_SECRET still uses the example placeholder"
      ;;
    *)
      pass "PYTHON_CALLBACK_SECRET is customized"
      ;;
  esac

  if [[ "${JAVA_UPSTREAM_URL}" == "http://service-java:${SERVER_PORT}" ]]; then
    pass "JAVA_UPSTREAM_URL matches the compose service name"
  else
    warn "JAVA_UPSTREAM_URL is ${JAVA_UPSTREAM_URL}; recommended value is http://service-java:${SERVER_PORT}"
  fi

  if [[ "${PYTHON_SERVICE_BASE_URL}" == "http://service-python:${PYTHON_SERVICE_PORT}" ]]; then
    pass "PYTHON_SERVICE_BASE_URL matches the compose service name"
  else
    warn "PYTHON_SERVICE_BASE_URL is ${PYTHON_SERVICE_BASE_URL}; recommended value is http://service-python:${PYTHON_SERVICE_PORT}"
  fi

  if [[ "${JAVA_CALLBACK_BASE_URL}" == "http://service-java:${SERVER_PORT}" ]]; then
    pass "JAVA_CALLBACK_BASE_URL matches the compose service name"
  else
    warn "JAVA_CALLBACK_BASE_URL is ${JAVA_CALLBACK_BASE_URL}; recommended value is http://service-java:${SERVER_PORT}"
  fi

  if [[ "${VITE_API_BASE_URL}" == "/api" ]]; then
    pass "VITE_API_BASE_URL uses same-origin reverse proxy mode"
  else
    warn "VITE_API_BASE_URL is ${VITE_API_BASE_URL}; same-origin /api is recommended for session-based deployment"
  fi

  if [[ -z "${APP_ALLOWED_ORIGINS:-}" ]]; then
    pass "APP_ALLOWED_ORIGINS is empty; same-origin deployment will avoid CORS issues"
  else
    warn "APP_ALLOWED_ORIGINS is set; verify it matches your browser origin"
  fi

  check_writable_dir "${HOST_STORAGE_ROOT}" "HOST_STORAGE_ROOT"
  check_writable_dir "${HOST_MYSQL_DATA_ROOT}" "HOST_MYSQL_DATA_ROOT"

  check_port_available "${PUBLIC_HTTP_PORT}" "Gateway"
  check_port_available "${MYSQL_HOST_PORT}" "MySQL"

  if [[ -x "${PROJECT_ROOT}/deploy.sh" ]]; then
    pass "deploy.sh is executable"
  else
    warn "deploy.sh is not executable yet. Run: chmod +x deploy.sh"
  fi

  if [[ -x "${PROJECT_ROOT}/scripts/preflight-check.sh" ]]; then
    pass "scripts/preflight-check.sh is executable"
  else
    warn "scripts/preflight-check.sh is not executable yet. Run: chmod +x scripts/preflight-check.sh"
  fi

  if [[ -x "${PROJECT_ROOT}/scripts/smoke-test.sh" ]]; then
    pass "scripts/smoke-test.sh is executable"
  else
    warn "scripts/smoke-test.sh is not executable yet. Run: chmod +x scripts/smoke-test.sh"
  fi

  if command_exists docker && docker compose version >/dev/null 2>&1; then
    if docker compose --env-file "${ENV_FILE}" config >/dev/null; then
      pass "docker compose config validation passed"
    else
      fail "docker compose config validation failed"
    fi
  fi
fi

if [[ "${EXIT_CODE}" -eq 0 ]]; then
  pass "Preflight checks passed"
else
  fail "Preflight checks found blocking issues"
fi

exit "${EXIT_CODE}"
