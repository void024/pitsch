@echo off
REM Starts the Pitsch AI service, backend and frontend, each in its own window.
REM Needs: Python 3.10+, Java 21 (JDK) and Node.js 20+ on PATH.
cd /d "%~dp0"

if not exist "ai-service\.venv" (
  echo Setting up the AI service - first run only...
  python -m venv "ai-service\.venv"
  if errorlevel 1 goto nopython
  "ai-service\.venv\Scripts\python" -m pip install -r "ai-service\requirements.txt"
)

if not exist "frontend\node_modules" (
  echo Installing frontend packages - first run only...
  pushd frontend
  call npm install
  popd
)

start "Pitsch AI service :8000" /D "%~dp0ai-service" cmd /k .venv\Scripts\python -m uvicorn app.main:app --port 8000
start "Pitsch backend :8080" /D "%~dp0backend" cmd /k mvnw.cmd spring-boot:run
start "Pitsch frontend :5173" /D "%~dp0frontend" cmd /k npm run dev

echo.
echo Starting... the backend takes about a minute the first time (it downloads its libraries).
echo Then open http://localhost:5173 and log in with demo@pitsch.com / pitsch123
goto :eof

:nopython
echo Python was not found. Install Python 3.10+ from https://www.python.org and try again.
