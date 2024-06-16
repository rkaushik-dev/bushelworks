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


def a_settle(**overrides) -> dict:
    body = {
        "party_code": "MERRICK",
        "location_code": "HOME",
        "settled_on": "2024-10-20",
        "spot_price": "4.3500",
    }
    body.update(overrides)
    return body


def test_settling_one_load(client):
    client.post("/tickets", json=a_ticket())
    response = client.post("/settlements", json=a_settle())
    assert response.status_code == 201
    body = response.json()
    assert body["number"] == "S-2024-0001"
    assert body["gross"] == "3904.70"
    assert body["ticket_numbers"] == ["A-10441"]


def test_the_location_header_points_at_the_statement(client):
    client.post("/tickets", json=a_ticket())
    response = client.post("/settlements", json=a_settle())
    assert response.headers["Location"] == "/settlements/S-2024-0001"


def test_settling_with_nothing_waiting_is_a_422(client):
    response = client.post("/settlements", json=a_settle())
    assert response.status_code == 422
    assert response.json()["code"] == "nothing_to_settle"


def test_a_settled_ticket_will_not_settle_twice(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    assert client.post("/settlements", json=a_settle()).status_code == 422


def test_the_statement_reads_back_with_its_lines(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    body = client.get("/settlements/S-2024-0001").json()
    kinds = [line["kind"] for line in body["lines"]]
    assert kinds[0] == "gross"
    assert "drying" in kinds
    assert "assessment" in kinds


def test_a_missing_settlement_is_a_404(client):
    response = client.get("/settlements/S-9999")
    assert response.status_code == 404
    assert response.json()["code"] == "settlement_not_found"


def test_the_printable_statement_comes_back_as_text(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    response = client.get("/settlements/S-2024-0001/statement")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert "SETTLEMENT STATEMENT" in response.text
    assert "NET PAYABLE" in response.text


def test_advances_come_off_the_net(client):
    client.post("/tickets", json=a_ticket())
    plain = client.post("/settlements", json=a_settle()).json()
    client.post("/tickets", json=a_ticket(ticket_number="A-10442"))
    with_advance = client.post(
        "/settlements",
        json=a_settle(
            advances=[
                {
                    "reference": "2024-0912",
                    "dollars": "1000.00",
                    "advanced_on": "2024-09-12",
                }
            ]
        ),
    ).json()
    from decimal import Decimal

    assert Decimal(with_advance["net"]) == Decimal(plain["net"]) - Decimal("1000.00")


def test_only_the_named_tickets_settle(client):
    client.post("/tickets", json=a_ticket())
    client.post("/tickets", json=a_ticket(ticket_number="A-10442"))
    response = client.post("/settlements", json=a_settle(ticket_numbers=["A-10442"]))
    assert response.json()["ticket_numbers"] == ["A-10442"]


def test_paying_records_the_cheque(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    response = client.post(
        "/settlements/S-2024-0001/payment",
        json={"cheque_number": "10442", "paid_on": "2024-10-21"},
    )
    assert response.status_code == 200


def test_paying_twice_is_a_409(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    payment = {"cheque_number": "10442", "paid_on": "2024-10-21"}
    client.post("/settlements/S-2024-0001/payment", json=payment)
    response = client.post("/settlements/S-2024-0001/payment", json=payment)
    assert response.status_code == 409


def test_listing_unpaid_settlements(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    response = client.get("/settlements", params={"unpaid_only": True})
    assert response.json()["total"] == 1


def test_a_paid_settlement_drops_off_the_unpaid_list(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    client.post(
        "/settlements/S-2024-0001/payment",
        json={"cheque_number": "10442", "paid_on": "2024-10-21"},
    )
    response = client.get("/settlements", params={"unpaid_only": True})
    assert response.json()["total"] == 0


def test_listing_by_day(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    assert client.get("/settlements", params={"on": "2024-10-20"}).json()["total"] == 1
    assert client.get("/settlements", params={"on": "2024-10-21"}).json()["total"] == 0


def test_the_daily_register_prints(client):
    client.post("/tickets", json=a_ticket())
    client.post("/settlements", json=a_settle())
    response = client.get("/settlements/register/2024-10-20")
    assert "SETTLEMENT REGISTER" in response.text
    assert "20 October 2024" in response.text
    assert "1 statements" in response.text


def test_a_spot_price_sent_as_a_float_is_refused(client):
    client.post("/tickets", json=a_ticket())
    response = client.post("/settlements", json=a_settle(spot_price=4.35))
    assert response.status_code == 422
