from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.domain.tickets import Disposition
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.scalehouse import (
    ScaleHouseError,
    commodities_handled,
    price_for,
    record_load,
)

WINDOW = DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30))


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract(number="F-2024-118", bushels="20000", price="4.6200", **kw):
    base = dict(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=WINDOW,
        flat_price=Decimal(price),
    )
    base.update(kw)
    return PurchaseContract(**base)


def test_a_load_with_no_contracts_is_recorded_and_left_open(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    assert recorded.ticket_number == "A-10441"
    assert recorded.applications == ()
    assert recorded.load.dry_bushels == Decimal("897.6324")


def test_a_load_lands_on_an_open_contract(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    assert recorded.contract_numbers == ("F-2024-118",)
    assert recorded.contracted_bushels == Decimal("897.6324")
    assert recorded.open_bushels == Decimal("0")


def test_the_application_row_is_written(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.contracts.applied_bushels("F-2024-118") == Decimal("897.6324")


def test_a_second_load_stacks_on_the_same_contract(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.contracts.applied_bushels("F-2024-118") == Decimal("1795.2648")


def test_a_contract_fills_and_the_rest_goes_to_storage(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    assert recorded.contracted_bushels == Decimal("510.0000")
    assert recorded.open_bushels == Decimal("387.6324")


def test_a_named_contract_is_the_only_one_considered(factory, rules, corn_ticket):
    named = replace(
        corn_ticket,
        disposition=Disposition.CONTRACT,
        contract_number="F-2024-119",
    )
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        uow.contracts.add(a_contract(number="F-2024-119", price="4.9000"))
        recorded = record_load(uow, named, "MERRICK", rules)
        uow.commit()
    assert recorded.contract_numbers == ("F-2024-119",)


def test_naming_a_contract_that_is_not_open_is_refused(factory, rules, corn_ticket):
    named = replace(
        corn_ticket,
        disposition=Disposition.CONTRACT,
        contract_number="F-2024-999",
    )
    with unit_of_work(factory) as uow:
        with pytest.raises(ScaleHouseError):
            record_load(uow, named, "MERRICK", rules)


def test_a_storage_load_is_not_applied_to_anything(factory, rules, corn_ticket):
    stored = replace(corn_ticket, disposition=Disposition.STORE)
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        recorded = record_load(uow, stored, "MERRICK", rules)
        uow.commit()
    assert recorded.applications == ()


def test_another_producers_contract_is_not_touched(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(party_id="HOLLIS"))
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    assert recorded.applications == ()


def test_a_load_priced_off_one_contract(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        assert price_for(recorded, uow) == Decimal("4.6200")


def test_a_split_load_blends_the_contract_and_the_spot_price(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        blended = price_for(recorded, uow, spot_price=Decimal("4.3500"))
    assert Decimal("4.3500") < blended < Decimal("4.6200")


def test_open_bushels_with_no_spot_price_is_refused(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        with pytest.raises(ScaleHouseError):
            price_for(recorded, uow)


def test_an_unapplied_load_prices_at_spot(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        assert price_for(recorded, uow, Decimal("4.3500")) == Decimal("4.3500")


def test_an_unapplied_load_with_no_spot_price_is_refused(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        recorded = record_load(uow, corn_ticket, "MERRICK", rules)
        with pytest.raises(ScaleHouseError):
            price_for(recorded, uow)


def test_recording_needs_an_open_unit_of_work(factory, rules, corn_ticket):
    uow = unit_of_work(factory)
    with pytest.raises(Exception):
        record_load(uow, corn_ticket, "MERRICK", rules)


def test_the_touch_screen_lists_only_what_the_site_can_settle(rules):
    handled = commodities_handled(rules, date(2024, 10, 3), "HOME")
    assert set(handled) == {"CORN", "HRW", "SOYB"}
    assert "MILO" not in handled
