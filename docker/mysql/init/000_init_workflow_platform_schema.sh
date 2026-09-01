#!/bin/sh
set -eu

SCHEMA_FILE="/opt/workflow-platform-bootstrap/000_init_workflow_platform_schema.sql"

if [ ! -f "${SCHEMA_FILE}" ]; then
  echo "schema file not found: ${SCHEMA_FILE}" >&2
  exit 1
fi

if [ -z "${MYSQL_DATABASE:-}" ]; then
  echo "MYSQL_DATABASE is required for schema initialization" >&2
  exit 1
fi

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --database="${MYSQL_DATABASE}" < "${SCHEMA_FILE}"
