"""The ledger over http.

The one that matters most is the last class. ``/ledger/journal/source/...`` sits
under a segment rather than at ``/ledger/journal/{number}`` because the second
shape swallows it, and this repository has shipped that bug twice. The test
asks the router which route actually matches rather than trusting the order the
declarations happen to be in.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.domain.ledger import standard_chart
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.dayend import run_close
from bushelworks.services.monthend import close_period, file_year
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

HARVEST = date(2024, 10, 3)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    return session_factory(engine)


@pytest.fixture
def books(factory, rules, corn_ticket):
    """A house with a chart, a filed year and one posted settlement."""
    with unit_of_work(factory) as uow:
        uow.ledger.file_chart(standard_chart())
        file_year(uow, 2024)
        for month in range(1, 10):
            close_period(uow, f"2024-{month:02d}", "office", on=date(2024, 10, 1))
        for number in ("A-10441", "A-10442"):
            record_load(
                uow, replace(corn_ticket, ticket_number=number), "MERRICK", rules
            )
        uow.commit()
    with unit_of_work(factory) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(factory) as uow:
        run_close(uow, HARVEST)
        uow.commit()
    return factory


@pytest.fixture
def client(books):
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=books,
    )
    with TestClient(app) as test_client:
        yield test_client


class TestChart:
    def test_the_chart_comes_back_in_code_order(self, client) -> None:
        found = client.get("/ledger/accounts")
        assert found.status_code == 200
        codes = [row["code"] for row in found.json()]
        assert codes == sorted(codes)
        assert "1210" in codes

    def test_a_heading_says_it_is_one(self, client) -> None:
        rows = {row["code"]: row for row in client.get("/ledger/accounts").json()}
        assert rows["1200"]["is_heading"]
        assert not rows["1210"]["is_heading"]
        assert rows["1210"]["parent"] == "1200"

    def test_the_type_comes_back_as_its_word(self, client) -> None:
        rows = {row["code"]: row for row in client.get("/ledger/accounts").json()}
        assert rows["4100"]["type"] == "revenue"
        assert rows["2100"]["type"] == "liability"


class TestTrialBalance:
    def test_it_balances(self, client) -> None:
        found = client.get("/ledger/trial-balance", params={"through": "2024-10-31"})
        assert found.status_code == 200
        body = found.json()
        assert body["balances"]
        assert body["debits"] == body["credits"]

    def test_the_amounts_come_back_as_strings(self, client) -> None:
        body = client.get(
            "/ledger/trial-balance", params={"through": "2024-10-31"}
        ).json()
        assert isinstance(body["debits"], str)
        assert isinstance(body["rows"][0]["balance"], str)

    def test_empty_accounts_are_left_out_by_default(self, client) -> None:
        body = client.get(
            "/ledger/trial-balance", params={"through": "2024-10-31"}
        ).json()
        codes = {row["code"] for row in body["rows"]}
        assert "4500" not in codes
        assert "2100" in codes

    def test_the_auditor_can_ask_for_all_of_them(self, client) -> None:
        body = client.get(
            "/ledger/trial-balance",
            params={"through": "2024-10-31", "include_empty": "true"},
        ).json()
        codes = {row["code"] for row in body["rows"]}
        assert "4500" in codes

    def test_a_since_date_makes_it_a_period(self, client) -> None:
        body = client.get(
            "/ledger/trial-balance",
            params={"through": "2024-10-31", "since": "2024-11-01"},
        ).json()
        assert body["rows"] == []

    def test_a_day_before_anything_posted_is_empty(self, client) -> None:
        body = client.get(
            "/ledger/trial-balance", params={"through": "2024-09-30"}
        ).json()
        assert body["rows"] == []
        assert body["balances"]


class TestJournal:
    def test_a_span_comes_back(self, client) -> None:
        found = client.get(
            "/ledger/journal",
            params={"opens": "2024-10-01", "closes": "2024-10-31"},
        )
        assert found.status_code == 200
        body = found.json()
        assert len(body) == 1
        assert body[0]["balances"]
        assert body[0]["source"] == "settlement"

    def test_the_legs_come_with_it(self, client) -> None:
        body = client.get(
            "/ledger/journal",
            params={"opens": "2024-10-01", "closes": "2024-10-31"},
        ).json()
        legs = body[0]["legs"]
        assert len(legs) >= 2
        assert {leg["side"] for leg in legs} == {"debit", "credit"}

    def test_one_entry_can_be_asked_for_by_number(self, client) -> None:
        listed = client.get(
            "/ledger/journal",
            params={"opens": "2024-10-01", "closes": "2024-10-31"},
        ).json()
        number = listed[0]["number"]
        found = client.get(f"/ledger/journal/entries/{number}")
        assert found.status_code == 200
        assert found.json()["number"] == number

    def test_an_entry_nobody_posted_is_a_404(self, client) -> None:
        found = client.get("/ledger/journal/entries/J-NOPE")
        assert found.status_code == 404
        assert found.json()["code"] == "journal_entry_not_found"

    def test_the_trail_for_a_document_comes_back(self, client) -> None:
        found = client.get("/ledger/journal/source/settlement/S-2024-0001")
        assert found.status_code == 200
        assert len(found.json()) == 1

    def test_a_document_with_no_entries_is_an_empty_list(self, client) -> None:
        found = client.get("/ledger/journal/source/settlement/S-2024-9999")
        assert found.status_code == 200
        assert found.json() == []

    def test_the_limit_is_honoured(self, client) -> None:
        found = client.get(
            "/ledger/journal",
            params={"opens": "2024-10-01", "closes": "2024-10-31", "limit": 1},
        )
        assert len(found.json()) == 1


class TestPeriods:
    def test_every_month_is_listed(self, client) -> None:
        found = client.get("/ledger/periods")
        assert found.status_code == 200
        assert len(found.json()) == 12

    def test_the_open_ones_can_be_asked_for(self, client) -> None:
        body = client.get("/ledger/periods", params={"open_only": "true"}).json()
        assert [row["code"] for row in body] == [
            "2024-10",
            "2024-11",
            "2024-12",
        ]

    def test_a_closed_month_says_who_closed_it(self, client) -> None:
        body = client.get("/ledger/periods").json()
        september = next(row for row in body if row["code"] == "2024-09")
        assert september["closed"]
        assert september["closed_by"] == "office"

    def test_reviewing_a_month_writes_nothing(self, client) -> None:
        found = client.get("/ledger/periods/2024-10")
        assert found.status_code == 200
        body = found.json()
        assert body["is_clean"]
        assert body["settlements"] == 1
        assert body["unposted"] == []
        assert client.get("/ledger/periods").json()[9]["closed"] is False

    def test_a_month_nobody_filed_is_a_404(self, client) -> None:
        found = client.get("/ledger/periods/2029-04")
        assert found.status_code == 404

    def test_a_month_closes_over_http(self, client) -> None:
        found = client.post(
            "/ledger/periods/2024-10/close",
            json={"closed_by": "R. Merrick", "on": "2024-11-06"},
        )
        assert found.status_code == 200
        assert found.json()["closed"]
        listed = client.get("/ledger/periods", params={"open_only": "true"}).json()
        assert "2024-10" not in [row["code"] for row in listed]

    def test_a_month_that_will_not_close_is_a_conflict(self, client) -> None:
        found = client.post(
            "/ledger/periods/2024-11/close", json={"closed_by": "office"}
        )
        assert found.status_code == 409

    def test_force_gets_it_closed(self, client) -> None:
        found = client.post(
            "/ledger/periods/2024-11/close",
            json={"closed_by": "office", "force": True},
        )
        assert found.status_code == 200
        assert found.json()["closed"]
        assert not found.json()["is_clean"]

    def test_a_closed_month_reopens_with_a_reason(self, client) -> None:
        client.post(
            "/ledger/periods/2024-10/close",
            json={"closed_by": "office", "on": "2024-11-06"},
        )
        found = client.post(
            "/ledger/periods/2024-10/reopen",
            json={"reason": "a load was keyed late"},
        )
        assert found.status_code == 200
        assert not found.json()["closed"]

    def test_reopening_without_a_reason_is_refused(self, client) -> None:
        client.post(
            "/ledger/periods/2024-10/close",
            json={"closed_by": "office", "on": "2024-11-06"},
        )
        found = client.post("/ledger/periods/2024-10/reopen", json={"reason": "  "})
        assert found.status_code == 422

    def test_a_year_can_be_filed(self, client) -> None:
        found = client.post("/ledger/years/2025")
        assert found.status_code == 200
        assert len(found.json()) == 12

    def test_filing_the_same_year_twice_is_a_conflict(self, client) -> None:
        client.post("/ledger/years/2025")
        assert client.post("/ledger/years/2025").status_code == 409


class TestNoWriteIntoTheJournal:
    def test_there_is_no_route_that_posts_an_entry(self, client) -> None:
        paths = {
            (method, route.path)
            for route in client.app.routes
            for method in getattr(route, "methods", set())
        }
        writes = {
            path
            for method, path in paths
            if method in {"POST", "PUT", "PATCH", "DELETE"}
            and path.startswith("/ledger/journal")
        }
        assert writes == set()

    def test_posting_to_the_journal_is_not_allowed(self, client) -> None:
        assert client.post("/ledger/journal", json={}).status_code == 405


class TestPathsDoNotShadowEachOther:
    def test_the_source_trail_is_not_read_as_an_entry_number(self, client) -> None:
        found = client.get("/ledger/journal/source/settlement/S-2024-0001")
        assert found.status_code == 200
        assert isinstance(found.json(), list)

    def test_the_trial_balance_is_not_read_as_a_period(self, client) -> None:
        found = client.get("/ledger/trial-balance", params={"through": "2024-10-31"})
        assert found.status_code == 200
        assert "rows" in found.json()
