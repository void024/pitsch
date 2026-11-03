@echo off
REM Local development without Docker: AI service (:8000), backend (:8080), frontend (:5173), each in its own window.
REM Needs Python 3.11+, Java 21 (JDK) and Node.js 20.19+ on PATH. Put your LLM key in ai-service\.env first.
REM Mode: demo (default here: mock Google providers + demo workspace) or development. Set PITSCH_MODE to override.
cd /d "%~dp0"
if "%PITSCH_MODE%"=="" set PITSCH_MODE=demo

if not exist "ai-service\.env" (
  echo ai-service\.env is missing: copy ai-service\.env.example and add your LLM key.
  goto :eof
)
if not exist "ai-service\.venv" (
  echo Setting up the AI service - first run only...
  python -m venv "ai-service\.venv"
  if errorlevel 1 goto nopython
  "ai-service\.venv\Scripts\python" -m pip install -r "ai-service\requirements.txt"
)
if not exist "frontend\node_modules" (
  echo Installing frontend packages - first run only...
  pushd frontend
  call npm ci
  popd
)

set VITE_DEMO_MODE=false
if "%PITSCH_MODE%"=="demo" set VITE_DEMO_MODE=true
start "Pitsch AI service :8000" /D "%~dp0ai-service" cmd /k "set PITSCH_MODE=development&& .venv\Scripts\python -m uvicorn app.main:app --port 8000"
start "Pitsch backend :8080" /D "%~dp0backend" cmd /k mvnw.cmd spring-boot:run
start "Pitsch frontend :5173" /D "%~dp0frontend" cmd /k npm run dev

echo.
echo Starting... the backend takes about a minute the first time (it downloads its libraries).
echo Then open http://localhost:5173 - demo mode: demo@pitsch.app / pitsch-demo-2026 (or your DEMO_PASSWORD)
goto :eof

:nopython
echo Python was not found. Install Python 3.11+ from https://www.python.org and try again.
