#!/usr/bin/env bash
# Starts the Pitsch AI service, backend and frontend (Ctrl+C stops all three).
# Needs: Python 3.10+, Java 21 (JDK) and Node.js 20+.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d ai-service/.venv ]; then
  echo "Setting up the AI service (first run only)..."
  python3 -m venv ai-service/.venv
  ai-service/.venv/bin/pip install -r ai-service/requirements.txt
fi
if [ ! -d frontend/node_modules ]; then
  echo "Installing frontend packages (first run only)..."
  (cd frontend && npm install)
fi

trap 'kill 0' EXIT
(cd ai-service && .venv/bin/python -m uvicorn app.main:app --port 8000) &
(cd backend && ./mvnw spring-boot:run) &
(cd frontend && npm run dev) &
echo
echo "Starting... then open http://localhost:5173 (demo@pitsch.com / pitsch123)"
wait
