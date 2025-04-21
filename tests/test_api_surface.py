"""The published surface, and the two ways it goes wrong quietly.

The first is a route that changes shape. The second is a route that is shadowed
by one declared before it, which is worse: the path still exists, the client
still calls it, and the answer comes from somewhere else.
"""

import pytest
from fastapi.testclient import TestClient

from api_surface import OPEN_PATHS, ROUTES
from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

KEYS = frozenset({"k1:read:look", "k2:scale:weigh", "k3:office:office"})


def build(**settings):
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    return create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:", **settings),
        factory=session_factory(engine),
    )


@pytest.fixture
def app():
    return build()


@pytest.fixture
def locked():
    with TestClient(build(api_keys=KEYS)) as client:
        yield client


def surface(app) -> list[tuple[str, str]]:
    rows = []
    for route in app.routes:
        methods = getattr(route, "methods", None)
        if not methods:
            continue
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            rows.append((method, route.path))
    return sorted(rows, key=lambda row: (row[1], row[0]))


def test_the_surface_is_exactly_what_was_written_down(app):
    assert surface(app) == sorted(ROUTES, key=lambda row: (row[1], row[0]))


def test_nothing_new_has_appeared(app):
    assert set(surface(app)) - set(ROUTES) == set()


def test_nothing_has_gone_missing(app):
    assert set(ROUTES) - set(surface(app)) == set()


def test_no_route_is_listed_twice():
    assert len(ROUTES) == len(set(ROUTES))


def test_every_path_starts_at_the_root():
    assert all(path.startswith("/") for _, path in ROUTES)


def test_no_path_ends_in_a_slash():
    assert [path for _, path in ROUTES if path.endswith("/")] == []


def test_the_methods_are_ones_the_house_uses():
    assert {method for method, _ in ROUTES} <= {"GET", "POST"}


def declaration_order(app) -> list[tuple[str, str]]:
    """Method and path, in the order FastAPI will try them."""
    rows = []
    for route in app.routes:
        methods = getattr(route, "methods", None)
        if not methods:
            continue
        for method in sorted(methods - {"HEAD", "OPTIONS"}):
            rows.append((method, route.path))
    return rows


def test_no_literal_segment_is_shadowed_by_a_parameter(app):
    """The failure that has bitten twice.

    ``/ops/audit/{document_kind}/{document_number}`` used to swallow
    ``/ops/audit/party/X``, and ``/bins/{location_code}/{bin_id}`` swallowed
    ``/bins/HOME/summary``. Both were declared first and both matched anything.

    A parameter segment matches every literal at the same depth, so a literal
    route declared after one is unreachable. This walks the real declaration
    order and says which pair is the problem.
    """
    seen: list[tuple[str, list[str]]] = []
    for method, path in declaration_order(app):
        parts = path.strip("/").split("/")
        for earlier_method, earlier in seen:
            if earlier_method != method or len(earlier) != len(parts):
                continue
            if earlier == parts:
                continue
            if all(
                before == now or before.startswith("{")
                for before, now in zip(earlier, parts, strict=True)
            ):
                pytest.fail(
                    f"{method} /{'/'.join(parts)} is shadowed by "
                    f"{method} /{'/'.join(earlier)}"
                )
        seen.append((method, parts))


def test_the_open_paths_answer_without_a_key(locked):
    for path in OPEN_PATHS:
        assert locked.get(path).status_code < 400, path


def test_everything_else_wants_a_key(locked):
    closed = [path for _, path in ROUTES if path not in OPEN_PATHS]
    plain = [path for path in closed if "{" not in path]
    assert plain
    for path in plain:
        assert locked.get(path).status_code in (401, 403, 405), path


def test_a_read_key_gets_into_the_read_only_places(locked):
    """The position board is the one thing a read key is for."""
    response = locked.get("/positions/2024-11-01", headers={"X-API-Key": "look"})
    assert response.status_code == 200


def test_a_read_key_is_kept_out_of_the_office(locked):
    for path in ("/complaints", "/contracts", "/tickets"):
        assert locked.get(path, headers={"X-API-Key": "look"}).status_code == 403


def test_an_office_key_gets_in_everywhere_a_read_key_does(locked):
    response = locked.get("/positions/2024-11-01", headers={"X-API-Key": "office"})
    assert response.status_code == 200


def test_an_unknown_key_gets_nowhere(locked):
    response = locked.get("/positions/2024-11-01", headers={"X-API-Key": "guess"})
    assert response.status_code == 401


def test_the_schema_describes_every_route(app):
    schema = app.openapi()
    described = {
        (method.upper(), path)
        for path, operations in schema["paths"].items()
        for method in operations
    }
    listed = {row for row in ROUTES if row[1] not in OPEN_PATHS}
    assert listed <= described


def test_the_schema_names_the_house(app):
    assert app.openapi()["info"]["title"]


def test_every_router_prefix_appears_on_the_surface():
    prefixes = {
        "/tickets",
        "/contracts",
        "/settlements",
        "/storage",
        "/positions",
        "/sales",
        "/parties",
        "/ops",
        "/deferred",
        "/bins",
        "/desk",
        "/reports",
        "/complaints",
    }
    for prefix in prefixes:
        assert any(path.startswith(prefix) for _, path in ROUTES), prefix
