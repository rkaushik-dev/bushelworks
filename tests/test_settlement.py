from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.settlement import (
    LineKind,
    Settlement,
    SettlementBuilder,
    SettlementError,
    SettlementLine,
    combine,
    split_settlement,
)
from bushelworks.domain.tickets import SplitRole, SplitShare

SETTLED = date(2024, 10, 20)


def builder() -> SettlementBuilder:
    return SettlementBuilder("S-2024-0417", SETTLED, "MERRICK", "HOME")


def full_statement() -> Settlement:
    b = builder()
    b.reference_ticket("A-10441")
    b.reference_contract("F-2024-118")
    b.add_gross("GRAIN", "Corn, 932.8571 bu", Decimal("932.8571"), Decimal("4.3500"))
    b.add_charge(
        LineKind.DISCOUNT, "TW", "Light test weight", Decimal("18.66"), rate=Decimal("0.02")
    )
    b.add_charge(LineKind.DRYING, "DRY", "Drying 3.2 points", Decimal("139.93"))
    b.add_charge(LineKind.STORAGE, "STOR", "Storage to 20 Oct", Decimal("42.00"))
    b.add_charge(LineKind.ASSESSMENT, "KSCORN", "Kansas Corn Commission", Decimal("9.33"))
    b.add_charge(LineKind.ADVANCE, "ADV", "Advance 12 Oct", Decimal("1000.00"))
    return b.build()


def test_a_statement_balances_by_construction():
    statement = full_statement()
    statement.check_balances()
    assert statement.gross == Decimal("4057.93")
    assert statement.deductions == Decimal("1209.92")
    assert statement.net == Decimal("2848.01")


def test_lines_come_back_in_canonical_order():
    kinds = [line.kind for line in full_statement().lines]
    assert kinds == [
        LineKind.GROSS,
        LineKind.DISCOUNT,
        LineKind.DRYING,
        LineKind.STORAGE,
        LineKind.ASSESSMENT,
        LineKind.ADVANCE,
    ]


def test_reducing_kinds_carry_a_negative_sign():
    statement = full_statement()
    discount = statement.of_kind(LineKind.DISCOUNT)[0]
    assert discount.dollars == Decimal("18.66")
    assert discount.signed == Decimal("-18.66")


def test_gross_carries_a_positive_sign():
    statement = full_statement()
    assert statement.of_kind(LineKind.GROSS)[0].signed == Decimal("4057.93")


def test_value_after_discounts_ignores_services_and_levies():
    assert full_statement().value_after_discounts == Decimal("4039.27")


def test_a_line_may_not_be_stored_negative():
    with pytest.raises(SettlementError):
        SettlementLine(
            kind=LineKind.DISCOUNT,
            code="TW",
            description="nope",
            dollars=Decimal("-1.00"),
        )


def test_zero_lines_are_dropped_rather_than_printed():
    b = builder()
    b.add_gross("GRAIN", "Corn", Decimal("100"), Decimal("4.00"))
    b.add_charge(LineKind.DISCOUNT, "TW", "No discount", Decimal("0.00"))
    statement = b.build()
    assert len(statement.lines) == 1


def test_references_are_recorded_once():
    b = builder()
    b.add_gross("GRAIN", "Corn", Decimal("100"), Decimal("4.00"))
    b.reference_ticket("A-1").reference_ticket("A-1").reference_ticket("A-2")
    b.reference_contract("F-1").reference_contract("F-1")
    statement = b.build()
    assert statement.ticket_numbers == ("A-1", "A-2")
    assert statement.contract_numbers == ("F-1",)


def test_running_totals_are_visible_while_building():
    b = builder()
    b.add_gross("GRAIN", "Corn", Decimal("1000"), Decimal("4.00"))
    b.add_charge(LineKind.DISCOUNT, "TW", "Test weight", Decimal("20.00"))
    assert b.running_gross == Decimal("4000.00")
    assert b.running_discounts == Decimal("20.00")


def test_subtotals_by_kind():
    statement = full_statement()
    assert statement.subtotal(LineKind.DRYING) == Decimal("139.93")
    assert statement.subtotal(LineKind.DEDUCTION) == Decimal("0.00")


def test_a_tampered_statement_fails_its_own_balance_check():
    statement = full_statement()
    broken = Settlement(
        number=statement.number,
        settled_on=statement.settled_on,
        party_id=statement.party_id,
        location_code=statement.location_code,
        lines=statement.lines[:-1],
    )
    broken.check_balances()
    assert broken.net == Decimal("3848.01")


def test_split_across_a_crop_share_lease():
    statement = full_statement()
    shares = [
        SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
        SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
    ]
    parts = split_settlement(statement, shares)
    assert sum(parts.values()) == statement.net
    assert parts["BROCKMAN"] == Decimal("712.00")


def test_split_of_an_odd_penny_still_adds_to_the_net():
    b = builder()
    b.add_gross("GRAIN", "Corn", Decimal("1000.0000"), Decimal("4.0001"))
    statement = b.build()
    shares = [SplitShare("A", Decimal("50")), SplitShare("B", Decimal("50"))]
    parts = split_settlement(statement, shares)
    assert sum(parts.values()) == statement.net


def test_splitting_across_nobody_is_refused():
    with pytest.raises(SettlementError):
        split_settlement(full_statement(), [])


def test_combining_statements_adds_the_nets():
    one = full_statement()
    two = full_statement()
    assert combine([one, two]) == Decimal("5696.02")
