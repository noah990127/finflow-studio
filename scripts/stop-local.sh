#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
OFFICE_CONTAINER="${FINFLOW_ONLYOFFICE_CONTAINER:-finflow-onlyoffice}"

for name in frontend java java-launcher python demo-api inventory-api; do
  pid_file="$RUN_DIR/$name.pid"
  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" >/dev/null 2>&1; then kill "$pid" >/dev/null 2>&1 || true; fi
    rm -f "$pid_file"
  fi
done

# Recover from stale or missing pid files left by an interrupted launcher.
for port in 5174 8001 8080 8010; do
  while IFS= read -r listener_pid; do
    [[ -n "$listener_pid" ]] && kill "$listener_pid" >/dev/null 2>&1 || true
  done < <(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
done

# Wait until listeners have really released their ports. Starting immediately after a
# graceful signal can otherwise mistake an exiting process for a healthy old service.
for port in 5174 8001 8080 8010; do
  for ((attempt = 1; attempt <= 30; attempt++)); do
    if ! lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then break; fi
    sleep 0.2
  done
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  for container in "$OFFICE_CONTAINER" finflow-demo-api finflow-minio finflow-postgres; do
    docker stop "$container" >/dev/null 2>&1 || true
  done
fi

echo "FinBTP Studio local services have been stopped."
