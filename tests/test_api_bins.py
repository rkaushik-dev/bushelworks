from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.models import BinRow


@pytest.fixture
def client():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        for bin_id, capacity in (("B-01", "40000"), ("B-02", "100000")):
            session.add(
                BinRow(
                    bin_id=bin_id,
                    location_code="HOME",
                    capacity_bushels=Decimal(capacity),
                    is_aerated=True,
                )
            )
        session.commit()
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=factory,
    )
    with TestClient(app) as test_client:
        yield test_client


def a_load(**overrides) -> dict:
    body = {
        "commodity_code": "CORN",
        "bushels": "10000",
        "sample": [
            {"factor": "moisture", "value": "14.0"},
            {"factor": "test_weight", "value": "56.0"},
        ],
    }
    body.update(overrides)
    return body


def test_the_yard_lists_its_bins(client):
    bins = client.get("/bins/HOME").json()
    assert [item["bin_id"] for item in bins] == ["B-01", "B-02"]
    assert bins[0]["commodity_code"] is None


def test_a_bin_reads_back(client):
    body = client.get("/bins/HOME/B-02").json()
    assert body["capacity_bushels"] == "100000.0000"
    assert body["headroom"] == "97000.0000"


def test_a_missing_bin_is_a_404(client):
    response = client.get("/bins/HOME/B-99")
    assert response.status_code == 404
    assert response.json()["code"] == "bin_not_found"


def test_putting_a_load_away_picks_a_bin(client):
    response = client.post("/bins/HOME/loads", json=a_load(bushels="1000"))
    assert response.status_code == 201
    assert response.json()["bin_id"] == "B-01"


def test_a_load_can_name_its_bin(client):
    response = client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    assert response.json()["bin_id"] == "B-02"
    assert response.json()["bushels"] == "10000.0000"


def test_the_sample_becomes_the_bin_blend(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    body = client.get("/bins/HOME/B-02").json()
    assert body["moisture"] == "14.0000"
    assert body["test_weight"] == "56.0000"


def test_a_second_load_blends(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    client.post(
        "/bins/HOME/loads",
        json=a_load(
            bin_id="B-02",
            sample=[
                {"factor": "moisture", "value": "18.0"},
                {"factor": "test_weight", "value": "54.0"},
            ],
        ),
    )
    body = client.get("/bins/HOME/B-02").json()
    assert body["moisture"] == "16.0000"


def test_a_load_of_another_commodity_is_a_409(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    response = client.post(
        "/bins/HOME/loads", json=a_load(bin_id="B-02", commodity_code="SOYB")
    )
    assert response.status_code == 409


def test_a_load_too_big_for_the_yard_is_a_422(client):
    response = client.post("/bins/HOME/loads", json=a_load(bushels="500000"))
    assert response.status_code == 422
    assert response.json()["code"] == "bin_yard_error"


def test_drawing_takes_grain_out(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    response = client.post("/bins/HOME/B-02/draws", params={"bushels": "4000"})
    assert response.json()["bushels"] == "6000.0000"


def test_drawing_more_than_is_there_is_a_422(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    response = client.post("/bins/HOME/B-02/draws", params={"bushels": "20000"})
    assert response.status_code == 422


def test_a_reading_is_logged(client):
    response = client.post(
        "/bins/HOME/B-02/readings",
        json={"taken_at": "2024-11-27T06:00:00Z", "temperature_c": "12.4"},
    )
    assert response.status_code == 201
    assert response.json()["warm"] == "no"


def test_a_warm_reading_says_so(client):
    response = client.post(
        "/bins/HOME/B-02/readings",
        json={"taken_at": "2024-11-27T06:00:00Z", "temperature_c": "26.0"},
    )
    assert response.json()["warm"] == "yes"


def test_taking_a_bin_out_of_service(client):
    response = client.post(
        "/bins/HOME/B-01/out-of-service", params={"reason": "fan motor"}
    )
    assert response.json()["out_of_service"] is True
    assert response.json()["out_of_service_reason"] == "fan motor"


def test_taking_a_bin_out_twice_is_a_409(client):
    client.post("/bins/HOME/B-01/out-of-service", params={"reason": "fan motor"})
    response = client.post(
        "/bins/HOME/B-01/out-of-service", params={"reason": "again"}
    )
    assert response.status_code == 409


def test_the_sheet_prints(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    response = client.get("/bins/HOME/report/sheet")
    assert response.headers["content-type"].startswith("text/plain")
    assert "BIN SHEET  HOME" in response.text


def test_the_sheet_exports_as_a_csv(client):
    response = client.get("/bins/HOME/report/export")
    assert response.headers["content-type"].startswith("text/csv")
    assert "bins-HOME.csv" in response.headers["content-disposition"]


def test_the_summary_reports_the_yard(client):
    client.post("/bins/HOME/loads", json=a_load(bin_id="B-02"))
    body = client.get("/bins/HOME/report/summary").json()
    assert body["bushels"] == "10000.0000"
    assert body["room"] != "0"


def test_a_bin_called_summary_would_not_shadow_the_report(client):
    assert client.get("/bins/HOME/summary").status_code == 404
    assert client.get("/bins/HOME/report/summary").status_code == 200
