from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.settlement import LineKind
from bushelworks.services.intake import receive
from bushelworks.services.settling import (
    Advance,
    DiscountBasis,
    SettlementRefused,
    StorageCharge,
    settle_load,
)

SETTLED = date(2024, 10, 20)
PRICE = Decimal("4.3500")


def test_a_plain_cash_settlement(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    # 897.6324 bu at 4.35
    assert statement.gross == Decimal("3904.70")
    statement.check_balances()


def test_the_statement_names_its_ticket(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    assert statement.ticket_numbers == ("A-10441",)
    assert statement.contract_numbers == ()


def test_a_contract_number_is_carried_onto_the_statement(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1", load, PRICE, rules, "MERRICK", SETTLED, contract_number="F-2024-118"
    )
    assert statement.contract_numbers == ("F-2024-118",)


def test_drying_appears_as_its_own_line(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    drying = statement.of_kind(LineKind.DRYING)
    assert len(drying) == 1
    assert drying[0].dollars == Decimal("102.33")


def test_quality_discounts_are_itemised_per_factor(rules, corn_ticket):
    dirty = replace(
        corn_ticket,
        sample={
            **corn_ticket.sample,
            Factor.TEST_WEIGHT: Decimal("53.4"),
            Factor.BCFM: Decimal("3.35"),
        },
    )
    load = receive(dirty, rules)
    statement = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    codes = [line.code for line in statement.of_kind(LineKind.DISCOUNT)]
    assert codes == ["BCFM", "TEST_WEIGHT"]


def test_discounts_can_be_taken_on_the_wet_bushels_instead(rules, corn_ticket):
    dirty = replace(
        corn_ticket,
        sample={**corn_ticket.sample, Factor.TEST_WEIGHT: Decimal("53.4")},
    )
    load = receive(dirty, rules)
    dry = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    wet = settle_load(
        "S-2",
        load,
        PRICE,
        rules,
        "MERRICK",
        SETTLED,
        discount_basis=DiscountBasis.WET,
    )
    assert wet.subtotal(LineKind.DISCOUNT) > dry.subtotal(LineKind.DISCOUNT)


def test_the_kansas_corn_checkoff_is_withheld(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)
    levies = statement.of_kind(LineKind.ASSESSMENT)
    assert [line.code for line in levies] == ["KSCORN"]
    assert levies[0].dollars == Decimal("8.98")


def test_a_producer_on_file_as_exempt_is_not_charged(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1", load, PRICE, rules, "MERRICK", SETTLED, exempt_assessments=["KSCORN"]
    )
    assert statement.of_kind(LineKind.ASSESSMENT) == ()


def test_advances_are_recovered_after_everything_else(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1",
        load,
        PRICE,
        rules,
        "MERRICK",
        SETTLED,
        advances=[Advance("2024-0912", Decimal("1500.00"), date(2024, 9, 12))],
    )
    assert statement.lines[-1].kind is LineKind.ADVANCE
    assert statement.net == Decimal("2293.39")


def test_an_advance_of_nothing_is_refused():
    with pytest.raises(SettlementRefused):
        Advance("x", Decimal("0"), date(2024, 9, 12))


def test_storage_charges_come_through_as_lines(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1",
        load,
        PRICE,
        rules,
        "MERRICK",
        SETTLED,
        storage=[StorageCharge("L-1", Decimal("42.00"), date(2024, 10, 20))],
    )
    assert statement.subtotal(LineKind.STORAGE) == Decimal("42.00")
    assert statement.of_kind(LineKind.STORAGE)[0].code == "STOR-L-1"


def test_a_negative_price_is_refused(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    with pytest.raises(SettlementRefused):
        settle_load("S-1", load, Decimal("-1"), rules, "MERRICK", SETTLED)


def test_a_settlement_below_the_house_minimum_is_refused(rules, corn_ticket):
    from bushelworks.domain.rulebook import HousePolicy

    rules.policy = HousePolicy(state_code="KS", minimum_settlement=Decimal("10000.00"))
    load = receive(corn_ticket, rules)
    with pytest.raises(SettlementRefused):
        settle_load("S-1", load, PRICE, rules, "MERRICK", SETTLED)


def test_every_statement_balances(rules, corn_ticket):
    dirty = replace(
        corn_ticket,
        sample={
            **corn_ticket.sample,
            Factor.TEST_WEIGHT: Decimal("51.0"),
            Factor.BCFM: Decimal("4.4"),
            Factor.DAMAGE_TOTAL: Decimal("7.5"),
        },
    )
    load = receive(dirty, rules)
    statement = settle_load(
        "S-1",
        load,
        PRICE,
        rules,
        "MERRICK",
        SETTLED,
        advances=[Advance("A", Decimal("500.00"), date(2024, 9, 1))],
        storage=[StorageCharge("L-1", Decimal("12.00"), SETTLED)],
    )
    statement.check_balances()
    assert statement.net == Decimal(
        str(statement.gross - statement.deductions)
    ).quantize(Decimal("0.01"))
