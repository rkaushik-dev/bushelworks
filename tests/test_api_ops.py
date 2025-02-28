from datetime import UTC

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


@pytest.fixture
def busy(client):
    client.post("/tickets", json=a_ticket())
    client.post("/tickets", json=a_ticket(ticket_number="A-10442"))
    return client


def test_a_document_with_no_history_comes_back_empty(client):
    assert client.get("/ops/audit/documents/ticket/A-99999").json() == []


def test_a_recorded_ticket_has_a_trail(busy):
    entries = busy.get("/ops/audit/documents/ticket/A-10441").json()
    assert len(entries) == 1
    assert entries[0]["action"] == "recorded"
    assert entries[0]["document_kind"] == "ticket"


def test_the_trail_carries_the_detail(busy):
    entry = busy.get("/ops/audit/documents/ticket/A-10441").json()[0]
    assert "grade 1" in entry["detail"]


def test_voiding_adds_to_the_trail(busy):
    busy.post("/tickets/HOME/A-10441/void", params={"reason": "reweighed"})
    entries = busy.get("/ops/audit/documents/ticket/A-10441").json()
    assert [entry["action"] for entry in entries] == ["recorded"]


def test_a_producer_trail_lists_newest_first(busy):
    entries = busy.get("/ops/audit/party/MERRICK").json()
    assert [entry["document_number"] for entry in entries] == ["A-10442", "A-10441"]


def test_a_producer_trail_can_be_limited(busy):
    entries = busy.get("/ops/audit/party/MERRICK", params={"limit": 1}).json()
    assert len(entries) == 1


def test_a_limit_out_of_range_is_refused(busy):
    assert busy.get("/ops/audit/party/MERRICK", params={"limit": 0}).status_code == 422


def test_a_producer_with_no_trail_comes_back_empty(busy):
    assert busy.get("/ops/audit/party/HOLLIS").json() == []


def test_the_day_trail_finds_todays_entries(busy):
    from datetime import datetime

    today = datetime.now(UTC).date().isoformat()
    entries = busy.get(f"/ops/audit/day/{today}").json()
    assert len(entries) == 2


def test_the_day_trail_can_be_filtered_by_action(busy):
    from datetime import datetime

    today = datetime.now(UTC).date().isoformat()
    entries = busy.get(f"/ops/audit/day/{today}", params={"action": "settled"}).json()
    assert entries == []


def test_closing_a_day_reports_what_happened(busy):
    body = busy.post("/ops/close/2024-10-03").json()
    assert body["tickets_written"] == 2
    assert body["tickets_voided"] == 0
    assert body["bushels_in"] == "1865.7143"


def test_a_clean_close_says_so(busy):
    body = busy.post("/ops/close/2024-10-03").json()
    assert body["is_clean"] is True
    assert body["discrepancies"] == []


def test_a_tight_tolerance_finds_a_position_move(busy):
    body = busy.post(
        "/ops/close/2024-10-03", params={"position_tolerance": "100"}
    ).json()
    assert body["is_clean"] is False
    assert any(item["kind"] == "position" for item in body["discrepancies"])


def test_an_empty_day_closes_clean(client):
    body = client.post("/ops/close/2024-10-03").json()
    assert body["is_clean"] is True
    assert body["tickets_written"] == 0


def test_the_close_prints_for_the_day_book(busy):
    response = busy.get("/ops/close/2024-10-03/sheet")
    assert response.headers["content-type"].startswith("text/plain")
    assert "DAILY CLOSE 2024-10-03" in response.text
    assert "closed clean" in response.text


def test_the_ops_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/ops/close/{day}" in paths
    assert "/ops/audit/party/{party_code}" in paths
    assert "/ops/audit/documents/{document_kind}/{document_number}" in paths
