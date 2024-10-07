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


def a_party(**overrides) -> dict:
    body = {
        "code": "MERRICK",
        "name": "Merrick Farms LLC",
        "state_code": "KS",
        "opened_on": "2024-01-15",
    }
    body.update(overrides)
    return body


@pytest.fixture
def filed(client):
    client.post("/parties", json=a_party())
    client.post("/parties", json=a_party(code="BROCKMAN", name="Ellen Brockman"))
    client.post("/parties", json=a_party(code="HOLLIS", name="Hollis Brothers"))
    return client


def an_agreement(**overrides) -> dict:
    body = {
        "effective_from": "2024-03-01",
        "shares": [
            {"party_code": "BROCKMAN", "percent": "25", "role": "landlord"},
            {"party_code": "MERRICK", "percent": "75", "role": "tenant"},
        ],
    }
    body.update(overrides)
    return body


def test_opening_an_account(client):
    response = client.post("/parties", json=a_party())
    assert response.status_code == 201
    body = response.json()
    assert body["sort_name"] == "MERRICK FARMS LLC"
    assert body["is_active"] is True
    assert body["checkoff_exempt"] is False


def test_the_same_code_twice_is_a_409(filed):
    assert filed.post("/parties", json=a_party()).status_code == 409


def test_a_missing_party_is_a_404(client):
    response = client.get("/parties/NOBODY")
    assert response.status_code == 404
    assert response.json()["code"] == "party_not_found"


def test_a_blank_code_is_a_422(client):
    assert client.post("/parties", json=a_party(code="")).status_code == 422


def test_listing_the_active_accounts(filed):
    body = filed.get("/parties").json()
    assert body["total"] == 3
    assert [item["code"] for item in body["items"]] == [
        "BROCKMAN",
        "HOLLIS",
        "MERRICK",
    ]


def test_closing_an_account_takes_it_off_the_list(filed):
    response = filed.post("/parties/HOLLIS/closure", params={"on": "2024-12-31"})
    assert response.status_code == 200
    assert response.json()["is_active"] is False
    assert filed.get("/parties").json()["total"] == 2


def test_closing_twice_is_a_409(filed):
    filed.post("/parties/HOLLIS/closure", params={"on": "2024-12-31"})
    response = filed.post("/parties/HOLLIS/closure", params={"on": "2024-12-31"})
    assert response.status_code == 409


def test_marking_a_producer_exempt(filed):
    response = filed.post("/parties/MERRICK/checkoff-exemption")
    assert response.json()["checkoff_exempt"] is True
    off = filed.post(
        "/parties/MERRICK/checkoff-exemption", params={"exempt": False}
    )
    assert off.json()["checkoff_exempt"] is False


def test_filing_an_agreement(filed):
    response = filed.post("/parties/farms/HOME-PLACE/agreements", json=an_agreement())
    assert response.status_code == 201
    body = response.json()
    assert body["is_balanced"] is True
    assert body["total_percent"] == "100"


def test_an_agreement_that_does_not_add_up_is_a_422(filed):
    response = filed.post(
        "/parties/farms/HOME-PLACE/agreements",
        json=an_agreement(shares=[{"party_code": "MERRICK", "percent": "60"}]),
    )
    assert response.status_code == 422
    assert response.json()["code"] == "party_error"


def test_an_agreement_with_no_shares_is_a_422(filed):
    response = filed.post(
        "/parties/farms/HOME-PLACE/agreements", json=an_agreement(shares=[])
    )
    assert response.status_code == 422


def test_an_agreement_naming_a_stranger_is_a_404(filed):
    response = filed.post(
        "/parties/farms/HOME-PLACE/agreements",
        json=an_agreement(
            shares=[
                {"party_code": "NOBODY", "percent": "50"},
                {"party_code": "MERRICK", "percent": "50"},
            ]
        ),
    )
    assert response.status_code == 404


def test_the_agreement_in_force_reads_back(filed):
    filed.post("/parties/farms/HOME-PLACE/agreements", json=an_agreement())
    response = filed.get(
        "/parties/farms/HOME-PLACE/agreement", params={"on": "2024-10-03"}
    )
    assert response.status_code == 200
    codes = {share["party_code"] for share in response.json()["shares"]}
    assert codes == {"BROCKMAN", "MERRICK"}


def test_nothing_in_force_before_the_agreement_is_a_404(filed):
    filed.post("/parties/farms/HOME-PLACE/agreements", json=an_agreement())
    response = filed.get(
        "/parties/farms/HOME-PLACE/agreement", params={"on": "2024-01-05"}
    )
    assert response.status_code == 404
    assert response.json()["code"] == "no_split_agreement"


def test_the_farms_a_producer_shares_in(filed):
    filed.post("/parties/farms/HOME-PLACE/agreements", json=an_agreement())
    filed.post(
        "/parties/farms/NORTH-EIGHTY/agreements",
        json=an_agreement(shares=[{"party_code": "MERRICK", "percent": "100"}]),
    )
    body = filed.get("/parties/MERRICK/farms").json()
    assert body["farms"] == ["HOME-PLACE", "NORTH-EIGHTY"]
    assert filed.get("/parties/HOLLIS/farms").json()["farms"] == []


def test_the_party_routes_are_documented(client):
    paths = client.get("/openapi.json").json()["paths"]
    assert "/parties" in paths
    assert "/parties/farms/{farm_code}/agreements" in paths
