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


def forward(**overrides) -> dict:
    body = {
        "number": "F-2024-118",
        "kind": "forward",
        "party_code": "MERRICK",
        "commodity_code": "CORN",
        "location_code": "HOME",
        "written_on": "2024-05-14",
        "bushels": "20000",
        "window_opens": "2024-10-01",
        "window_closes": "2024-11-30",
        "flat_price": "4.6200",
    }
    body.update(overrides)
    return body


def hta(**overrides) -> dict:
    return forward(
        number="H-2024-042",
        kind="hta",
        flat_price=None,
        futures_month="Z",
        futures_year=2024,
        futures_price="4.8500",
        **overrides,
    )


def basis_contract(**overrides) -> dict:
    return forward(
        number="B-2024-009",
        kind="basis",
        flat_price=None,
        futures_month="Z",
        futures_year=2024,
        basis="-0.3000",
        **overrides,
    )


def test_writing_a_forward_prices_it_immediately(client):
    response = client.post("/contracts", json=forward())
    assert response.status_code == 201
    body = response.json()
    assert body["is_fully_priced"] is True
    assert body["unit_price"] == "4.6200"
    assert body["minimum_bushels"] == "19600.0000"
    assert body["maximum_bushels"] == "20400.0000"


def test_a_forward_with_no_price_is_refused(client):
    response = client.post("/contracts", json=forward(flat_price=None))
    assert response.status_code == 422
    assert response.json()["code"] == "contract_error"


def test_writing_the_same_number_twice_is_a_409(client):
    client.post("/contracts", json=forward())
    assert client.post("/contracts", json=forward()).status_code == 409


def test_a_missing_contract_is_a_404(client):
    response = client.get("/contracts/F-9999")
    assert response.status_code == 404
    assert response.json()["code"] == "contract_not_found"


def test_an_hta_is_unpriced_until_the_basis_is_set(client):
    client.post("/contracts", json=hta())
    body = client.get("/contracts/H-2024-042").json()
    assert body["is_fully_priced"] is False
    assert body["unit_price"] is None
    assert body["futures_symbol"] == "CORNZ24"


def test_setting_the_basis_prices_the_hta(client):
    client.post("/contracts", json=hta())
    response = client.post(
        "/contracts/H-2024-042/basis", json={"value": "-0.2500", "on": "2024-10-15"}
    )
    assert response.status_code == 200
    assert response.json()["unit_price"] == "4.6000"


def test_the_basis_can_only_be_set_once(client):
    client.post("/contracts", json=hta())
    client.post("/contracts/H-2024-042/basis", json={"value": "-0.25", "on": "2024-10-15"})
    response = client.post(
        "/contracts/H-2024-042/basis", json={"value": "-0.15", "on": "2024-10-20"}
    )
    assert response.status_code == 422


def test_setting_a_basis_on_a_forward_is_refused(client):
    client.post("/contracts", json=forward())
    response = client.post(
        "/contracts/F-2024-118/basis", json={"value": "-0.25", "on": "2024-10-15"}
    )
    assert response.status_code == 422


def test_setting_futures_prices_a_basis_contract(client):
    client.post("/contracts", json=basis_contract())
    response = client.post(
        "/contracts/B-2024-009/futures", json={"value": "4.7000", "on": "2024-11-01"}
    )
    assert response.json()["unit_price"] == "4.4000"


def test_rolling_forward_adds_the_spread(client):
    client.post("/contracts", json=hta())
    response = client.post(
        "/contracts/H-2024-042/roll",
        json={"month_code": "H", "year": 2025, "spread": "0.1200"},
    )
    assert response.status_code == 200
    assert response.json()["futures_symbol"] == "CORNH25"
    assert response.json()["futures_price"] == "4.9700"


def test_rolling_backward_is_refused(client):
    client.post("/contracts", json=hta())
    client.post(
        "/contracts/H-2024-042/roll",
        json={"month_code": "H", "year": 2025, "spread": "0.12"},
    )
    response = client.post(
        "/contracts/H-2024-042/roll",
        json={"month_code": "Z", "year": 2024, "spread": "-0.12"},
    )
    assert response.status_code == 422


def test_rolling_to_a_month_the_commodity_does_not_trade(client):
    client.post("/contracts", json=hta())
    response = client.post(
        "/contracts/H-2024-042/roll",
        json={"month_code": "F", "year": 2025, "spread": "0.12"},
    )
    assert response.status_code == 422
    assert response.json()["code"] == "pricing_error"


def test_cancelling_a_contract(client):
    client.post("/contracts", json=forward())
    response = client.post("/contracts/F-2024-118/cancel")
    assert response.json()["status"] == "cancelled"


def test_cancelling_twice_is_refused(client):
    client.post("/contracts", json=forward())
    client.post("/contracts/F-2024-118/cancel")
    assert client.post("/contracts/F-2024-118/cancel").status_code == 422


def test_listing_open_contracts_for_a_day(client):
    client.post("/contracts", json=forward())
    client.post(
        "/contracts",
        json=forward(
            number="F-2024-119",
            window_opens="2025-01-01",
            window_closes="2025-03-31",
        ),
    )
    response = client.get(
        "/contracts",
        params={"party_code": "MERRICK", "commodity_code": "CORN", "on": "2024-10-15"},
    )
    body = response.json()
    assert body["total"] == 1
    assert body["items"][0]["number"] == "F-2024-118"


def test_a_cancelled_contract_is_not_listed(client):
    client.post("/contracts", json=forward())
    client.post("/contracts/F-2024-118/cancel")
    response = client.get(
        "/contracts",
        params={"party_code": "MERRICK", "commodity_code": "CORN", "on": "2024-10-15"},
    )
    assert response.json()["total"] == 0


def test_applied_bushels_start_at_zero(client):
    client.post("/contracts", json=forward())
    assert client.get("/contracts/F-2024-118").json()["applied_bushels"] == "0"
