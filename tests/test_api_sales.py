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


def a_sale(**overrides) -> dict:
    body = {
        "number": "S-2024-0088",
        "buyer": "Salina Terminal",
        "commodity_code": "CORN",
        "origin_code": "HOME",
        "written_on": "2024-09-05",
        "bushels": "10000",
        "window_opens": "2024-11-01",
        "window_closes": "2024-12-31",
        "flat_price": "4.7800",
        "freight_per_bushel": "0.1800",
    }
    body.update(overrides)
    return body


@pytest.fixture
def sold(client):
    client.post("/sales", json=a_sale())
    return client


def test_writing_a_sale(client):
    response = client.post("/sales", json=a_sale())
    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "open"
    assert body["remaining"] == "10000"
    assert body["shipments"] == []


def test_the_same_sale_number_twice_is_a_409(sold):
    assert sold.post("/sales", json=a_sale()).status_code == 409


def test_a_missing_sale_is_a_404(client):
    response = client.get("/sales/S-9999")
    assert response.status_code == 404
    assert response.json()["code"] == "sale_not_found"


def test_a_sale_of_no_bushels_is_refused_as_a_422(client):
    response = client.post("/sales", json=a_sale(bushels="0"))
    assert response.status_code == 422
    assert response.json()["code"] == "sale_error"


def test_a_sale_with_no_price_is_refused_as_a_422(client):
    response = client.post("/sales", json=a_sale(flat_price=None))
    assert response.status_code == 422
    assert response.json()["code"] == "sale_error"


def test_a_window_that_closes_first_is_refused_as_a_422(client):
    response = client.post(
        "/sales", json=a_sale(window_opens="2024-12-31", window_closes="2024-11-01")
    )
    assert response.status_code == 422


def test_listing_open_sales(sold):
    body = sold.get("/sales", params={"commodity_code": "CORN"}).json()
    assert body["total"] == 1
    assert body["items"][0]["number"] == "S-2024-0088"


def test_loading_out_moves_the_shipped_total(sold):
    response = sold.post(
        "/sales/S-2024-0088/shipments",
        json={
            "reference": "CAR-1",
            "pounds": "196000",
            "shipped_on": "2024-11-12",
            "carrier_id": "BNSF 480221",
        },
    )
    assert response.status_code == 201
    body = response.json()
    assert body["shipped"] == "3500.0000"
    assert body["remaining"] == "6500.0000"
    assert body["shipments"][0]["carrier_id"] == "BNSF 480221"


def test_loading_outside_the_window_is_a_422(sold):
    response = sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2025-03-01"},
    )
    assert response.status_code == 422
    assert response.json()["code"] == "shipping_error"


def test_the_same_reference_twice_is_a_409(sold):
    load = {"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"}
    sold.post("/sales/S-2024-0088/shipments", json=load)
    assert sold.post("/sales/S-2024-0088/shipments", json=load).status_code == 409


def test_filling_a_sale_closes_it(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "560000", "shipped_on": "2024-11-12"},
    )
    assert sold.get("/sales/S-2024-0088").json()["status"] == "filled"


def test_posting_the_buyer_settlement(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"},
    )
    response = sold.post(
        "/sales/S-2024-0088/shipments/CAR-1/settlement",
        json={
            "destination_pounds": "195328",
            "destination_grade": "2",
            "proceeds": "16730.00",
            "settled_on": "2024-11-26",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["destination_grade"] == "2"
    assert body["transit_shrink"] == "12.0000"


def test_settling_twice_is_a_409(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"},
    )
    settlement = {
        "destination_pounds": "195328",
        "destination_grade": "2",
        "proceeds": "16730.00",
        "settled_on": "2024-11-26",
    }
    sold.post("/sales/S-2024-0088/shipments/CAR-1/settlement", json=settlement)
    response = sold.post(
        "/sales/S-2024-0088/shipments/CAR-1/settlement", json=settlement
    )
    assert response.status_code == 409


def test_rejecting_a_car_puts_the_bushels_back(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"},
    )
    response = sold.post(
        "/sales/S-2024-0088/shipments/CAR-1/rejection",
        params={"reason": "16.8 moisture at destination"},
    )
    assert response.status_code == 200
    assert response.json()["rejected"] is True
    assert sold.get("/sales/S-2024-0088").json()["shipped"] == "0.0000"


def test_a_rejection_needs_a_reason(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"},
    )
    response = sold.post(
        "/sales/S-2024-0088/shipments/CAR-1/rejection", params={"reason": ""}
    )
    assert response.status_code == 422


def test_the_short_position_comes_off_the_open_sales(sold):
    body = sold.get(
        "/sales/position/CORN", params={"on": "2024-11-12"}
    ).json()
    assert body["sold_forward"] == "10000.0000"


def test_loading_reduces_the_short_position(sold):
    sold.post(
        "/sales/S-2024-0088/shipments",
        json={"reference": "CAR-1", "pounds": "196000", "shipped_on": "2024-11-12"},
    )
    body = sold.get("/sales/position/CORN", params={"on": "2024-11-12"}).json()
    assert body["sold_forward"] == "6500.0000"


def test_the_sale_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/sales" in paths
    assert "/sales/{number}/shipments" in paths
