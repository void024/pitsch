#!/usr/bin/env bash
# Local development without Docker: AI service (:8000), backend (:8080) and frontend (:5173). Ctrl+C stops all.
# Needs Python 3.11+, Java 21 (JDK) and Node.js 20.19+. Uses H2 and local files unless backend/.env says otherwise.
# Mode: PITSCH_MODE=demo (default here: mock Google providers + demo workspace) or development (no mocks).
# The agents call a real LLM: put LLM_PROVIDER / LLM_MODEL / LLM_API_KEY in ai-service/.env first.
set -euo pipefail
cd "$(dirname "$0")"
export PITSCH_MODE="${PITSCH_MODE:-demo}"

if [ ! -f ai-service/.env ]; then
  echo "ai-service/.env is missing: copy ai-service/.env.example and add your LLM key." >&2
  exit 1
fi
if [ ! -d ai-service/.venv ]; then
  echo "Setting up the AI service (first run only)..."
  python3 -m venv ai-service/.venv
  ai-service/.venv/bin/pip install -r ai-service/requirements.txt
fi
if [ ! -d frontend/node_modules ]; then
  echo "Installing frontend packages (first run only)..."
  (cd frontend && npm ci)
fi

AI_MODE=development
trap 'kill 0' EXIT
(cd ai-service && PITSCH_MODE=$AI_MODE .venv/bin/python -m uvicorn app.main:app --port 8000) &
(cd backend && ./mvnw -q spring-boot:run) &
(cd frontend && VITE_DEMO_MODE=$([ "$PITSCH_MODE" = demo ] && echo true || echo false) npm run dev) &
echo
if [ "$PITSCH_MODE" = demo ]; then
  echo "Starting... open http://localhost:5173 and sign in as demo@pitsch.app (password: DEMO_PASSWORD or pitsch-demo-2026)"
else
  echo "Starting... open http://localhost:5173 and create an account (verification emails are printed in the backend log)"
fi
wait
