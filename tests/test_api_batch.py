"""A run of tickets the terminal held while the line was down.

One duplicate in the middle used to lose the other eight, and the driver of the
eighth had already gone home.
"""

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


def a_ticket(number="A-10441", **overrides) -> dict:
    body = {
        "ticket_number": number,
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


def test_a_clean_batch_is_all_accepted(client):
    response = client.post(
        "/tickets/batch",
        json={"tickets": [a_ticket("A-1"), a_ticket("A-2"), a_ticket("A-3")]},
    )
    assert response.status_code == 207
    body = response.json()
    assert body["accepted"] == 3
    assert body["refused"] == 0


def test_every_ticket_gets_its_own_result(client):
    body = client.post(
        "/tickets/batch", json={"tickets": [a_ticket("A-1"), a_ticket("A-2")]}
    ).json()
    assert [item["ticket_number"] for item in body["results"]] == ["A-1", "A-2"]
    assert all(item["accepted"] for item in body["results"])


def test_an_accepted_ticket_reports_its_bushels(client):
    body = client.post("/tickets/batch", json={"tickets": [a_ticket("A-1")]}).json()
    assert body["results"][0]["dry_bushels"] == "897.6324"


def test_the_batch_is_actually_written(client):
    client.post("/tickets/batch", json={"tickets": [a_ticket("A-1")]})
    assert client.get("/tickets/HOME/A-1").status_code == 200


def test_a_duplicate_in_the_middle_does_not_cost_the_rest(client):
    client.post("/tickets", json=a_ticket("A-2"))
    body = client.post(
        "/tickets/batch",
        json={"tickets": [a_ticket("A-1"), a_ticket("A-2"), a_ticket("A-3")]},
    ).json()
    assert body["accepted"] == 2
    assert body["refused"] == 1
    assert client.get("/tickets/HOME/A-3").status_code == 200


def test_the_refused_ticket_says_why(client):
    client.post("/tickets", json=a_ticket("A-2"))
    body = client.post(
        "/tickets/batch", json={"tickets": [a_ticket("A-1"), a_ticket("A-2")]}
    ).json()
    refused = [item for item in body["results"] if not item["accepted"]][0]
    assert refused["ticket_number"] == "A-2"
    assert refused["code"] == "conflict"
    assert "already written" in refused["detail"]


def test_a_batch_can_be_told_to_stop_at_the_first_refusal(client):
    client.post("/tickets", json=a_ticket("A-2"))
    body = client.post(
        "/tickets/batch",
        json={
            "tickets": [a_ticket("A-1"), a_ticket("A-2"), a_ticket("A-3")],
            "stop_on_first_refusal": True,
        },
    ).json()
    assert body["accepted"] == 1
    assert len(body["results"]) == 2
    assert client.get("/tickets/HOME/A-3").status_code == 404


def test_the_tickets_before_a_stop_are_still_written(client):
    client.post("/tickets", json=a_ticket("A-2"))
    client.post(
        "/tickets/batch",
        json={
            "tickets": [a_ticket("A-1"), a_ticket("A-2")],
            "stop_on_first_refusal": True,
        },
    )
    assert client.get("/tickets/HOME/A-1").status_code == 200


def test_a_ticket_the_house_cannot_grade_is_refused_on_its_own(client):
    bad = a_ticket("A-2", commodity_code="MILO")
    body = client.post(
        "/tickets/batch", json={"tickets": [a_ticket("A-1"), bad, a_ticket("A-3")]}
    ).json()
    assert body["accepted"] == 2
    refused = [item for item in body["results"] if not item["accepted"]][0]
    assert refused["code"] == "intake_error"


def test_an_empty_batch_is_a_422(client):
    assert client.post("/tickets/batch", json={"tickets": []}).status_code == 422


def test_a_batch_past_the_limit_is_a_422(client):
    tickets = [a_ticket(f"A-{index}") for index in range(201)]
    response = client.post("/tickets/batch", json={"tickets": tickets})
    assert response.status_code == 422


def test_a_malformed_ticket_in_the_batch_refuses_the_whole_body(client):
    body = {"tickets": [a_ticket("A-1"), a_ticket("A-2", scale_date="x")]}
    response = client.post("/tickets/batch", json=body)
    assert response.status_code == 422
    assert response.json()["code"] == "invalid_request"


def test_the_batch_route_is_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/tickets/batch" in paths


def test_the_batch_path_does_not_shadow_a_ticket_lookup(client):
    client.post("/tickets", json=a_ticket("A-1"))
    assert client.get("/tickets/HOME/A-1").status_code == 200
