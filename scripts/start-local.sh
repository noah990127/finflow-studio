#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
OFFICE_CONTAINER="${FINFLOW_ONLYOFFICE_CONTAINER:-finflow-onlyoffice}"
OFFICE_IMAGE="${FINFLOW_ONLYOFFICE_IMAGE:-onlyoffice/documentserver:8.3.3}"
OFFICE_PLATFORM="${FINFLOW_ONLYOFFICE_PLATFORM:-linux/amd64}"
OFFICE_SECRET="${ONLYOFFICE_JWT_SECRET:-finflow-office-secret}"

mkdir -p "$RUN_DIR"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
  OFFICE_SECRET="${ONLYOFFICE_JWT_SECRET:-finflow-office-secret}"
fi

export FINFLOW_DEMO_DATABASE_URL="${FINFLOW_DEMO_DATABASE_URL:-jdbc:postgresql://127.0.0.1:5432/finflow}"
export FINFLOW_DEMO_DATABASE_USERNAME="${FINFLOW_DEMO_DATABASE_USERNAME:-finflow}"
export FINFLOW_DEMO_DATABASE_PASSWORD="${FINFLOW_DEMO_DATABASE_PASSWORD:-finflow}"

wait_for_url() {
  local name="$1" url="$2" attempts="${3:-60}"
  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      printf '%s is ready\n' "$name"
      return 0
    fi
    sleep 2
  done
  printf '%s did not become ready. Check %s\n' "$name" "$RUN_DIR" >&2
  return 1
}

ensure_docker() {
  command -v docker >/dev/null 2>&1 || { echo "Docker is required for ONLYOFFICE." >&2; exit 1; }
  if docker info >/dev/null 2>&1; then return; fi
  if command -v colima >/dev/null 2>&1; then
    echo "Starting the local container engine..."
    colima start
  fi
  docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker or Colima and retry." >&2; exit 1; }
}

start_onlyoffice() {
  ensure_docker
  if docker container inspect "$OFFICE_CONTAINER" >/dev/null 2>&1; then
    docker start "$OFFICE_CONTAINER" >/dev/null
  else
    docker run -d --name "$OFFICE_CONTAINER" --platform "$OFFICE_PLATFORM" \
      -p 8082:80 \
      -e JWT_ENABLED=true \
      -e "JWT_SECRET=$OFFICE_SECRET" \
      -e JWT_HEADER=AuthorizationJwt \
      -e ALLOW_PRIVATE_IP_ADDRESS=true \
      -e ALLOW_META_IP_ADDRESS=true \
      -v finflow-onlyoffice-data:/var/www/onlyoffice/Data \
      -v finflow-onlyoffice-logs:/var/log/onlyoffice \
      --restart unless-stopped \
      "$OFFICE_IMAGE" >/dev/null
  fi
  echo "Waiting for ONLYOFFICE (the first start can take several minutes)..."
  wait_for_url "ONLYOFFICE" "http://127.0.0.1:8082/healthcheck" 150
}

start_service() {
  local name="$1" health_url="$2" workdir="$3" pid_file="$4" log_file="$5"
  shift 5
  if curl -fsS --max-time 2 "$health_url" >/dev/null 2>&1; then
    printf '%s is already running\n' "$name"
    return
  fi
  (
    cd "$workdir"
    nohup "$@" >"$log_file" 2>&1 &
    echo $! >"$pid_file"
  )
  wait_for_url "$name" "$health_url" 60
}

start_onlyoffice

if [[ -z "${FINFLOW_OFFICE_API_BASE_URL:-}" ]]; then
  OFFICE_API_HOST="host.docker.internal"
  if command -v colima >/dev/null 2>&1 && colima status >/dev/null 2>&1; then
    COLIMA_HOST="$(colima ssh -- ip route show default 2>/dev/null | awk 'NR == 1 { print $3 }')"
    [[ -n "$COLIMA_HOST" ]] && OFFICE_API_HOST="$COLIMA_HOST"
  fi
  FINFLOW_OFFICE_API_BASE_URL="http://$OFFICE_API_HOST:8080"
fi
export FINFLOW_OFFICE_API_BASE_URL

[[ -x "$ROOT/worker-python/.venv/bin/python" ]] || { echo "Python environment is missing: worker-python/.venv" >&2; exit 1; }
[[ -d "$ROOT/frontend/node_modules" ]] || { echo "Frontend dependencies are missing: run npm install in frontend" >&2; exit 1; }

start_service "Demo data API" "http://127.0.0.1:8011/health" \
  "$ROOT/examples/cases/demo-api" "$RUN_DIR/demo-api.pid" "$RUN_DIR/demo-api.log" \
  "$ROOT/worker-python/.venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port 8011

start_service "Inventory demo API" "http://127.0.0.1:8010/health" \
  "$ROOT/demo-services" "$RUN_DIR/inventory-api.pid" "$RUN_DIR/inventory-api.log" \
  "$ROOT/worker-python/.venv/bin/python" -m uvicorn inventory_api:app --host 127.0.0.1 --port 8010

start_service "Python worker" "http://127.0.0.1:8001/health" \
  "$ROOT/worker-python" "$RUN_DIR/python.pid" "$RUN_DIR/python.log" \
  "$ROOT/worker-python/.venv/bin/python" -m uvicorn app.main:app --host 127.0.0.1 --port 8001

if curl -fsS --max-time 2 "http://127.0.0.1:8080/actuator/health" >/dev/null 2>&1; then
  echo "Java API is already running. Restart it with this script if Office editing remains disabled."
else
  (
    cd "$ROOT/backend-java"
    nohup env \
      FINFLOW_OFFICE_ENABLED=true \
      FINFLOW_OFFICE_DOCUMENT_SERVER_URL=http://127.0.0.1:8082 \
      FINFLOW_OFFICE_INTERNAL_URL=http://127.0.0.1:8082 \
      "FINFLOW_OFFICE_API_BASE_URL=$FINFLOW_OFFICE_API_BASE_URL" \
      "FINFLOW_OFFICE_JWT_SECRET=$OFFICE_SECRET" \
      "FINFLOW_DEMO_DATABASE_URL=$FINFLOW_DEMO_DATABASE_URL" \
      "FINFLOW_DEMO_DATABASE_USERNAME=$FINFLOW_DEMO_DATABASE_USERNAME" \
      "FINFLOW_DEMO_DATABASE_PASSWORD=$FINFLOW_DEMO_DATABASE_PASSWORD" \
      mvn spring-boot:run >"$RUN_DIR/java.log" 2>&1 &
    echo $! >"$RUN_DIR/java.pid"
  )
  wait_for_url "Java API" "http://127.0.0.1:8080/actuator/health" 90
fi

start_service "Vue workbench" "http://127.0.0.1:5174/" \
  "$ROOT/frontend" "$RUN_DIR/frontend.pid" "$RUN_DIR/frontend.log" \
  npm run dev -- --host 127.0.0.1 --port 5174

echo
echo "FinFlow Studio is ready: http://127.0.0.1:5174/"
echo "Demo data API:          http://127.0.0.1:8011/health"
echo "Inventory demo API:     http://127.0.0.1:8010/health"
echo "ONLYOFFICE health:      http://127.0.0.1:8082/healthcheck"
echo "ONLYOFFICE file bridge: $FINFLOW_OFFICE_API_BASE_URL"
