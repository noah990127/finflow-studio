#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/.run"
OFFICE_CONTAINER="${FINFLOW_ONLYOFFICE_CONTAINER:-finflow-onlyoffice}"
OFFICE_IMAGE="${FINFLOW_ONLYOFFICE_IMAGE:-onlyoffice/documentserver:8.3.3}"
OFFICE_PLATFORM="${FINFLOW_ONLYOFFICE_PLATFORM:-linux/amd64}"
OFFICE_SECRET="${ONLYOFFICE_JWT_SECRET:-finflow-office-secret}"
SEARXNG_CONTAINER="${FINFLOW_SEARXNG_CONTAINER:-finflow-searxng}"
SEARXNG_IMAGE="${SEARXNG_IMAGE:-ghcr.io/searxng/searxng}:${SEARXNG_VERSION:-2026.9.25-12f8b6515}"
SEARXNG_PORT="${SEARXNG_HOST_PORT:-8888}"

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

start_existing_container() {
  local name="$1"
  if docker container inspect "$name" >/dev/null 2>&1; then
    docker start "$name" >/dev/null
    printf '%s container is running\n' "$name"
  fi
}

start_onlyoffice() {
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
  "$ROOT/scripts/configure-onlyoffice-large-files.sh" "$OFFICE_CONTAINER"
  wait_for_url "ONLYOFFICE" "http://127.0.0.1:8082/healthcheck" 60
}

start_searxng() {
  if docker container inspect "$SEARXNG_CONTAINER" >/dev/null 2>&1; then
    docker start "$SEARXNG_CONTAINER" >/dev/null
  else
    local proxy_url="${SEARXNG_OUTBOUND_PROXY:-}"
    if [[ -z "$proxy_url" ]]; then
      proxy_url="$(docker info --format '{{.HTTPProxy}}' 2>/dev/null || true)"
      proxy_url="${proxy_url//host.lima.internal/host.docker.internal}"
    fi
    local proxy_args=()
    if [[ -n "$proxy_url" && "$proxy_url" != "<no value>" ]]; then
      proxy_args+=(--add-host host.docker.internal:host-gateway)
      proxy_args+=(-e "HTTP_PROXY=$proxy_url" -e "HTTPS_PROXY=$proxy_url")
      proxy_args+=(-e "http_proxy=$proxy_url" -e "https_proxy=$proxy_url")
      proxy_args+=(-e "NO_PROXY=localhost,127.0.0.1" -e "no_proxy=localhost,127.0.0.1")
    fi
    docker run -d --name "$SEARXNG_CONTAINER" \
      -p "$SEARXNG_PORT:8080" \
      -e "SEARXNG_BASE_URL=http://127.0.0.1:$SEARXNG_PORT/" \
      -e "SEARXNG_SECRET=${SEARXNG_SECRET:-finflow-local-search-secret}" \
      -e SEARXNG_LIMITER=false \
      "${proxy_args[@]}" \
      -v "$ROOT/infra/searxng/settings.yml:/etc/searxng/settings.yml:ro" \
      -v finflow-searxng-cache:/var/cache/searxng \
      --restart unless-stopped \
      "$SEARXNG_IMAGE" >/dev/null
  fi
  echo "Waiting for the search service..."
  wait_for_url "SearxNG" "http://127.0.0.1:$SEARXNG_PORT/search?q=health&format=json" 60
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

ensure_docker
start_existing_container "finflow-postgres"
start_existing_container "finflow-minio"
start_onlyoffice
start_searxng

HOST_OUTBOUND_PROXY="${FINFLOW_OUTBOUND_PROXY:-${SEARXNG_OUTBOUND_PROXY:-}}"
if [[ -z "$HOST_OUTBOUND_PROXY" ]]; then
  HOST_OUTBOUND_PROXY="$(docker info --format '{{.HTTPProxy}}' 2>/dev/null || true)"
fi
HOST_OUTBOUND_PROXY="${HOST_OUTBOUND_PROXY//host.lima.internal/127.0.0.1}"
HOST_OUTBOUND_PROXY="${HOST_OUTBOUND_PROXY//host.docker.internal/127.0.0.1}"
if [[ -n "$HOST_OUTBOUND_PROXY" && "$HOST_OUTBOUND_PROXY" != "<no value>" ]]; then
  export HTTP_PROXY="$HOST_OUTBOUND_PROXY" HTTPS_PROXY="$HOST_OUTBOUND_PROXY"
  export http_proxy="$HOST_OUTBOUND_PROXY" https_proxy="$HOST_OUTBOUND_PROXY"
  export NO_PROXY="localhost,127.0.0.1,${NO_PROXY:-}" no_proxy="localhost,127.0.0.1,${no_proxy:-}"
fi

export RESEARCH_SEARCH_PROVIDER="${RESEARCH_SEARCH_PROVIDER:-searxng}"
export RESEARCH_SEARXNG_URL="${RESEARCH_SEARXNG_URL:-http://127.0.0.1:$SEARXNG_PORT}"
export RESEARCH_SEARXNG_ENGINES="${RESEARCH_SEARXNG_ENGINES:-sogou,bing,baidu,brave,duckduckgo}"

if [[ -z "${FINFLOW_OFFICE_API_BASE_URL:-}" ]]; then
  OFFICE_API_HOST="host.docker.internal"
  if command -v colima >/dev/null 2>&1 && colima status >/dev/null 2>&1; then
    COLIMA_HOST="$(colima ssh -- ip route show default 2>/dev/null | awk 'NR == 1 { print $3 }')"
    [[ -n "$COLIMA_HOST" ]] && OFFICE_API_HOST="$COLIMA_HOST"
  fi
  FINFLOW_OFFICE_API_BASE_URL="http://$OFFICE_API_HOST:8080"
fi
export FINFLOW_OFFICE_API_BASE_URL

PYTHON_BIN="$ROOT/worker-python/.venv312/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then
  PYTHON_BIN="$ROOT/worker-python/.venv/bin/python"
fi
[[ -x "$PYTHON_BIN" ]] || { echo "Python environment is missing: worker-python/.venv312 or worker-python/.venv" >&2; exit 1; }
[[ -d "$ROOT/frontend/node_modules" ]] || { echo "Frontend dependencies are missing: run npm install in frontend" >&2; exit 1; }

start_service "Demo data API" "http://127.0.0.1:8011/health" \
  "$ROOT/examples/cases/demo-api" "$RUN_DIR/demo-api.pid" "$RUN_DIR/demo-api.log" \
  "$PYTHON_BIN" -m uvicorn app:app --host 127.0.0.1 --port 8011

start_service "Inventory demo API" "http://127.0.0.1:8010/health" \
  "$ROOT/demo-services" "$RUN_DIR/inventory-api.pid" "$RUN_DIR/inventory-api.log" \
  "$PYTHON_BIN" -m uvicorn inventory_api:app --host 127.0.0.1 --port 8010

start_service "Python worker" "http://127.0.0.1:8001/health" \
  "$ROOT/worker-python" "$RUN_DIR/python.pid" "$RUN_DIR/python.log" \
  "$PYTHON_BIN" -m uvicorn app.main:app --host 127.0.0.1 --port 8001

if [[ "${BROWSER_RUNNER_ENABLED:-false}" == "true" ]]; then
  export BROWSER_RUNNER_TOKEN="${BROWSER_RUNNER_TOKEN:-}"
  [[ ${#BROWSER_RUNNER_TOKEN} -ge 32 ]] || { echo "BROWSER_RUNNER_TOKEN must contain at least 32 characters" >&2; exit 1; }
  start_service "Browser Runner" "http://127.0.0.1:8002/health" \
    "$ROOT/worker-python" "$RUN_DIR/browser-runner.pid" "$RUN_DIR/browser-runner.log" \
    "$PYTHON_BIN" -m uvicorn app.browser_runner.app:app --host 127.0.0.1 --port 8002
  # A healthy process may still be running with an old credential.
  "$PYTHON_BIN" - <<'PY'
import json
import os
import urllib.request
import urllib.error

request = urllib.request.Request(
    os.environ.get("BROWSER_RUNNER_URL", "http://127.0.0.1:8002").rstrip("/") + "/sites",
    data=b"{}", headers={"Authorization": "Bearer " + os.environ["BROWSER_RUNNER_TOKEN"],
                          "Content-Type": "application/json"})
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        sites = json.load(response).get("sites", [])
    print(f"Browser Runner authorization verified ({len(sites)} authorized sites)")
except (urllib.error.URLError, ValueError):
    raise SystemExit("Browser Runner authorization check failed. Verify BROWSER_RUNNER_TOKEN and restart the runner; health alone is insufficient.")
PY
fi
if [[ "${BROWSER_DEMO_ENABLED:-false}" == "true" ]]; then
  start_service "Browser reporting demo" "http://127.0.0.1:8766/" \
    "$ROOT/examples/browser-report" "$RUN_DIR/browser-demo.pid" "$RUN_DIR/browser-demo.log" \
    "$PYTHON_BIN" -m uvicorn app:app --host 127.0.0.1 --port 8766
fi

if ! curl -fsS --max-time 2 "http://127.0.0.1:8080/actuator/health" >/dev/null 2>&1; then
  echo "Building Java API..."
  (cd "$ROOT/backend-java" && mvn -q -DskipTests package)
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
    echo $! >"$RUN_DIR/java-launcher.pid"
  )
  wait_for_url "Java API" "http://127.0.0.1:8080/actuator/health" 90
  java_pid="$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | head -n 1)"
  [[ -n "$java_pid" ]] || { echo "Java API is healthy but its process could not be identified." >&2; exit 1; }
  echo "$java_pid" >"$RUN_DIR/java.pid"
else
  echo "Java API is already running"
fi

start_service "Vue workbench" "http://127.0.0.1:5174/" \
  "$ROOT/frontend" "$RUN_DIR/frontend.pid" "$RUN_DIR/frontend.log" \
  npm run dev -- --host 127.0.0.1 --port 5174

echo
echo "FinBTP Studio is ready: http://127.0.0.1:5174/"
echo "Demo data API:          http://127.0.0.1:8011/health"
echo "Inventory demo API:     http://127.0.0.1:8010/health"
echo "ONLYOFFICE health:      http://127.0.0.1:8082/healthcheck"
echo "ONLYOFFICE file bridge: $FINFLOW_OFFICE_API_BASE_URL"
echo "Search service:         http://127.0.0.1:$SEARXNG_PORT/"

# Automation runners may clean up child processes when the launcher exits. Keeping the
# launcher attached is optional and does not change normal terminal usage.
if [[ "${1:-}" == "--keep-alive" ]]; then
  echo "Launcher is attached. Press Ctrl+C to stop the attached session."
  while true; do sleep 3600; done
fi
