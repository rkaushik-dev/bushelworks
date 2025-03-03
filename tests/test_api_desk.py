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
            {"factor": "moisture", "value": "15.0"},
            {"factor": "test_weight", "value": "56.5"},
            {"factor": "bcfm", "value": "1.2"},
            {"factor": "damage_total", "value": "1.0"},
        ],
    }
    body.update(overrides)
    return body


def a_fill(**overrides) -> dict:
    body = {
        "reference": "F-1001",
        "commodity_code": "CORN",
        "month_code": "Z",
        "year": 2024,
        "side": "short",
        "contracts": 1,
        "price": "4.3225",
        "filled_on": "2024-11-01",
    }
    body.update(overrides)
    return body


@pytest.fixture
def busy(client):
    for index in range(20):
        client.post("/tickets", json=a_ticket(ticket_number=f"A-{10441 + index}"))
    return client


def test_an_empty_house_has_an_empty_sheet(client):
    body = client.post("/desk/sheet", json={"as_of": "2024-11-01"}).json()
    assert body["lines"] == []
    assert body["total_gap_contracts"] == 0


def test_the_sheet_names_the_day_and_the_site(busy):
    body = busy.post(
        "/desk/sheet", json={"as_of": "2024-11-01", "location_code": "HOME"}
    ).json()
    assert body["as_of"] == "2024-11-01"
    assert body["location_code"] == "HOME"


def test_an_unhedged_house_wants_to_sell(busy):
    body = busy.post("/desk/sheet", json={"as_of": "2024-11-01"}).json()
    corn = next(line for line in body["lines"] if line["commodity_code"] == "CORN")
    assert corn["on_contracts"] == 0
    assert corn["gap_contracts"] > 0
    assert corn["side"] == "short"


def test_the_fills_come_off_the_gap(busy):
    plain = busy.post("/desk/sheet", json={"as_of": "2024-11-01"}).json()
    hedged = busy.post(
        "/desk/sheet", json={"as_of": "2024-11-01", "fills": [a_fill()]}
    ).json()
    before = plain["lines"][0]["gap_contracts"]
    after = hedged["lines"][0]["gap_contracts"]
    assert after == before - 1


def test_a_hedge_on_another_commodity_does_not_help(busy):
    body = busy.post(
        "/desk/sheet",
        json={
            "as_of": "2024-11-01",
            "fills": [a_fill(commodity_code="SOYB", month_code="X")],
        },
    ).json()
    corn = next(line for line in body["lines"] if line["commodity_code"] == "CORN")
    assert corn["on_contracts"] == 0


def test_over_hedging_shows_on_the_line(busy):
    body = busy.post(
        "/desk/sheet",
        json={"as_of": "2024-11-01", "fills": [a_fill(contracts=400)]},
    ).json()
    corn = next(line for line in body["lines"] if line["commodity_code"] == "CORN")
    assert corn["is_over_hedged"]
    assert corn["side"] == "long"


def test_quantities_come_back_as_strings(busy):
    body = busy.post("/desk/sheet", json={"as_of": "2024-11-01"}).json()
    assert isinstance(body["lines"][0]["net_long"], str)


def test_the_actionable_route_drops_the_quiet_lines(busy):
    lines = busy.post(
        "/desk/sheet/actionable", json={"as_of": "2024-11-01"}
    ).json()
    assert all(line["is_actionable"] for line in lines)


def test_an_empty_house_has_nothing_actionable(client):
    assert client.post("/desk/sheet/actionable", json={"as_of": "2024-11-01"}).json() == []


def test_the_sheet_can_be_limited_to_one_commodity(busy):
    body = busy.post(
        "/desk/sheet", json={"as_of": "2024-11-01", "commodities": ["SOYB"]}
    ).json()
    assert all(line["commodity_code"] == "SOYB" for line in body["lines"])


def test_a_fill_needs_a_delivery_month_that_trades(client):
    response = client.post(
        "/desk/sheet",
        json={"as_of": "2024-11-01", "fills": [a_fill(month_code="B")]},
    )
    assert response.status_code == 422


def test_a_fill_with_no_contracts_is_refused(client):
    response = client.post(
        "/desk/sheet",
        json={"as_of": "2024-11-01", "fills": [a_fill(contracts=0)]},
    )
    assert response.status_code == 422


def test_a_price_sent_as_a_json_number_is_refused(client):
    response = client.post(
        "/desk/sheet",
        json={"as_of": "2024-11-01", "fills": [a_fill(price=4.3225)]},
    )
    assert response.status_code == 422


def test_the_same_fill_reference_twice_is_a_conflict(client):
    response = client.post(
        "/desk/sheet",
        json={"as_of": "2024-11-01", "fills": [a_fill(), a_fill()]},
    )
    assert response.status_code == 409


def test_a_short_gains_when_the_market_falls(client):
    body = client.post(
        "/desk/exposure",
        json={"fills": [a_fill()], "settlements": {"CORNZ24": "4.2225"}},
    ).json()
    assert body["CORNZ24"] == "500.00"


def test_a_short_loses_when_the_market_rises(client):
    body = client.post(
        "/desk/exposure",
        json={"fills": [a_fill()], "settlements": {"CORNZ24": "4.4225"}},
    ).json()
    assert body["CORNZ24"] == "-500.00"


def test_a_month_with_no_settlement_price_is_refused(client):
    response = client.post(
        "/desk/exposure", json={"fills": [a_fill()], "settlements": {}}
    )
    assert response.status_code == 422


def test_exposure_with_no_fills_is_empty(client):
    assert client.post("/desk/exposure", json={"fills": []}).json() == {}


def test_the_basis_is_cash_less_futures(client):
    body = client.get("/desk/basis", params={"cash_bid": "4.10", "futures": "4.3225"}).json()
    assert body["basis"] == "-0.2225"


def test_a_carry_that_beats_storage_pays_to_store(client):
    body = client.get(
        "/desk/carry",
        params={
            "near": "4.20",
            "deferred": "4.45",
            "months": 3,
            "storage_per_month": "0.05",
        },
    ).json()
    assert body["pays_to_store"] == "yes"
    assert body["carry_per_bushel"] == "0.1000"


def test_a_carry_that_does_not_cover_storage_says_so(client):
    body = client.get(
        "/desk/carry",
        params={
            "near": "4.20",
            "deferred": "4.25",
            "months": 3,
            "storage_per_month": "0.05",
        },
    ).json()
    assert body["pays_to_store"] == "no"


def test_a_carry_over_no_months_is_refused(client):
    response = client.get(
        "/desk/carry",
        params={
            "near": "4.20",
            "deferred": "4.45",
            "months": 0,
            "storage_per_month": "0.05",
        },
    )
    assert response.status_code == 422


def test_the_desk_is_office_only():
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
        looking = client.post(
            "/desk/sheet",
            json={"as_of": "2024-11-01"},
            headers={"X-API-Key": "look"},
        )
        allowed = client.post(
            "/desk/sheet",
            json={"as_of": "2024-11-01"},
            headers={"X-API-Key": "office"},
        )
    assert looking.status_code == 403
    assert allowed.status_code == 200
