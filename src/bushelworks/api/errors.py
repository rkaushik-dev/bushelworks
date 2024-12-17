"""One error shape for every failure the API can return.

The touch screen in the scale house shows ``detail`` to whoever is standing at
the window, so it has to be a sentence rather than a stack frame. The
spreadsheet keys on ``code``, so the code has to be stable even when the
sentence is reworded.

The mapping from domain error to status is deliberate and short:

* a validation error is a 422, because the request was understood and refused
* a lookup failure is a 404
* a conflict is a 409
* a configuration error is a 500, because it is the house's fault and not the
  caller's
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ..errors import (
    BushelworksError,
    ConfigurationError,
    ConflictError,
    LookupFailure,
    ValidationError,
)


def _security_errors() -> tuple[type[BushelworksError], type[BushelworksError]]:
    from .security import Forbidden, NotAuthorised

    return NotAuthorised, Forbidden


_NOT_AUTHORISED, _FORBIDDEN = _security_errors()

STATUS_FOR: list[tuple[type[BushelworksError], int]] = [
    (_NOT_AUTHORISED, 401),
    (_FORBIDDEN, 403),
    (LookupFailure, 404),
    (ConflictError, 409),
    (ValidationError, 422),
    (ConfigurationError, 500),
]


def status_for(error: BushelworksError) -> int:
    """The status this error should come back as.

    Ordered rather than a dict because the classes overlap: a ``LookupFailure``
    is also a ``BushelworksError`` and the most specific match has to win.
    """
    for kind, status in STATUS_FOR:
        if isinstance(error, kind):
            return status
    return 400


def problem(error: BushelworksError, request_id: str | None = None) -> dict[str, object]:
    body: dict[str, object] = {"code": error.code, "detail": error.message}
    if request_id:
        body["request_id"] = request_id
    return body


def field_path(location: Sequence[Any]) -> str:
    """Turn pydantic's location tuple into something a person can read.

    ``('body', 'splits', 0, 'percent')`` becomes ``splits.0.percent``. The
    leading ``body`` or ``query`` is dropped: the caller knows which it sent.
    """
    parts = [str(part) for part in location]
    if parts and parts[0] in ("body", "query", "path", "header"):
        parts = parts[1:]
    return ".".join(parts) or "body"


def validation_problem(
    exc: RequestValidationError, request_id: str | None = None
) -> dict[str, Any]:
    """A rejected request body, in the same shape as every other refusal.

    FastAPI's own 422 body is a list under ``detail`` with pydantic's internal
    keys in it. The scale house terminal shows ``detail`` to whoever is standing
    at the window, so it has to be a sentence, and the field by field breakdown
    goes in a separate key for the caller that wants it.
    """
    errors = exc.errors()
    fields = [
        {"field": field_path(item.get("loc", ())), "detail": item.get("msg", "")}
        for item in errors
    ]
    if len(fields) == 1:
        detail = f"{fields[0]['field']}: {fields[0]['detail']}"
    else:
        named = ", ".join(item["field"] for item in fields)
        detail = f"{len(fields)} fields were refused: {named}"

    body: dict[str, Any] = {
        "code": "invalid_request",
        "detail": detail,
        "fields": fields,
    }
    if request_id:
        body["request_id"] = request_id
    return body


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(BushelworksError)
    async def _handle(request: Request, exc: BushelworksError) -> JSONResponse:
        request_id = getattr(request.state, "request_id", None)
        return JSONResponse(
            status_code=status_for(exc),
            content=problem(exc, request_id),
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_validation(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        request_id = getattr(request.state, "request_id", None)
        return JSONResponse(
            status_code=422,
            content=validation_problem(exc, request_id),
        )
