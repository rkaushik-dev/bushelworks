"""Which key gets into which part of the house."""

import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

OFFICE_KEY = "office-key-value"
SCALE_KEY = "scale-key-value"
READ_KEY = "read-key-value"

KEYS = frozenset(
    {
        f"k1:office:{OFFICE_KEY}",
        f"k2:scale:{SCALE_KEY}",
        f"k3:read:{READ_KEY}",
    }
)


@pytest.fixture
def client():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(
            database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS
        ),
        factory=session_factory(engine),
    )
    with TestClient(app) as test_client:
        yield test_client


def headers(key: str) -> dict[str, str]:
    return {"X-API-Key": key}


def a_ticket() -> dict:
    return {
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


def test_the_scale_key_writes_tickets(client):
    response = client.post("/tickets", json=a_ticket(), headers=headers(SCALE_KEY))
    assert response.status_code == 201


def test_the_office_key_also_writes_tickets(client):
    response = client.post("/tickets", json=a_ticket(), headers=headers(OFFICE_KEY))
    assert response.status_code == 201


def test_the_read_key_cannot_write_a_ticket(client):
    response = client.post("/tickets", json=a_ticket(), headers=headers(READ_KEY))
    assert response.status_code == 403
    assert response.json()["code"] == "forbidden"


def test_the_read_key_cannot_list_tickets_either(client):
    response = client.get("/tickets", headers=headers(READ_KEY))
    assert response.status_code == 403


def test_the_scale_key_cannot_settle(client):
    client.post("/tickets", json=a_ticket(), headers=headers(SCALE_KEY))
    response = client.post(
        "/settlements",
        json={
            "party_code": "MERRICK",
            "location_code": "HOME",
            "settled_on": "2024-10-20",
            "spot_price": "4.3500",
        },
        headers=headers(SCALE_KEY),
    )
    assert response.status_code == 403


def test_the_office_key_settles(client):
    client.post("/tickets", json=a_ticket(), headers=headers(SCALE_KEY))
    response = client.post(
        "/settlements",
        json={
            "party_code": "MERRICK",
            "location_code": "HOME",
            "settled_on": "2024-10-20",
            "spot_price": "4.3500",
        },
        headers=headers(OFFICE_KEY),
    )
    assert response.status_code == 201


def test_the_scale_key_cannot_write_a_contract(client):
    response = client.post(
        "/contracts",
        json={
            "number": "F-1",
            "kind": "forward",
            "party_code": "MERRICK",
            "commodity_code": "CORN",
            "location_code": "HOME",
            "written_on": "2024-05-14",
            "bushels": "20000",
            "window_opens": "2024-10-01",
            "window_closes": "2024-11-30",
            "flat_price": "4.62",
        },
        headers=headers(SCALE_KEY),
    )
    assert response.status_code == 403


def test_the_scale_key_cannot_run_the_accrual(client):
    response = client.post(
        "/storage/accruals",
        params={"through": "2024-12-14"},
        headers=headers(SCALE_KEY),
    )
    assert response.status_code == 403


def test_every_key_can_read_the_position(client):
    for key in (READ_KEY, SCALE_KEY, OFFICE_KEY):
        response = client.get("/positions/2024-11-01", headers=headers(key))
        assert response.status_code == 200, key


def test_no_key_at_all_is_a_401_not_a_403(client):
    assert client.get("/positions/2024-11-01").status_code == 401
    assert client.post("/tickets", json=a_ticket()).status_code == 401


def test_the_forbidden_body_says_which_role_was_needed(client):
    body = client.get("/tickets", headers=headers(READ_KEY)).json()
    assert "scale role" in body["detail"]
    assert "read" in body["detail"]
