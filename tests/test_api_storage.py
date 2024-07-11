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


def a_lot(**overrides) -> dict:
    body = {
        "lot_id": "L-2024-0031",
        "party_code": "MERRICK",
        "commodity_code": "CORN",
        "location_code": "HOME",
        "bushels": "8000",
        "stored_on": "2024-10-03",
    }
    body.update(overrides)
    return body


@pytest.fixture
def stocked(client):
    client.post("/storage/lots", json=a_lot())
    client.post(
        "/storage/lots",
        json=a_lot(lot_id="L-2024-0032", bushels="4000", stored_on="2024-10-20"),
    )
    return client


def test_opening_a_lot(client):
    response = client.post("/storage/lots", json=a_lot())
    assert response.status_code == 201
    body = response.json()
    assert body["open_bushels"] == "8000.0000"
    assert body["accrued_to_date"] == "0.00"


def test_opening_the_same_lot_twice_is_a_409(stocked):
    assert stocked.post("/storage/lots", json=a_lot()).status_code == 409


def test_reading_a_lot(stocked):
    body = stocked.get("/storage/lots/L-2024-0031").json()
    assert body["party_code"] == "MERRICK"
    assert body["billed_through"] is None


def test_a_missing_lot_is_a_404(client):
    response = client.get("/storage/lots/L-9999")
    assert response.status_code == 404
    assert response.json()["code"] == "lot_not_found"


def test_listing_open_lots(stocked):
    body = stocked.get("/storage/lots", params={"party_code": "MERRICK"}).json()
    assert body["total"] == 2


def test_withdrawing_draws_the_oldest_lot_first(stocked):
    response = stocked.post(
        "/storage/withdrawals",
        json={
            "party_code": "MERRICK",
            "commodity_code": "CORN",
            "bushels": "9000",
            "withdrawn_on": "2024-11-01",
        },
    )
    assert response.status_code == 200
    touched = {item["lot_id"]: item["open_bushels"] for item in response.json()}
    assert touched["L-2024-0031"] == "0.0000"
    assert touched["L-2024-0032"] == "3000.0000"


def test_withdrawing_more_than_is_there_is_a_422(stocked):
    response = stocked.post(
        "/storage/withdrawals",
        json={
            "party_code": "MERRICK",
            "commodity_code": "CORN",
            "bushels": "20000",
            "withdrawn_on": "2024-11-01",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == "storage_service_error"


def test_what_is_on_hand(stocked):
    body = stocked.get("/storage/on-hand/CORN").json()
    assert body["bushels"] == "12000.0000"


def test_a_dry_run_shows_the_numbers_without_billing(stocked):
    response = stocked.post(
        "/storage/accruals", params={"through": "2024-12-14", "dry_run": True}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["recorded"] is False
    assert body["total"] != "0.00"
    assert stocked.get("/storage/lots/L-2024-0031").json()["billed_through"] is None


def test_a_real_run_moves_the_billed_through(stocked):
    stocked.post("/storage/accruals", params={"through": "2024-12-14"})
    body = stocked.get("/storage/lots/L-2024-0031").json()
    assert body["billed_through"] == "2024-12-14"
    assert body["accrued_to_date"] == "504.00"


def test_running_the_accrual_twice_charges_nothing_again(stocked):
    stocked.post("/storage/accruals", params={"through": "2024-12-14"})
    second = stocked.post("/storage/accruals", params={"through": "2024-12-14"}).json()
    assert second["lines"] == []
    assert second["total"] == "0.00"


def test_writing_a_warehouse_receipt(stocked):
    response = stocked.post(
        "/storage/receipts",
        json={
            "receipt_number": "WR-004417",
            "lot_id": "L-2024-0031",
            "grade": "2",
            "issued_on": "2024-10-04",
        },
    )
    assert response.status_code == 201
    body = response.json()
    assert body["is_live"] is True
    assert body["bushels"] == "8000.0000"


def test_a_lot_carries_one_live_receipt(stocked):
    receipt = {
        "receipt_number": "WR-004417",
        "lot_id": "L-2024-0031",
        "grade": "2",
        "issued_on": "2024-10-04",
    }
    stocked.post("/storage/receipts", json=receipt)
    second = dict(receipt, receipt_number="WR-004418")
    assert stocked.post("/storage/receipts", json=second).status_code == 409


def test_cancelling_a_receipt(stocked):
    stocked.post(
        "/storage/receipts",
        json={
            "receipt_number": "WR-004417",
            "lot_id": "L-2024-0031",
            "grade": "2",
            "issued_on": "2024-10-04",
        },
    )
    response = stocked.post(
        "/storage/receipts/WR-004417/cancel", params={"on": "2025-03-01"}
    )
    assert response.status_code == 200
    assert response.json()["is_live"] is False


def test_cancelling_twice_is_a_409(stocked):
    stocked.post(
        "/storage/receipts",
        json={
            "receipt_number": "WR-004417",
            "lot_id": "L-2024-0031",
            "grade": "2",
            "issued_on": "2024-10-04",
        },
    )
    stocked.post("/storage/receipts/WR-004417/cancel", params={"on": "2025-03-01"})
    response = stocked.post(
        "/storage/receipts/WR-004417/cancel", params={"on": "2025-03-02"}
    )
    assert response.status_code == 409


def test_the_storage_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/storage/lots" in paths
    assert "/storage/accruals" in paths
    assert "/storage/receipts" in paths
