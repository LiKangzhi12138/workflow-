#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
MODE="${1:-dry-run}"

clean_children() {
  local relative_path="$1"
  local category="$2"
  local root="$PROJECT_ROOT/$relative_path"

  if [[ ! -e "$root" ]]; then
    echo "[SKIP] $category not found: $root"
    return
  fi

  local project_full root_full
  project_full="$(cd "$PROJECT_ROOT" && pwd)"
  root_full="$(cd "$root" && pwd)"
  case "$root_full" in
    "$project_full"/*) ;;
    *) echo "Refuse to clean outside project root: $root_full" >&2; exit 1 ;;
  esac

  echo "[SCAN] $category => $root_full"
  find "$root_full" -mindepth 1 -maxdepth 1 -print | while IFS= read -r target; do
    case "$target" in
      "$root_full"/*) ;;
      *) echo "Refuse to clean path outside controlled root: $target" >&2; exit 1 ;;
    esac
    if [[ "$MODE" == "execute" ]]; then
      rm -rf -- "$target"
      echo "[DELETED] $category => $target"
    else
      echo "[DRY-RUN] $category => $target"
    fi
  done
}

echo "Workflow Platform result-artifact cleanup"
echo "ProjectRoot: $PROJECT_ROOT"
echo "Mode: $MODE"
echo ""

clean_children "storage/dev/validation-cache" "Java validation image cache / recognition samples"
clean_children "storage/dev/results" "Java workflow result directory"
clean_children "storage/dev/logs" "Java controlled result logs"
clean_children "service-python/app/storage/results" "Python validation result JSON"
clean_children "service-python/app/storage/result-samples" "Python recognition sample images"
clean_children ".runtime/local-server/storage/validation-cache" "Local-server validation image cache"
clean_children ".runtime/local-server/storage/results" "Local-server Java result directory"
clean_children ".runtime/local-server/storage/python/results" "Local-server Python validation result JSON"
clean_children ".runtime/local-server/storage/python/result-samples" "Local-server Python recognition sample images"
clean_children ".runtime/local-server/logs/java" "Local-server Java logs"
clean_children ".runtime/local-server/logs/python" "Local-server Python logs"

echo ""
echo "Done. Formal model assets and dataset assets were not touched."
if [[ "$MODE" != "execute" ]]; then
  echo "Re-run with: bash scripts/cleanup-result-artifacts.sh execute"
fi
