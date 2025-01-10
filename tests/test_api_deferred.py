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
        "lot_id": "D-1",
        "party_code": "MERRICK",
        "commodity_code": "CORN",
        "location_code": "HOME",
        "bushels": "10000",
        "delivered_on": "2024-10-03",
    }
    body.update(overrides)
    return body


@pytest.fixture
def delivered(client):
    client.post("/deferred/lots", json=a_lot())
    client.post(
        "/deferred/lots",
        json=a_lot(lot_id="D-2", bushels="5000", delivered_on="2024-11-12"),
    )
    return client


def test_taking_delivery_opens_a_lot(client):
    response = client.post("/deferred/lots", json=a_lot())
    assert response.status_code == 201
    body = response.json()
    assert body["open_bushels"] == "10000.0000"
    assert body["delivered_on"] == "2024-10-03"


def test_the_same_lot_twice_is_a_409(delivered):
    assert delivered.post("/deferred/lots", json=a_lot()).status_code == 409


def test_a_lot_of_nothing_is_a_422(client):
    response = client.post("/deferred/lots", json=a_lot(bushels="0"))
    assert response.status_code == 422


def test_listing_the_unpriced_lots(delivered):
    body = delivered.get("/deferred/lots", params={"party_code": "MERRICK"}).json()
    assert body["total"] == 2
    assert [item["lot_id"] for item in body["items"]] == ["D-1", "D-2"]


def test_pricing_a_lot_returns_the_gross_and_the_fee(delivered):
    response = delivered.post(
        "/deferred/lots/D-1/pricing",
        json={"bushels": "4000", "unit_price": "4.3500", "priced_on": "2024-11-02"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["gross"] == "17400.00"
    assert body["service_fee"] == "120.00"
    assert body["net"] == "17280.00"


def test_pricing_more_than_is_open_is_a_422(delivered):
    response = delivered.post(
        "/deferred/lots/D-1/pricing",
        json={"bushels": "20000", "unit_price": "4.35", "priced_on": "2024-11-02"},
    )
    assert response.status_code == 422
    assert response.json()["code"] == "deferred_price_error"


def test_pricing_before_delivery_is_a_409(delivered):
    response = delivered.post(
        "/deferred/lots/D-1/pricing",
        json={"bushels": "100", "unit_price": "4.35", "priced_on": "2024-09-01"},
    )
    assert response.status_code == 409


def test_pricing_a_lot_that_is_not_there_is_a_404(client):
    response = client.post(
        "/deferred/lots/D-9/pricing",
        json={"bushels": "100", "unit_price": "4.35", "priced_on": "2024-11-02"},
    )
    assert response.status_code == 404


def test_the_producer_position_adds_the_lots_up(delivered):
    body = delivered.get(
        "/deferred/position/MERRICK/CORN", params={"as_of": "2024-11-12"}
    ).json()
    assert body["bushels"] == "15000.0000"
    assert body["oldest_delivery"] == "2024-10-03"
    assert body["is_overdue"] is False


def test_a_producer_with_nothing_unpriced_is_a_404(delivered):
    response = delivered.get(
        "/deferred/position/HOLLIS/CORN", params={"as_of": "2024-11-12"}
    )
    assert response.status_code == 404


def test_the_position_names_the_overdue_lots(delivered):
    body = delivered.get(
        "/deferred/position/MERRICK/CORN", params={"as_of": "2025-12-01"}
    ).json()
    assert body["is_overdue"] is True
    assert set(body["overdue_lots"]) == {"D-1", "D-2"}


def test_the_house_exposure_is_the_unpriced_bushels(delivered):
    body = delivered.get(
        "/deferred/exposure/CORN", params={"as_of": "2024-11-12"}
    ).json()
    assert body["bushels"] == "15000.0000"
    assert body["total_unpriced"] == "15000.0000"


def test_exposure_ignores_a_later_delivery(delivered):
    body = delivered.get(
        "/deferred/exposure/CORN", params={"as_of": "2024-10-15"}
    ).json()
    assert body["bushels"] == "10000.0000"


def test_nothing_is_overdue_inside_the_term(delivered):
    assert delivered.get("/deferred/overdue", params={"as_of": "2025-01-01"}).json() == []


def test_the_overdue_list_names_the_lots(delivered):
    lots = delivered.get("/deferred/overdue", params={"as_of": "2025-12-01"}).json()
    assert [item["lot_id"] for item in lots] == ["D-1", "D-2"]


def test_the_deferred_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/deferred/lots" in paths
    assert "/deferred/lots/{lot_id}/pricing" in paths
