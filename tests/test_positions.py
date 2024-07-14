from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.deferred import deliver
from bushelworks.services.positions import (
    build_report,
    contracted_bushels,
    line_for,
    received_bushels,
    unhedged,
)
from bushelworks.services.scalehouse import record_load
from bushelworks.services.warehousing import store

AS_OF = date(2024, 11, 1)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract(number="F-2024-118", bushels="20000"):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )


@pytest.fixture
def busy(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("4000"), date(2024, 10, 5))
        deliver(uow, "D-1", "HOLLIS", "CORN", "HOME", Decimal("6000"), date(2024, 10, 8))
        uow.commit()
    return factory


def test_received_bushels_counts_every_live_ticket(busy):
    with unit_of_work(busy) as uow:
        assert received_bushels(uow, "CORN", AS_OF) == Decimal("1865.7143")


def test_received_bushels_ignores_voids(busy):
    with unit_of_work(busy) as uow:
        uow.tickets.void("HOME", "A-10442", "reweighed")
        uow.commit()
    with unit_of_work(busy) as uow:
        assert received_bushels(uow, "CORN", AS_OF) == Decimal("932.8571")


def test_received_bushels_stops_at_the_day_asked_about(busy):
    with unit_of_work(busy) as uow:
        assert received_bushels(uow, "CORN", date(2024, 10, 2)) == Decimal("0.0000")


def test_a_commodity_with_no_tickets_is_zero(busy):
    with unit_of_work(busy) as uow:
        assert received_bushels(uow, "SOYB", AS_OF) == Decimal("0.0000")


def test_contracted_bushels_is_what_has_not_arrived(busy):
    with unit_of_work(busy) as uow:
        applied = uow.contracts.applied_bushels("F-2024-118")
        outstanding = contracted_bushels(uow, "CORN", AS_OF)
    assert outstanding == Decimal("20000.0000") - applied


def test_an_overfilled_contract_does_not_go_short(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        record_load(uow, corn_ticket, "MERRICK", rules)
        assert contracted_bushels(uow, "CORN", AS_OF) == Decimal("0.0000")


def test_a_closed_window_drops_off_the_contracted_total(busy):
    with unit_of_work(busy) as uow:
        assert contracted_bushels(uow, "CORN", date(2024, 12, 15)) == Decimal("0.0000")


def test_the_line_separates_storage_from_deferred(busy):
    with unit_of_work(busy) as uow:
        line = line_for(uow, "CORN", AS_OF)
    assert line.storage_obligation == Decimal("4000.0000")
    assert line.deferred_price == Decimal("6000.0000")


def test_owned_is_what_is_left_after_the_obligations(busy):
    with unit_of_work(busy) as uow:
        line = line_for(uow, "CORN", AS_OF)
    assert line.owned == line.on_hand - Decimal("10000.0000")


def test_deferred_bushels_come_back_into_the_net_long(busy):
    with unit_of_work(busy) as uow:
        line = line_for(uow, "CORN", AS_OF)
    assert line.net_long == line.owned + Decimal("6000.0000") + line.purchased_forward


def test_shipping_takes_bushels_off_what_is_on_hand(busy):
    with unit_of_work(busy) as uow:
        plain = line_for(uow, "CORN", AS_OF)
        shipped = line_for(uow, "CORN", AS_OF, shipped=Decimal("1000"))
    assert shipped.on_hand == plain.on_hand - Decimal("1000")


def test_on_hand_never_goes_negative(busy):
    with unit_of_work(busy) as uow:
        line = line_for(uow, "CORN", AS_OF, shipped=Decimal("999999"))
    assert line.on_hand == Decimal("0")


def test_a_sale_makes_the_house_short(busy):
    with unit_of_work(busy) as uow:
        line = line_for(uow, "CORN", AS_OF, sold_forward=Decimal("100000"))
    assert line.is_short


def test_the_report_skips_commodities_with_nothing_in_them(busy):
    with unit_of_work(busy) as uow:
        report = build_report(uow, AS_OF)
    assert report.commodities() == ("CORN",)


def test_the_report_can_be_asked_for_named_commodities(busy):
    with unit_of_work(busy) as uow:
        report = build_report(uow, AS_OF, commodities=("CORN", "SOYB"))
    assert report.commodities() == ("CORN",)


def test_the_report_is_dated(busy):
    with unit_of_work(busy) as uow:
        report = build_report(uow, AS_OF)
    assert report.as_of == AS_OF
    assert all(line.as_of == AS_OF for line in report.lines)


def test_the_unhedged_gap_is_the_position_less_what_is_on(busy):
    with unit_of_work(busy) as uow:
        report = build_report(uow, AS_OF)
        gaps = unhedged(report, {"CORN": Decimal("5000")})
    assert gaps["CORN"] == report.by_commodity("CORN").net_long - Decimal("5000")


def test_a_commodity_with_no_hedge_shows_the_whole_position(busy):
    with unit_of_work(busy) as uow:
        report = build_report(uow, AS_OF)
        gaps = unhedged(report, {})
    assert gaps["CORN"] == report.by_commodity("CORN").net_long


def test_a_site_filter_narrows_the_line(busy):
    with unit_of_work(busy) as uow:
        home = line_for(uow, "CORN", AS_OF, location_code="HOME")
        luray = line_for(uow, "CORN", AS_OF, location_code="LURAY")
    assert home.on_hand > Decimal("0")
    assert luray.on_hand == Decimal("0")
