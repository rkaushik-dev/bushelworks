import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
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


def a_body(**overrides) -> dict:
    body = {
        "ticket_number": "A-10441",
        "location_code": "HOME",
        "scale_date": "2024-10-03",
        "commodity_code": "CORN",
        "party_code": "MERRICK",
        "gross_pounds": "78420",
        "tare_pounds": "26180",
        "disposition": "cash_sale",
        "truck_id": "T-14",
        "splits": [{"party_code": "MERRICK", "percent": "100"}],
        "sample": [
            {"factor": "moisture", "value": "18.2"},
            {"factor": "test_weight", "value": "56.5"},
            {"factor": "bcfm", "value": "1.2"},
            {"factor": "damage_total", "value": "1.0"},
        ],
    }
    body.update(overrides)
    return body


def test_recording_a_load_returns_the_graded_numbers(client):
    response = client.post("/tickets", json=a_body())
    assert response.status_code == 201
    body = response.json()
    assert body["clean_bushels"] == "932.8571"
    assert body["dry_bushels"] == "897.6324"
    assert body["shrink_percent"] == "3.7760"
    assert body["grade"]["grade"] == "1"


def test_the_location_header_points_at_the_ticket(client):
    response = client.post("/tickets", json=a_body())
    assert response.headers["Location"] == "/tickets/HOME/A-10441"


def test_the_ticket_reads_back(client):
    client.post("/tickets", json=a_body())
    response = client.get("/tickets/HOME/A-10441")
    assert response.status_code == 200
    body = response.json()
    assert body["net_pounds"] == "52240"
    assert body["gross_pounds"] == "78420"
    assert len(body["sample"]) == 4
    assert body["splits"][0]["party_code"] == "MERRICK"


def test_a_missing_ticket_is_a_404(client):
    response = client.get("/tickets/HOME/A-99999")
    assert response.status_code == 404
    assert response.json()["code"] == "ticket_not_found"


def test_the_same_ticket_twice_is_a_409(client):
    client.post("/tickets", json=a_body())
    response = client.post("/tickets", json=a_body())
    assert response.status_code == 409
    assert response.json()["code"] == "conflict"


def test_a_tare_above_gross_is_a_422(client):
    response = client.post("/tickets", json=a_body(gross_pounds="1000"))
    assert response.status_code == 422


def test_a_commodity_the_house_does_not_buy_is_refused(client):
    response = client.post(
        "/tickets",
        json=a_body(
            commodity_code="MILO",
            sample=[
                {"factor": "moisture", "value": "13.0"},
                {"factor": "test_weight", "value": "57.0"},
                {"factor": "foreign_material", "value": "1.0"},
                {"factor": "damage_total", "value": "1.0"},
            ],
        ),
    )
    assert response.status_code == 422
    assert response.json()["code"] == "intake_error"


def test_a_sample_missing_a_required_factor_is_refused(client):
    response = client.post(
        "/tickets",
        json=a_body(sample=[{"factor": "moisture", "value": "18.2"}]),
    )
    assert response.status_code == 422


def test_regrading_a_stored_ticket(client):
    client.post("/tickets", json=a_body())
    response = client.get("/tickets/HOME/A-10441/grade")
    assert response.status_code == 200
    assert response.json()["dry_bushels"] == "897.6324"


def test_voiding_keeps_the_weights_and_records_the_reason(client):
    client.post("/tickets", json=a_body())
    response = client.post(
        "/tickets/HOME/A-10441/void", params={"reason": "reweighed"}
    )
    assert response.status_code == 200
    assert response.json()["voided"] is True
    assert response.json()["net_pounds"] == "52240"


def test_voiding_twice_is_a_409(client):
    client.post("/tickets", json=a_body())
    client.post("/tickets/HOME/A-10441/void", params={"reason": "reweighed"})
    response = client.post("/tickets/HOME/A-10441/void", params={"reason": "again"})
    assert response.status_code == 409


def test_a_void_needs_a_reason(client):
    client.post("/tickets", json=a_body())
    response = client.post("/tickets/HOME/A-10441/void", params={"reason": ""})
    assert response.status_code == 422


def test_listing_unsettled_tickets(client):
    client.post("/tickets", json=a_body())
    client.post("/tickets", json=a_body(ticket_number="A-10442"))
    response = client.get("/tickets", params={"unsettled_only": True})
    body = response.json()
    assert body["total"] == 2
    assert [item["ticket_number"] for item in body["items"]] == ["A-10441", "A-10442"]


def test_a_voided_ticket_drops_off_the_unsettled_list(client):
    client.post("/tickets", json=a_body())
    client.post("/tickets", json=a_body(ticket_number="A-10442"))
    client.post("/tickets/HOME/A-10442/void", params={"reason": "reweighed"})
    response = client.get("/tickets", params={"unsettled_only": True})
    assert response.json()["total"] == 1


def test_listing_by_party(client):
    client.post("/tickets", json=a_body())
    client.post("/tickets", json=a_body(ticket_number="A-10442", party_code="HOLLIS"))
    response = client.get("/tickets", params={"party_code": "HOLLIS"})
    assert response.json()["total"] == 1


def test_the_page_size_is_clamped_to_the_house_maximum(client):
    client.post("/tickets", json=a_body())
    response = client.get("/tickets", params={"limit": 100000})
    assert response.json()["limit"] == 200


def test_a_negative_offset_is_treated_as_zero(client):
    client.post("/tickets", json=a_body())
    response = client.get("/tickets", params={"offset": -5})
    assert response.json()["offset"] == 0


def test_the_routes_are_in_the_openapi_document(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/tickets" in paths
    assert "/tickets/{location_code}/{ticket_number}/void" in paths
