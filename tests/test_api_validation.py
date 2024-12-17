"""A refused request body reads like every other refusal.

FastAPI's own 422 puts pydantic's internal shape under ``detail``. The scale
house terminal shows ``detail`` to whoever is standing at the window, so it has
to be a sentence, and the machine readable breakdown goes somewhere else.
"""

import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.api.errors import field_path
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


def a_ticket(**overrides) -> dict:
    body = {
        "ticket_number": "A-10441",
        "location_code": "HOME",
        "scale_date": "2024-10-03",
        "commodity_code": "CORN",
        "party_code": "MERRICK",
        "gross_pounds": "78420",
        "tare_pounds": "26180",
        "disposition": "cash_sale",
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


def test_the_body_location_prefix_is_dropped():
    assert field_path(("body", "splits", 0, "percent")) == "splits.0.percent"
    assert field_path(("query", "on")) == "on"
    assert field_path(()) == "body"


def test_a_location_with_no_prefix_survives():
    assert field_path(("splits", 1)) == "splits.1"


def test_one_bad_field_reads_as_a_sentence(client):
    response = client.post("/tickets", json=a_ticket(scale_date="not-a-date"))
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "invalid_request"
    assert body["detail"].startswith("scale_date:")


def test_the_breakdown_names_the_field(client):
    response = client.post("/tickets", json=a_ticket(scale_date="not-a-date"))
    fields = response.json()["fields"]
    assert len(fields) == 1
    assert fields[0]["field"] == "scale_date"
    assert fields[0]["detail"]


def test_several_bad_fields_are_counted(client):
    response = client.post(
        "/tickets", json=a_ticket(scale_date="not-a-date", ticket_number="")
    )
    body = response.json()
    assert "2 fields were refused" in body["detail"]
    assert {item["field"] for item in body["fields"]} == {
        "scale_date",
        "ticket_number",
    }


def test_a_nested_field_is_named_by_its_path(client):
    response = client.post(
        "/tickets",
        json=a_ticket(splits=[{"party_code": "MERRICK", "percent": 100.5}]),
    )
    fields = response.json()["fields"]
    assert any(item["field"].startswith("splits.0") for item in fields)


def test_a_missing_field_is_named(client):
    body = a_ticket()
    del body["commodity_code"]
    response = client.post("/tickets", json=body)
    assert response.json()["fields"][0]["field"] == "commodity_code"


def test_the_refusal_carries_the_request_id(client):
    response = client.post("/tickets", json=a_ticket(scale_date="x"))
    assert response.json()["request_id"] == response.headers["x-request-id"]


def test_a_domain_refusal_still_has_no_fields_key(client):
    client.post("/tickets", json=a_ticket())
    response = client.post("/tickets", json=a_ticket())
    assert response.status_code == 409
    assert "fields" not in response.json()


def test_a_bad_query_parameter_is_refused_the_same_way(client):
    response = client.get("/positions/not-a-date")
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "invalid_request"
    assert body["fields"][0]["field"] == "as_of"


def test_a_float_quantity_says_what_to_send_instead(client):
    response = client.post("/tickets", json=a_ticket(gross_pounds=78420.5))
    assert response.status_code == 422
    assert "strings" in response.json()["fields"][0]["detail"]
