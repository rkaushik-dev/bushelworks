from decimal import Decimal

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
    client.post(
        "/storage/lots",
        json={
            "lot_id": "L-1",
            "party_code": "MERRICK",
            "commodity_code": "CORN",
            "location_code": "HOME",
            "bushels": "4000",
            "stored_on": "2024-10-05",
        },
    )
    return client


def test_an_empty_house_has_no_position(client):
    body = client.get("/positions/2024-11-01").json()
    assert body["lines"] == []
    assert body["total_owned"] == "0.0000"


def test_the_position_lists_the_commodity_on_hand(busy):
    body = busy.get("/positions/2024-11-01").json()
    assert [line["commodity_code"] for line in body["lines"]] == ["CORN"]
    assert body["lines"][0]["on_hand"] == "1865.7143"


def test_storage_is_shown_separately_from_what_is_owned(busy):
    line = busy.get("/positions/2024-11-01").json()["lines"][0]
    assert line["storage_obligation"] == "4000.0000"
    assert line["owned"] == "-2134.2857"


def test_the_hedge_count_rounds_toward_zero(busy):
    line = busy.get("/positions/2024-11-01").json()["lines"][0]
    assert line["hedge_contracts"] == 0


def test_a_smaller_contract_size_gives_more_lots(busy):
    line = busy.get(
        "/positions/2024-11-01", params={"contract_size": "100"}
    ).json()["lines"][0]
    assert line["hedge_contracts"] != 0


def test_one_commodity_can_be_asked_for(busy):
    body = busy.get("/positions/2024-11-01/commodity/CORN").json()
    assert body["commodity_code"] == "CORN"
    assert body["as_of"] == "2024-11-01"


def test_the_sale_side_comes_off_the_sale_contracts(busy):
    flat = busy.get("/positions/2024-11-01/commodity/CORN").json()
    assert flat["sold_forward"] == "0.0000"


def test_grain_in_transit_can_still_be_passed_in(busy):
    plain = busy.get("/positions/2024-11-01/commodity/CORN").json()
    moving = busy.get(
        "/positions/2024-11-01/commodity/CORN", params={"in_transit": "1000"}
    ).json()
    assert moving["in_transit"] == "1000"
    assert Decimal(moving["net_long"]) == Decimal(plain["net_long"]) + Decimal("1000")


def test_the_board_prints_as_text(busy):
    response = busy.get("/positions/2024-11-01/board")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert "POSITION AS OF 2024-11-01" in response.text


def test_the_hedge_sheet_prints_as_text(busy):
    response = busy.get("/positions/2024-11-01/hedge-sheet")
    assert "HEDGE SHEET" in response.text
    assert "CONTRACTS" in response.text


def test_the_export_comes_back_as_a_csv_attachment(busy):
    response = busy.get("/positions/2024-11-01/export")
    assert response.headers["content-type"].startswith("text/csv")
    assert "position-2024-11-01.csv" in response.headers["content-disposition"]
    assert response.text.split("\n")[0].startswith("as_of,commodity_code")


def test_the_unhedged_gap_reports_the_whole_position(busy):
    body = busy.get("/positions/2024-11-01/unhedged").json()
    line = busy.get("/positions/2024-11-01").json()["lines"][0]
    assert body["CORN"] == line["net_long"]


def test_the_position_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/positions/{as_of}" in paths
    assert "/positions/{as_of}/hedge-sheet" in paths
