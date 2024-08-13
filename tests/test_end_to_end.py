"""One harvest day, from the driveway to the cheque.

This is the test that would have caught every cross layer mistake the unit tests
did not: a rate resolved as of today instead of the ticket date, a discount
extended over the wrong bushel count, a checkoff levied per ticket instead of
per statement. It works one producer through four loads and asserts the numbers
a grain accountant would tie out by hand.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.domain.settlement import LineKind
from bushelworks.domain.tickets import Disposition, SplitRole, SplitShare
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.statement import render
from bushelworks.services.charges import Advance
from bushelworks.services.houses import house_rulebook
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

HARVEST = date(2024, 10, 3)
SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def house():
    return house_rulebook()


def load(number: str, gross: str, tare: str, moisture: str, **overrides):
    from bushelworks.domain.tickets import ScaleTicket

    base = dict(
        ticket_number=number,
        scale_date=HARVEST,
        location_code="HOME",
        commodity_code="CORN",
        gross_pounds=Decimal(gross),
        tare_pounds=Decimal(tare),
        disposition=Disposition.CASH_SALE,
        splits=[SplitShare("MERRICK", Decimal("100"))],
        truck_id="T-14",
        sample={
            Factor.TEST_WEIGHT: Decimal("56.5"),
            Factor.MOISTURE: Decimal(moisture),
            Factor.BCFM: Decimal("1.2"),
            Factor.DAMAGE_TOTAL: Decimal("1.0"),
        },
    )
    base.update(overrides)
    return ScaleTicket(**base)


def a_forward(number="F-2024-118", bushels="2000", price="4.6200"):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal(price),
    )


@pytest.fixture
def a_days_hauling(factory, house):
    """Four loads against a two thousand bushel forward."""
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_forward())
        for number, gross, tare, moisture in (
            ("A-10441", "78420", "26180", "18.2"),
            ("A-10442", "79100", "26180", "17.4"),
            ("A-10443", "77980", "26240", "15.8"),
            ("A-10444", "78650", "26180", "14.6"),
        ):
            record_load(uow, load(number, gross, tare, moisture), "MERRICK", house)
        uow.commit()
    return factory


def test_all_four_loads_are_on_file(a_days_hauling):
    with unit_of_work(a_days_hauling) as uow:
        numbers = [
            row.ticket_number for row in uow.tickets.awaiting_settlement("HOME")
        ]
    assert numbers == ["A-10441", "A-10442", "A-10443", "A-10444"]


def test_the_forward_fills_and_stops_taking_bushels(a_days_hauling):
    with unit_of_work(a_days_hauling) as uow:
        applied = uow.contracts.applied_bushels("F-2024-118")
        contract = uow.contracts.get("F-2024-118")
    assert applied == contract.maximum_bushels
    assert applied == Decimal("2040.0000")


def test_the_dry_load_neither_shrinks_nor_dries(a_days_hauling, house):
    from bushelworks.persistence.mappers import ticket_from_row
    from bushelworks.services.intake import drying_charge, receive

    with unit_of_work(a_days_hauling) as uow:
        row = uow.tickets.row("HOME", "A-10444")
        graded = receive(ticket_from_row(row), house)
        assert graded.shrink_percent == Decimal("0.0000")
        assert drying_charge(graded, house) == Decimal("0.00")


def test_the_wettest_load_shrinks_the_most(a_days_hauling, house):
    from bushelworks.persistence.mappers import ticket_from_row
    from bushelworks.services.intake import receive

    with unit_of_work(a_days_hauling) as uow:
        wet = receive(ticket_from_row(uow.tickets.row("HOME", "A-10441")), house)
        dry = receive(ticket_from_row(uow.tickets.row("HOME", "A-10443")), house)
    assert wet.shrink_percent > dry.shrink_percent
    assert wet.moisture_points == Decimal("3.2")
    assert dry.moisture_points == Decimal("0.8")


def test_one_statement_covers_the_whole_day(a_days_hauling, house):
    with unit_of_work(a_days_hauling) as uow:
        statement = settle_producer(
            uow, "MERRICK", "HOME", house, SETTLED, SPOT
        )
        uow.commit()
    assert statement.number == "S-2024-0001"
    assert len(statement.ticket_numbers) == 4
    assert len(statement.of_kind(LineKind.GROSS)) == 4
    statement.check_balances()


def test_the_statement_balances_to_the_cent(a_days_hauling, house):
    with unit_of_work(a_days_hauling) as uow:
        statement = settle_producer(
            uow,
            "MERRICK",
            "HOME",
            house,
            SETTLED,
            SPOT,
            advances=[Advance("2024-0912", Decimal("5000.00"), date(2024, 9, 12))],
        )
        uow.commit()
    assert statement.net == statement.gross - statement.deductions
    assert statement.subtotal(LineKind.ADVANCE) == Decimal("5000.00")


def test_the_checkoff_is_one_line_for_four_loads(a_days_hauling, house):
    with unit_of_work(a_days_hauling) as uow:
        statement = settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        uow.commit()
    levies = statement.of_kind(LineKind.ASSESSMENT)
    assert len(levies) == 1
    assert levies[0].code == "KSCORN"


def test_the_contracted_loads_are_paid_more_than_the_spot_ones(a_days_hauling, house):
    from bushelworks.persistence.mappers import ticket_from_row
    from bushelworks.services.intake import receive
    from bushelworks.services.settlement_run import price_of

    with unit_of_work(a_days_hauling) as uow:
        first = uow.tickets.row("HOME", "A-10441")
        last = uow.tickets.row("HOME", "A-10444")
        first_price = price_of(
            first, uow, SPOT, receive(ticket_from_row(first), house).settled_bushels
        )
        last_price = price_of(
            last, uow, SPOT, receive(ticket_from_row(last), house).settled_bushels
        )
    assert first_price == Decimal("4.6200")
    assert last_price == Decimal("4.3500")


def test_nothing_settles_twice(a_days_hauling, house):
    from bushelworks.services.settlement_run import NothingToSettle

    with unit_of_work(a_days_hauling) as uow:
        settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(a_days_hauling) as uow:
        with pytest.raises(NothingToSettle):
            settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)


def test_the_statement_prints_inside_eighty_columns(a_days_hauling, house):
    with unit_of_work(a_days_hauling) as uow:
        statement = settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        uow.commit()
    text = render(statement)
    assert all(len(line) <= 80 for line in text.split("\n"))
    assert "A-10441" in text
    assert "NET PAYABLE" in text


def test_a_crop_share_load_splits_the_net(a_days_hauling, house):
    from bushelworks.domain.settlement import split_settlement

    with unit_of_work(a_days_hauling) as uow:
        statement = settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        uow.commit()
    shares = [
        SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
        SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
    ]
    parts = split_settlement(statement, shares)
    assert sum(parts.values()) == statement.net


def test_a_voided_load_is_not_settled(factory, house):
    with unit_of_work(factory) as uow:
        record_load(uow, load("A-10501", "78420", "26180", "16.0"), "MERRICK", house)
        record_load(uow, load("A-10502", "78420", "26180", "16.0"), "MERRICK", house)
        uow.tickets.void("HOME", "A-10502", "reweighed")
        statement = settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        uow.commit()
    assert statement.ticket_numbers == ("A-10501",)


def test_a_second_producer_gets_the_next_number(a_days_hauling, house):
    with unit_of_work(a_days_hauling) as uow:
        record_load(
            uow,
            replace(
                load("A-10601", "78420", "26180", "16.0"),
                splits=[SplitShare("HOLLIS", Decimal("100"))],
            ),
            "HOLLIS",
            house,
        )
        first = settle_producer(uow, "MERRICK", "HOME", house, SETTLED, SPOT)
        second = settle_producer(uow, "HOLLIS", "HOME", house, SETTLED, SPOT)
        uow.commit()
    assert first.number == "S-2024-0001"
    assert second.number == "S-2024-0002"
    assert first.net != second.net
