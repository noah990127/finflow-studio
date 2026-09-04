#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="$ROOT/worker-python/.venv312/bin/python"

if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Python 3.12 environment is missing: worker-python/.venv312" >&2
  echo "Create it with: python3.12 -m venv worker-python/.venv312" >&2
  exit 1
fi

echo "[1/4] Java tests"
(cd "$ROOT/backend-java" && mvn -q test)

echo "[2/4] Python tests"
(cd "$ROOT/worker-python" && "$PYTHON_BIN" -m pytest -q)

echo "[3/4] Frontend type check and production build"
(cd "$ROOT/frontend" && npm run build)

echo "[4/4] Patch whitespace check"
(cd "$ROOT" && git diff --check)

echo "FinFlow verification passed."
