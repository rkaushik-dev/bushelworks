"""Application factory.

A factory rather than a module level ``app`` so the tests can build one against
an in memory database without touching the environment, and so two houses can
run in one process during a merger, which has already happened once.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from time import perf_counter
from typing import Any

from fastapi import FastAPI, Request
from sqlalchemy.orm import Session, sessionmaker

from ..config import Settings, load_settings
from ..logging import configure, event, failure, get_logger, set_request_id
from ..persistence.base import create_all, engine_for, session_factory
from .errors import install_error_handlers

API_TITLE = "bushelworks"
API_DESCRIPTION = (
    "Scale house capture and settlement for country grain elevators. "
    "Every quantity is exact decimal and every rate is resolved as of the "
    "ticket date rather than today."
)


def create_app(
    settings: Settings | None = None,
    factory: sessionmaker[Session] | None = None,
    create_schema: bool = False,
) -> FastAPI:
    resolved = settings or load_settings()
    configure(resolved.log_level)

    if factory is None:
        engine = engine_for(resolved.database_url, echo=resolved.echo_sql)
        if create_schema:
            create_all(engine)
        factory = session_factory(engine)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        factory.kw["bind"].dispose()

    app = FastAPI(
        title=API_TITLE,
        description=API_DESCRIPTION,
        version=_version(),
        lifespan=lifespan,
    )
    app.state.settings = resolved
    app.state.session_factory = factory

    install_error_handlers(app)
    _install_request_id(app)
    _install_health(app)

    from .routers import (
        contracts_router,
        parties_router,
        positions_router,
        sales_router,
        settlements_router,
        storage_router,
        tickets_router,
    )

    app.include_router(tickets_router)
    app.include_router(contracts_router)
    app.include_router(settlements_router)
    app.include_router(storage_router)
    app.include_router(positions_router)
    app.include_router(sales_router)
    app.include_router(parties_router)
    return app


def _version() -> str:
    from .. import __version__

    return __version__


def _install_request_id(app: FastAPI) -> None:
    """Stamp every request so a log line and an error body can be tied together.

    The scale house calls with a problem and reads the id off the screen. It is
    the only thing that makes an error report actionable at harvest, when there
    are nine hundred requests an hour and they all look the same.

    The same id goes into the logging context, so every line a request writes
    carries it without any of the routes having to pass it down.
    """
    logger = get_logger("api")

    @app.middleware("http")
    async def _stamp(request: Request, call_next: Any) -> Any:
        incoming = request.headers.get("x-request-id")
        request_id = incoming or uuid.uuid4().hex[:16]
        request.state.request_id = request_id
        set_request_id(request_id)
        started = perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            failure(
                logger,
                "request.failed",
                method=request.method,
                path=request.url.path,
                ms=round((perf_counter() - started) * 1000),
            )
            raise
        finally:
            set_request_id(None)
        response.headers["x-request-id"] = request_id
        event(
            logger,
            "request.served",
            method=request.method,
            path=request.url.path,
            status=response.status_code,
            ms=round((perf_counter() - started) * 1000),
            request_id=request_id,
        )
        return response


def _install_health(app: FastAPI) -> None:
    @app.get("/health", tags=["ops"])
    def health() -> dict[str, str]:
        """Liveness. Says nothing about the database on purpose."""
        return {"status": "ok", "version": app.version}

    @app.get("/ready", tags=["ops"])
    def ready() -> dict[str, str]:
        """Readiness. Opens a session and runs the cheapest statement there is."""
        from sqlalchemy import text

        factory: sessionmaker[Session] = app.state.session_factory
        with factory() as session:
            session.execute(text("SELECT 1"))
        return {"status": "ready"}
