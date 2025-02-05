import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.api.metrics import Metrics
from bushelworks.api.throttle import EXEMPT
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory


@pytest.fixture
def client():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=session_factory(engine),
    )
    with TestClient(app) as test_client:
        yield test_client


def test_a_new_counter_has_seen_nothing():
    metrics = Metrics()
    assert metrics.requests == 0
    assert metrics.average_ms == 0.0
    assert metrics.busiest_path() == ""


def test_recording_counts_the_request():
    metrics = Metrics()
    metrics.record("/tickets", 201, 12.0)
    assert metrics.requests == 1
    assert metrics.by_status[201] == 1
    assert metrics.by_path["/tickets"] == 1


def test_the_average_is_over_the_requests():
    metrics = Metrics()
    metrics.record("/a", 200, 10.0)
    metrics.record("/a", 200, 20.0)
    assert metrics.average_ms == 15.0


def test_the_slowest_request_is_remembered():
    metrics = Metrics()
    metrics.record("/a", 200, 10.0)
    metrics.record("/settlements", 201, 90.0)
    metrics.record("/b", 200, 20.0)
    assert metrics.slowest_ms == 90.0
    assert metrics.slowest_path == "/settlements"


def test_the_busiest_path_is_the_one_called_most():
    metrics = Metrics()
    for _ in range(3):
        metrics.record("/tickets", 201, 5.0)
    metrics.record("/positions", 200, 5.0)
    assert metrics.busiest_path() == "/tickets"


def test_server_errors_are_counted_separately():
    metrics = Metrics()
    metrics.record("/a", 500, 5.0)
    metrics.record("/a", 200, 5.0)
    assert metrics.errors == 1
    assert metrics.refusals == 0


def test_refusals_are_not_errors():
    metrics = Metrics()
    metrics.record("/a", 409, 5.0)
    metrics.record("/a", 422, 5.0)
    assert metrics.refusals == 2
    assert metrics.errors == 0


def test_the_uptime_is_counted_in_seconds():
    assert Metrics().uptime_seconds >= 0


def test_the_endpoint_reports_what_has_happened(client):
    client.get("/health")
    body = client.get("/metrics").json()
    assert body["requests"] >= 1
    assert "by_status" in body
    assert body["by_status"]["200"] >= 1


def test_the_endpoint_counts_itself(client):
    first = client.get("/metrics").json()["requests"]
    second = client.get("/metrics").json()["requests"]
    assert second > first


def test_a_refused_call_is_counted_as_a_refusal(client):
    client.get("/tickets/HOME/A-99999")
    body = client.get("/metrics").json()
    assert body["refusals"] >= 1
    assert body["by_status"]["404"] >= 1


def test_the_busiest_path_shows_up_in_the_body(client):
    for _ in range(3):
        client.get("/health")
    body = client.get("/metrics").json()
    assert body["busiest_path"] == "/health"


def test_the_slowest_path_shows_up_in_the_body(client):
    client.get("/health")
    body = client.get("/metrics").json()
    assert body["slowest_path"]
    assert body["slowest_ms"] >= 0


def test_metrics_is_never_rate_limited():
    assert "/metrics" in EXEMPT


def test_the_metrics_route_is_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/metrics" in paths
