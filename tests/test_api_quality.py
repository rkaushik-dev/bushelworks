import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

SPAN = {"opens": "2024-09-01", "closes": "2025-08-31"}


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


def a_complaint(**overrides) -> dict:
    body = {
        "reference": "C-2024-018",
        "source": "buyer",
        "subject": "car ran 15.8 moisture against a 14.0 contract",
        "raised_on": "2024-10-09",
        "delivered_on": "2024-10-03",
        "party_code": "CARGILL",
        "bin_id": "BIN-3",
        "probe_id": "P-88213",
    }
    body.update(overrides)
    return body


@pytest.fixture
def filed(client):
    client.post("/complaints", json=a_complaint())
    return client


def test_a_complaint_can_be_taken(client):
    response = client.post("/complaints", json=a_complaint())
    assert response.status_code == 201
    assert response.json()["outcome"] == "open"


def test_a_new_complaint_is_open_and_defensible(client):
    body = client.post("/complaints", json=a_complaint()).json()
    assert body["is_open"]
    assert body["sample_is_available"]
    assert not body["is_late"]


def test_the_days_since_delivery_come_back(client):
    assert client.post("/complaints", json=a_complaint()).json()["days_after"] == 6


def test_a_complaint_raised_before_delivery_is_refused(client):
    response = client.post(
        "/complaints", json=a_complaint(raised_on="2024-10-01")
    )
    assert response.status_code == 422


def test_the_same_reference_twice_is_a_conflict(filed):
    assert filed.post("/complaints", json=a_complaint()).status_code == 409


def test_a_complaint_with_no_subject_is_refused(client):
    assert client.post("/complaints", json=a_complaint(subject="")).status_code == 422


def test_a_source_nobody_knows_is_refused(client):
    response = client.post("/complaints", json=a_complaint(source="postman"))
    assert response.status_code == 422


def test_a_named_ticket_has_to_exist(client):
    response = client.post(
        "/complaints", json=a_complaint(ticket_number="A-99999")
    )
    assert response.status_code == 422


def test_a_named_ticket_that_exists_is_taken(client):
    client.post("/tickets", json=a_ticket())
    response = client.post(
        "/complaints", json=a_complaint(ticket_number="A-10441")
    )
    assert response.status_code == 201


def test_a_ticket_weighed_after_the_delivery_is_refused(client):
    client.post("/tickets", json=a_ticket(scale_date="2024-11-20"))
    response = client.post(
        "/complaints", json=a_complaint(ticket_number="A-10441")
    )
    assert response.status_code == 422


def test_a_voided_ticket_is_not_a_ticket(client):
    client.post("/tickets", json=a_ticket())
    client.post("/tickets/HOME/A-10441/void", params={"reason": "keyed twice"})
    response = client.post(
        "/complaints", json=a_complaint(ticket_number="A-10441")
    )
    assert response.status_code == 422


def test_a_filed_complaint_can_be_read_back(filed):
    assert filed.get("/complaints/C-2024-018").json()["party_code"] == "CARGILL"


def test_one_that_is_not_there_is_a_404(client):
    assert client.get("/complaints/C-NOPE").status_code == 404


def test_the_open_list_carries_the_new_one(filed):
    body = filed.get("/complaints").json()
    assert [item["reference"] for item in body] == ["C-2024-018"]


def test_the_open_list_can_be_narrowed_to_a_bin(filed):
    filed.post("/complaints", json=a_complaint(reference="C-2", bin_id="BIN-7"))
    body = filed.get("/complaints", params={"bin_id": "BIN-7"}).json()
    assert [item["reference"] for item in body] == ["C-2"]


def test_a_complaint_with_no_sample_is_indefensible(client):
    client.post("/complaints", json=a_complaint(probe_id=None))
    body = client.get("/complaints/reports/indefensible", params=SPAN).json()
    assert [item["reference"] for item in body] == ["C-2024-018"]


def test_a_complaint_past_retention_is_indefensible(client):
    client.post("/complaints", json=a_complaint(raised_on="2024-12-01"))
    body = client.get("/complaints/reports/indefensible", params=SPAN).json()
    assert len(body) == 1


def test_a_complaint_with_a_live_sample_is_defensible(filed):
    assert filed.get("/complaints/reports/indefensible", params=SPAN).json() == []


def test_an_action_can_be_recorded(filed):
    response = filed.post(
        "/complaints/C-2024-018/actions",
        json={"taken_on": "2024-10-10", "taken_by": "dee", "what": "pulled the sample"},
    )
    assert response.status_code == 201


def test_an_action_on_a_complaint_that_is_not_there_is_a_404(client):
    response = client.post(
        "/complaints/C-NOPE/actions",
        json={"taken_on": "2024-10-10", "taken_by": "dee", "what": "nothing"},
    )
    assert response.status_code == 404


def test_closing_records_the_outcome(filed):
    body = filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "upheld", "on": "2024-10-14", "credit": "412.50"},
    ).json()
    assert body["outcome"] == "upheld"
    assert body["credit"] == "412.50"
    assert not body["is_open"]


def test_a_closed_complaint_leaves_the_open_list(filed):
    filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "rejected", "on": "2024-10-14"},
    )
    assert filed.get("/complaints").json() == []


def test_closing_twice_is_a_conflict(filed):
    filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "rejected", "on": "2024-10-14"},
    )
    response = filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "upheld", "on": "2024-10-15"},
    )
    assert response.status_code == 409


def test_a_rejected_complaint_cannot_carry_a_credit(filed):
    response = filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "rejected", "on": "2024-10-14", "credit": "50.00"},
    )
    assert response.status_code == 422


def test_closing_before_it_was_raised_is_refused(filed):
    response = filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "settled", "on": "2024-10-04"},
    )
    assert response.status_code == 422


def test_a_credit_sent_as_a_json_number_is_refused(filed):
    response = filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "upheld", "on": "2024-10-14", "credit": 412.5},
    )
    assert response.status_code == 422


def test_a_bin_complained_about_three_times_is_flagged(filed):
    filed.post("/complaints", json=a_complaint(reference="C-2"))
    filed.post("/complaints", json=a_complaint(reference="C-3"))
    body = filed.get("/complaints/reports/repeat-bins", params=SPAN).json()
    assert body == ["BIN-3"]


def test_two_complaints_are_not_a_pattern(filed):
    filed.post("/complaints", json=a_complaint(reference="C-2"))
    assert filed.get("/complaints/reports/repeat-bins", params=SPAN).json() == []


def test_the_threshold_can_be_moved(filed):
    filed.post("/complaints", json=a_complaint(reference="C-2"))
    body = filed.get(
        "/complaints/reports/repeat-bins", params={**SPAN, "threshold": 2}
    ).json()
    assert body == ["BIN-3"]


def test_the_summary_counts_what_came_in(filed):
    body = filed.get("/complaints/reports/summary", params=SPAN).json()
    assert body["count"] == 1
    assert body["open"] == 1
    assert body["by_source"] == {"buyer": 1}


def test_the_summary_carries_the_upheld_rate(filed):
    filed.post(
        "/complaints/C-2024-018/close",
        json={"outcome": "upheld", "on": "2024-10-14", "credit": "412.50"},
    )
    body = filed.get("/complaints/reports/summary", params=SPAN).json()
    assert body["upheld_rate"] == "100.0"
    assert body["credits"] == "412.50"


def test_the_summary_names_the_window(filed):
    assert filed.get("/complaints/reports/summary", params=SPAN).json()["window_days"] == 30


def test_a_quiet_span_summarises_to_nothing(client):
    body = client.get("/complaints/reports/summary", params=SPAN).json()
    assert body["count"] == 0
    assert body["upheld_rate"] == "0.0"


def test_the_register_is_office_only():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(
            database_url="sqlite+pysqlite:///:memory:",
            api_keys=frozenset({"k1:read:look", "k2:office:office"}),
        ),
        factory=session_factory(engine),
    )
    with TestClient(app) as client:
        looking = client.get("/complaints", headers={"X-API-Key": "look"})
        allowed = client.get("/complaints", headers={"X-API-Key": "office"})
    assert looking.status_code == 403
    assert allowed.status_code == 200
