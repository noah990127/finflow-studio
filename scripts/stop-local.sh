#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
OFFICE_CONTAINER="${FINFLOW_ONLYOFFICE_CONTAINER:-finflow-onlyoffice}"

for name in frontend java python; do
  pid_file="$RUN_DIR/$name.pid"
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" >/dev/null 2>&1; then kill "$pid" >/dev/null 2>&1 || true; fi
    rm -f "$pid_file"
  fi
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker stop "$OFFICE_CONTAINER" >/dev/null 2>&1 || true
fi

echo "FinFlow Studio local services have been stopped."
