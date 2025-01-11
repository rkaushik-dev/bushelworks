import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.api.throttle import EXEMPT, WINDOW_SECONDS, Limiter, Window
from bushelworks.config import Settings
from bushelworks.errors import ConfigurationError
from bushelworks.persistence.base import create_all, engine_for, session_factory


def build(settings: Settings) -> TestClient:
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    return TestClient(create_app(settings=settings, factory=session_factory(engine)))


@pytest.fixture
def open_house():
    with build(Settings(database_url="sqlite+pysqlite:///:memory:")) as client:
        yield client


@pytest.fixture
def limited():
    with build(
        Settings(
            database_url="sqlite+pysqlite:///:memory:", rate_limit_per_minute=3
        )
    ) as client:
        yield client


def test_a_limit_below_one_is_refused():
    with pytest.raises(ValueError):
        Limiter(0)


def test_a_negative_limit_is_refused_in_settings():
    with pytest.raises(ConfigurationError):
        Settings(rate_limit_per_minute=-1)


def test_the_limit_is_off_by_default():
    assert Settings().rate_limit_per_minute == 0


def test_the_limit_reads_from_the_environment(monkeypatch):
    from bushelworks.config import load_settings

    monkeypatch.setenv("BUSHELWORKS_RATE_LIMIT_PER_MINUTE", "120")
    assert load_settings().rate_limit_per_minute == 120


def test_a_window_expires_after_its_length():
    window = Window(started=0.0)
    assert not window.expired(30.0)
    assert window.expired(WINDOW_SECONDS)


def test_a_window_reports_how_long_is_left():
    window = Window(started=0.0)
    assert window.remaining_seconds(20.0) == 40


def test_the_limiter_counts_inside_the_window():
    limiter = Limiter(3)
    for expected_left in (2, 1, 0):
        allowed, left, _ = limiter.check("a", now=0.0)
        assert allowed
        assert left == expected_left


def test_the_limiter_refuses_past_the_limit():
    limiter = Limiter(2)
    limiter.check("a", now=0.0)
    limiter.check("a", now=0.0)
    allowed, left, resets_in = limiter.check("a", now=0.0)
    assert not allowed
    assert left == 0
    assert resets_in == 60


def test_a_new_window_starts_the_count_again():
    limiter = Limiter(1)
    assert limiter.check("a", now=0.0)[0]
    assert not limiter.check("a", now=10.0)[0]
    assert limiter.check("a", now=70.0)[0]


def test_callers_are_counted_separately():
    limiter = Limiter(1)
    assert limiter.check("a", now=0.0)[0]
    assert limiter.check("b", now=0.0)[0]
    assert limiter.tracked() == 2


def test_a_caller_can_be_forgotten():
    limiter = Limiter(1)
    limiter.check("a", now=0.0)
    limiter.forget("a")
    assert limiter.tracked() == 0


def test_an_unlimited_house_adds_no_headers(open_house):
    response = open_house.get("/positions/2024-11-01")
    assert response.status_code == 200
    assert "X-RateLimit-Limit" not in response.headers


def test_a_limited_house_reports_what_is_left(limited):
    response = limited.get("/positions/2024-11-01")
    assert response.headers["X-RateLimit-Limit"] == "3"
    assert response.headers["X-RateLimit-Remaining"] == "2"


def test_going_past_the_limit_is_a_429(limited):
    for _ in range(3):
        limited.get("/positions/2024-11-01")
    response = limited.get("/positions/2024-11-01")
    assert response.status_code == 429
    assert response.json()["code"] == "too_many_requests"
    assert response.headers["Retry-After"]


def test_the_refusal_says_what_the_limit_is(limited):
    for _ in range(4):
        response = limited.get("/positions/2024-11-01")
    assert "more than 3 calls in a minute" in response.json()["detail"]


def test_health_is_never_limited(limited):
    for _ in range(10):
        assert limited.get("/health").status_code == 200


def test_the_exempt_paths_are_the_ones_a_load_balancer_calls():
    assert "/health" in EXEMPT
    assert "/ready" in EXEMPT
