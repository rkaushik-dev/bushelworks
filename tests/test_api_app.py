import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.api.errors import status_for
from bushelworks.config import Settings
from bushelworks.errors import (
    BushelworksError,
    ConfigurationError,
    ConflictError,
    LookupFailure,
    ValidationError,
)
from bushelworks.persistence.base import create_all, engine_for, session_factory


@pytest.fixture
def client():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=session_factory(engine),
    )
    _install_test_routes(app)
    with TestClient(app) as test_client:
        yield test_client


def _install_test_routes(app):
    router = APIRouter(prefix="/_test")

    @router.get("/validation")
    def _validation():
        raise ValidationError("tare exceeds gross", code="tare_exceeds_gross")

    @router.get("/missing")
    def _missing():
        raise LookupFailure("no ticket A-99999 at HOME")

    @router.get("/conflict")
    def _conflict():
        raise ConflictError("ticket A-10441 already written at HOME")

    @router.get("/misconfigured")
    def _misconfigured():
        raise ConfigurationError("no shrink table filed")

    @router.get("/plain")
    def _plain():
        raise BushelworksError("something the office has not seen before")

    app.include_router(router)


def test_health_answers_without_touching_the_database(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert response.json()["version"]


def test_ready_opens_a_session(client):
    response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_every_response_carries_a_request_id(client):
    response = client.get("/health")
    assert len(response.headers["x-request-id"]) == 16


def test_an_incoming_request_id_is_kept(client):
    response = client.get("/health", headers={"x-request-id": "abc123"})
    assert response.headers["x-request-id"] == "abc123"


def test_a_validation_error_is_a_422_with_its_code(client):
    response = client.get("/_test/validation")
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "tare_exceeds_gross"
    assert body["detail"] == "tare exceeds gross"
    assert body["request_id"] == response.headers["x-request-id"]


def test_a_lookup_failure_is_a_404(client):
    response = client.get("/_test/missing")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_a_conflict_is_a_409(client):
    response = client.get("/_test/conflict")
    assert response.status_code == 409
    assert response.json()["code"] == "conflict"


def test_a_configuration_error_is_a_500(client):
    response = client.get("/_test/misconfigured")
    assert response.status_code == 500
    assert response.json()["code"] == "configuration_error"


def test_an_unclassified_domain_error_is_a_400(client):
    response = client.get("/_test/plain")
    assert response.status_code == 400
    assert response.json()["code"] == "bushelworks_error"


def test_the_most_specific_class_decides_the_status():
    assert status_for(LookupFailure("x")) == 404
    assert status_for(ValidationError("x")) == 422
    assert status_for(ConflictError("x")) == 409
    assert status_for(BushelworksError("x")) == 400


def test_the_openapi_document_describes_the_service(client):
    document = client.get("/openapi.json").json()
    assert document["info"]["title"] == "bushelworks"
    assert "country grain elevators" in document["info"]["description"]


def test_the_app_carries_its_settings(client):
    assert client.app.state.settings.state_code == "KS"
