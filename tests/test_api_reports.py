import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

CROP_YEAR = {"year": 2024}


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


def deliver_and_settle(client, party_code, count, start):
    client.post(
        "/parties",
        json={"code": party_code, "name": f"{party_code.title()} Farms"},
    )
    for index in range(count):
        client.post(
            "/tickets",
            json=a_ticket(ticket_number=f"A-{start + index}", party_code=party_code),
        )
    return client.post(
        "/settlements",
        json={
            "party_code": party_code,
            "location_code": "HOME",
            "settled_on": "2024-10-20",
            "spot_price": "4.3500",
        },
    )


@pytest.fixture
def settled(client):
    deliver_and_settle(client, "MERRICK", 3, 10441)
    deliver_and_settle(client, "HOLLIS", 1, 20441)
    return client


def test_a_quiet_house_has_an_empty_table(client):
    body = client.get("/reports/patronage", params=CROP_YEAR).json()
    assert body["lines"] == []
    assert body["bushels"] == "0.0000"


def test_the_table_lists_the_producers_who_settled(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    assert {line["party_code"] for line in body["lines"]} == {"MERRICK", "HOLLIS"}


def test_the_table_is_biggest_first(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    assert body["lines"][0]["party_code"] == "MERRICK"


def test_the_table_carries_the_span_of_the_crop_year(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    assert body["opens"] == "2024-09-01"
    assert body["closes"] == "2025-08-31"


def test_a_named_span_is_used_instead_of_a_crop_year(settled):
    body = settled.get(
        "/reports/patronage",
        params={"opens": "2024-10-01", "closes": "2024-10-31"},
    ).json()
    assert body["opens"] == "2024-10-01"
    assert len(body["lines"]) == 2


def test_a_span_that_misses_the_settlements_is_empty(settled):
    body = settled.get(
        "/reports/patronage",
        params={"opens": "2024-11-01", "closes": "2024-11-30"},
    ).json()
    assert body["lines"] == []


def test_half_a_span_is_refused(settled):
    response = settled.get("/reports/patronage", params={"opens": "2024-10-01"})
    assert response.status_code == 422


def test_quantities_come_back_as_strings(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    assert isinstance(body["lines"][0]["bushels"], str)
    assert isinstance(body["lines"][0]["margin_per_bushel"], str)


def test_the_shares_add_to_a_hundred(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    total = sum(float(line["share_percent"]) for line in body["lines"])
    assert abs(total - 100.0) < 0.01


def test_everybody_is_a_member_by_default(settled):
    body = settled.get("/reports/patronage", params=CROP_YEAR).json()
    assert all(line["is_member"] for line in body["lines"])


def test_a_roll_marks_the_others_as_outsiders(settled):
    body = settled.get(
        "/reports/patronage", params={**CROP_YEAR, "member": ["MERRICK"]}
    ).json()
    outsider = next(line for line in body["lines"] if line["party_code"] == "HOLLIS")
    assert not outsider["is_member"]


def test_another_site_has_nothing_on_it(settled):
    body = settled.get(
        "/reports/patronage", params={**CROP_YEAR, "location_code": "LURAY"}
    ).json()
    assert body["lines"] == []
    assert body["location_code"] == "LURAY"


def test_the_printed_page_is_plain_text(settled):
    response = settled.get("/reports/patronage/table", params=CROP_YEAR)
    assert response.headers["content-type"].startswith("text/plain")
    assert "PATRONAGE BY BUSHELS" in response.text


def test_the_printed_page_fits_eighty_columns(settled):
    response = settled.get("/reports/patronage/table", params=CROP_YEAR)
    assert all(len(line) <= 80 for line in response.text.splitlines())


def test_the_page_can_be_ranked_on_margin(settled):
    response = settled.get(
        "/reports/patronage/table", params={**CROP_YEAR, "order": "margin"}
    )
    assert "PATRONAGE BY MARGIN" in response.text


def test_an_order_nobody_knows_is_refused(settled):
    response = settled.get(
        "/reports/patronage/table", params={**CROP_YEAR, "order": "sideways"}
    )
    assert response.status_code == 422


def test_the_export_comes_back_as_csv(settled):
    response = settled.get("/reports/patronage/export", params=CROP_YEAR)
    assert response.headers["content-type"].startswith("text/csv")
    assert response.text.splitlines()[0].startswith("party_code,")


def test_the_export_is_named_after_the_year(settled):
    response = settled.get("/reports/patronage/export", params=CROP_YEAR)
    assert "patronage-2024.csv" in response.headers["content-disposition"]


def test_the_export_has_a_row_for_each_producer(settled):
    response = settled.get("/reports/patronage/export", params=CROP_YEAR)
    assert len(response.text.strip().splitlines()) == 3


def test_an_allocation_splits_the_pool(settled):
    body = settled.get(
        "/reports/patronage/allocation", params={**CROP_YEAR, "pool": "10000.00"}
    ).json()
    assert sum(float(share) for share in body.values()) == pytest.approx(10000.0)


def test_a_non_member_gets_nothing(settled):
    body = settled.get(
        "/reports/patronage/allocation",
        params={**CROP_YEAR, "pool": "10000.00", "member": ["MERRICK"]},
    ).json()
    assert set(body) == {"MERRICK"}


def test_a_share_under_the_minimum_is_dropped(settled):
    body = settled.get(
        "/reports/patronage/allocation",
        params={**CROP_YEAR, "pool": "10.00", "minimum": "9.00"},
    ).json()
    assert "HOLLIS" not in body


def test_the_board_reports_are_office_only():
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
        looking = client.get(
            "/reports/patronage", params=CROP_YEAR, headers={"X-API-Key": "look"}
        )
        allowed = client.get(
            "/reports/patronage", params=CROP_YEAR, headers={"X-API-Key": "office"}
        )
    assert looking.status_code == 403
    assert allowed.status_code == 200
