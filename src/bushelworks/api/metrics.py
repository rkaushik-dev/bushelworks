"""Counters the box can be asked about.

Not a monitoring system. The office box has no scrape target and nobody is going
to install one, so this is a handful of counters in memory and one endpoint that
prints them. What it is for is the telephone call that starts "it has been slow
since lunchtime", which is otherwise unanswerable.

Everything resets when the process restarts, which is fine: the question is
always about today.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from time import monotonic

from fastapi import FastAPI, Request


@dataclass
class Metrics:
    """Request counters and a coarse latency picture."""

    started: float = field(default_factory=monotonic)
    requests: int = 0
    by_status: dict[int, int] = field(default_factory=lambda: defaultdict(int))
    by_path: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    total_ms: float = 0.0
    slowest_ms: float = 0.0
    slowest_path: str = ""

    def record(self, path: str, status: int, ms: float) -> None:
        self.requests += 1
        self.by_status[status] += 1
        self.by_path[path] += 1
        self.total_ms += ms
        # The first request sets the slowest even if it was too quick to
        # measure. Comparing on the number alone leaves the path blank on a
        # fast box, which reads as "nothing has been served".
        if not self.slowest_path or ms > self.slowest_ms:
            self.slowest_ms = ms
            self.slowest_path = path

    @property
    def uptime_seconds(self) -> int:
        return int(monotonic() - self.started)

    @property
    def average_ms(self) -> float:
        return round(self.total_ms / self.requests, 1) if self.requests else 0.0

    @property
    def errors(self) -> int:
        return sum(count for status, count in self.by_status.items() if status >= 500)

    @property
    def refusals(self) -> int:
        """Everything the house said no to. Not errors; often the point."""
        return sum(
            count for status, count in self.by_status.items() if 400 <= status < 500
        )

    def busiest_path(self) -> str:
        if not self.by_path:
            return ""
        return max(self.by_path, key=lambda path: self.by_path[path])

    def as_dict(self) -> dict[str, object]:
        return {
            "uptime_seconds": self.uptime_seconds,
            "requests": self.requests,
            "average_ms": self.average_ms,
            "slowest_ms": round(self.slowest_ms, 1),
            "slowest_path": self.slowest_path,
            "busiest_path": self.busiest_path(),
            "errors": self.errors,
            "refusals": self.refusals,
            "by_status": {str(status): count for status, count in sorted(self.by_status.items())},
        }


def install_metrics(app: FastAPI) -> Metrics:
    """Count every request, including the ones that failed."""
    metrics = Metrics()
    app.state.metrics = metrics

    @app.middleware("http")
    async def _count(request: Request, call_next):  # type: ignore[no-untyped-def]
        started = monotonic()
        try:
            response = await call_next(request)
        except Exception:
            metrics.record(request.url.path, 500, (monotonic() - started) * 1000)
            raise
        metrics.record(
            request.url.path, response.status_code, (monotonic() - started) * 1000
        )
        return response

    @app.get("/metrics", tags=["ops"])
    def read_metrics() -> dict[str, object]:
        """What this process has done since it started."""
        return metrics.as_dict()

    return metrics
