"""A rate limit, for the one caller that needs one.

The scale house terminal and the office cannot overload anything. The producer
portal can: it is on the public internet and it polls, and a script left running
over a weekend has twice filled the log and once filled the disk.

A fixed window rather than a token bucket. The window is a minute, the counter
is in memory, and both of those are deliberate for a house that runs one
process on one box. Nothing here reaches for redis, because there is no redis
and there is not going to be one.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from time import monotonic

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

WINDOW_SECONDS = 60.0

# Paths that are never limited. A health check that gets a 429 takes the box
# out of the load balancer, which is the opposite of what a limit is for.
EXEMPT = ("/health", "/ready", "/metrics", "/openapi.json", "/docs", "/redoc")


@dataclass
class Window:
    """One caller's counter inside one window."""

    started: float
    hits: int = 0

    def expired(self, now: float, length: float = WINDOW_SECONDS) -> bool:
        return now - self.started >= length

    def reset(self, now: float) -> None:
        self.started = now
        self.hits = 0

    def remaining_seconds(self, now: float, length: float = WINDOW_SECONDS) -> int:
        return max(0, int(round(self.started + length - now)))


class Limiter:
    """Fixed window counters, keyed by whatever identifies the caller."""

    def __init__(self, per_minute: int, window: float = WINDOW_SECONDS) -> None:
        if per_minute < 1:
            raise ValueError("a rate limit has to allow at least one call")
        self.per_minute = per_minute
        self.window = window
        self._windows: dict[str, Window] = {}

    def check(self, key: str, now: float | None = None) -> tuple[bool, int, int]:
        """Count a call. Returns whether it is allowed, what is left and when.

        A new caller's window starts at the moment it is passed rather than at
        the wall clock. The two are the same in production and are not in a
        test, and the test is the one that catches the arithmetic.
        """
        moment = monotonic() if now is None else now
        counter = self._windows.get(key)
        if counter is None:
            counter = Window(started=moment)
            self._windows[key] = counter
        if counter.expired(moment, self.window):
            counter.reset(moment)
        counter.hits += 1
        allowed = counter.hits <= self.per_minute
        left = max(0, self.per_minute - counter.hits)
        return allowed, left, counter.remaining_seconds(moment, self.window)

    def forget(self, key: str) -> None:
        self._windows.pop(key, None)

    def tracked(self) -> int:
        return len(self._windows)


def caller_key(request: Request) -> str:
    """Who to count against.

    The API key when there is one, because that identifies the account rather
    than whatever address it happens to be behind. Otherwise the client
    address, which is the best available and is good enough for a portal.
    """
    key = request.headers.get("X-API-Key")
    if key:
        return f"key:{key[:8]}"
    client = request.client
    return f"ip:{client.host}" if client else "ip:unknown"


def install_throttle(app: FastAPI, per_minute: int) -> Limiter | None:
    """Add the limit, unless the house has turned it off with a zero."""
    if per_minute < 1:
        return None
    limiter = Limiter(per_minute)
    app.state.limiter = limiter

    @app.middleware("http")
    async def _throttle(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        if request.url.path in EXEMPT:
            return await call_next(request)

        allowed, left, resets_in = limiter.check(caller_key(request))
        if not allowed:
            return JSONResponse(
                status_code=429,
                content={
                    "code": "too_many_requests",
                    "detail": (
                        f"more than {per_minute} calls in a minute, "
                        f"try again in {resets_in} seconds"
                    ),
                },
                headers={
                    "Retry-After": str(resets_in),
                    "X-RateLimit-Limit": str(per_minute),
                    "X-RateLimit-Remaining": "0",
                },
            )
        response = await call_next(request)
        response.headers["X-RateLimit-Limit"] = str(per_minute)
        response.headers["X-RateLimit-Remaining"] = str(left)
        return response

    return limiter
