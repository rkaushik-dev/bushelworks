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

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from ..errors import (
    BushelworksError,
    ConfigurationError,
    ConflictError,
    LookupFailure,
    ValidationError,
)

def _not_authorised() -> type[BushelworksError]:
    from .deps import NotAuthorised

    return NotAuthorised


STATUS_FOR: list[tuple[type[BushelworksError], int]] = [
    (_not_authorised(), 401),
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


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(BushelworksError)
    async def _handle(request: Request, exc: BushelworksError) -> JSONResponse:
        request_id = getattr(request.state, "request_id", None)
        return JSONResponse(
            status_code=status_for(exc),
            content=problem(exc, request_id),
        )
