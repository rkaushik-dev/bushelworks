from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer
from bushelworks.services.settling import Advance
from bushelworks.services.tax import (
    REPORTING_THRESHOLD,
    TaxError,
    near_threshold,
    payee_year,
    payees,
    reportable,
    working_file,
)

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def a_year(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(
            uow,
            "MERRICK",
            "HOME",
            rules,
            SETTLED,
            SPOT,
            advances=[Advance("A", Decimal("1000.00"), date(2024, 9, 12))],
        )
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "HOLLIS", rules
        )
        settle_producer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    return factory


def test_a_year_that_cannot_be_reported_on(a_year):
    with unit_of_work(a_year) as uow:
        with pytest.raises(TaxError):
            payee_year(uow, "MERRICK", 1899)


def test_the_reportable_figure_is_gross_less_the_price_reductions(a_year):
    with unit_of_work(a_year) as uow:
        payee = payee_year(uow, "MERRICK", 2024)
    assert payee.gross == Decimal("3904.70")
    assert payee.reductions > Decimal("0")
    assert payee.reportable == payee.gross - payee.reductions


def test_an_advance_does_not_reduce_the_reportable_figure(a_year):
    with unit_of_work(a_year) as uow:
        with_advance = payee_year(uow, "MERRICK", 2024)
        without = payee_year(uow, "HOLLIS", 2024)
    assert with_advance.advances_recovered == Decimal("1000.00")
    assert without.advances_recovered == Decimal("0.00")
    assert with_advance.reportable == without.reportable


def test_the_settlement_count_is_carried(a_year):
    with unit_of_work(a_year) as uow:
        assert payee_year(uow, "MERRICK", 2024).settlements == 1


def test_a_producer_over_the_threshold_is_reportable(a_year):
    with unit_of_work(a_year) as uow:
        payee = payee_year(uow, "MERRICK", 2024)
    assert payee.reportable > REPORTING_THRESHOLD
    assert payee.is_reportable
    assert payee.shortfall == Decimal("0.00")


def test_a_producer_with_nothing_in_the_year_is_empty(a_year):
    with unit_of_work(a_year) as uow:
        payee = payee_year(uow, "MERRICK", 2023)
    assert payee.gross == Decimal("0.00")
    assert not payee.is_reportable
    assert payee.shortfall == REPORTING_THRESHOLD


def test_the_year_boundary_is_respected(a_year):
    with unit_of_work(a_year) as uow:
        assert payee_year(uow, "MERRICK", 2025).settlements == 0


def test_the_payee_list_is_biggest_first(a_year):
    with unit_of_work(a_year) as uow:
        found = payees(uow, 2024)
    assert [payee.party_code for payee in found] == ["HOLLIS", "MERRICK"]
    assert found[0].reportable >= found[1].reportable


def test_the_reportable_list_drops_the_small_ones(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        row = uow.settlements.row("S-2024-0001")
        line = row.lines[0]
        line.dollars = Decimal("100.00")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert reportable(uow, 2024) == []


def test_near_threshold_finds_the_ones_that_nearly_made_it(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        row = uow.settlements.row("S-2024-0001")
        row.lines[0].dollars = Decimal("650.00")
        uow.commit()
    with unit_of_work(factory) as uow:
        found = near_threshold(uow, 2024, within=Decimal("500.00"))
    assert [payee.party_code for payee in found] == ["MERRICK"]


def test_a_voided_settlement_is_left_out(a_year):
    with unit_of_work(a_year) as uow:
        uow.settlements.row("S-2024-0001").voided = True
        uow.commit()
    with unit_of_work(a_year) as uow:
        assert payee_year(uow, "MERRICK", 2024).settlements == 0


def test_the_working_file_has_a_row_for_everybody(a_year):
    with unit_of_work(a_year) as uow:
        text = working_file(uow, 2024)
    rows = text.strip().split("\n")
    assert rows[0].startswith("party_code,year,settlements")
    assert len(rows) == 3


def test_the_working_file_says_who_is_reportable(a_year):
    with unit_of_work(a_year) as uow:
        text = working_file(uow, 2024)
    assert ",yes" in text


def test_an_empty_year_still_writes_a_header(a_year):
    with unit_of_work(a_year) as uow:
        text = working_file(uow, 2023)
    assert text.strip() == (
        "party_code,year,settlements,gross,reductions,reportable,"
        "advances_recovered,is_reportable"
    )
