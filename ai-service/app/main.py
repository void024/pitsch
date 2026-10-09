import logging
import os
import re
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.routes import router
from app.config import get_service_settings, get_settings
from app.core.logging import configure_logging, request_id_var

configure_logging(os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("app")
_SAFE_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


def _config_error(exc: ValidationError) -> RuntimeError:
    names = ", ".join(str(e["loc"][0]).upper() if e["loc"] else e["msg"] for e in exc.errors())
    return RuntimeError(f"Missing or invalid configuration: {names}. Copy .env.example to .env and fill it in.")


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Fail fast at startup if config is missing, instead of starting "healthy" and then
    # returning a bare HTTP 500 on every agent call.
    try:
        service = get_service_settings()
        get_settings()
    except ValidationError as exc:
        raise _config_error(exc) from None
    if not service.ai_service_token:
        logger.warning("AI_SERVICE_TOKEN is not set: agent endpoints accept unauthenticated calls "
                       "(allowed only outside production)")
    yield


def _service_settings_or_default():
    # The docs switch is evaluated at import time; a configuration error is reported by lifespan instead.
    try:
        return get_service_settings()
    except ValidationError:
        return None


_service = _service_settings_or_default()
_docs = bool(_service and _service.docs_enabled)

app = FastAPI(title="Pitsch AI Service", version="1.0.0", lifespan=lifespan,
              description="AI agents for Pitsch. Called only by the Spring Boot backend (X-Internal-Token).",
              docs_url="/docs" if _docs else None, redoc_url=None, openapi_url="/openapi.json" if _docs else None)
app.include_router(router)


@app.middleware("http")
async def request_context(request: Request, call_next):
    """Correlates logs with the backend's request (X-Request-Id) and refuses oversized bodies early."""
    incoming = request.headers.get("x-request-id", "")
    rid = incoming if _SAFE_REQUEST_ID.match(incoming) else uuid.uuid4().hex
    token = request_id_var.set(rid)
    try:
        limit = (_service.max_request_mb if _service else 30.0) * 1024 * 1024
        length = request.headers.get("content-length")
        if length and length.isdigit() and int(length) > limit:
            return JSONResponse(status_code=413, content={
                "success": False, "agent": None, "data": None, "meta": None,
                "error": {"code": "INVALID_INPUT", "message": "Request body too large", "retryable": False}})
        response = await call_next(request)
        response.headers["X-Request-Id"] = rid
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        return response
    finally:
        request_id_var.reset(token)


@app.exception_handler(RequestValidationError)
async def invalid_input_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    # Report where validation failed, never the input values (they may contain email content).
    details = [{"loc": list(e["loc"]), "msg": e["msg"]} for e in exc.errors()]
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "agent": None,
            "data": None,
            "error": {"code": "INVALID_INPUT", "message": "Request failed validation",
                      "retryable": False, "details": details},
            "meta": None,
        },
    )


@app.get("/health")
@app.get("/health/live")
def health() -> dict:
    return {"status": "ok"}


@app.get("/health/ready")
def ready() -> JSONResponse:
    """Ready when configuration is valid (the LLM is not called: probes must be cheap and free)."""
    try:
        get_service_settings()
        get_settings()
    except ValidationError:
        return JSONResponse(status_code=503, content={"status": "unavailable", "reason": "configuration"})
    return JSONResponse(status_code=200, content={"status": "ok"})
