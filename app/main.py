import os

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.core.logging import configure_logging

configure_logging(os.getenv("LOG_LEVEL", "INFO"))

app = FastAPI(title="Pitsch AI Service", version="0.1.0")
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
