import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.api.routes import router
from app.config import get_settings
from app.core.logging import configure_logging

configure_logging(os.getenv("LOG_LEVEL", "INFO"))


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Fail fast at startup if config is missing, instead of starting "healthy" and then
    # returning a bare HTTP 500 on every agent call.
    try:
        get_settings()
    except ValidationError as exc:
        names = ", ".join(str(e["loc"][0]).upper() for e in exc.errors())
        raise RuntimeError(
            f"Missing or invalid configuration: {names}. Copy .env.example to .env and fill it in."
        ) from None
    yield


app = FastAPI(title="Pitsch AI Service", version="0.2.0", lifespan=lifespan,
              description="AI agents for Pitsch. Called only by the Spring Boot backend.")
app.include_router(router)


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
def health() -> dict:
    return {"status": "ok"}
