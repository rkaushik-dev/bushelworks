from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.hedging import Account, HedgeError, Lot, Side
from bushelworks.domain.position import PositionLine, PositionReport
from bushelworks.domain.pricing import FuturesMonth
from bushelworks.services.desk import (
    Gap,
    basis,
    carry,
    gap_for,
    month_exposure,
    sheet_for,
)

AS_OF = date(2024, 12, 2)
CZ4 = FuturesMonth(commodity_code="CORN", month_code="Z", year=2024)
CH5 = FuturesMonth(commodity_code="CORN", month_code="H", year=2025)


def a_lot(**overrides) -> Lot:
    base = dict(
        reference="F-1001",
        month=CZ4,
        side=Side.SHORT,
        contracts=10,
        price=Decimal("4.3225"),
        filled_on=date(2024, 11, 1),
    )
    base.update(overrides)
    return Lot(**base)


def short(contracts: int, month: FuturesMonth = CZ4, **overrides) -> Account:
    account = Account()
    account.add(a_lot(contracts=contracts, month=month, **overrides))
    return account


def a_line(commodity_code="CORN", on_hand="120000", **overrides) -> PositionLine:
    base = dict(
        as_of=AS_OF,
        commodity_code=commodity_code,
        location_code="HOME",
        on_hand=Decimal(on_hand),
    )
    base.update(overrides)
    return PositionLine(**base)


def a_report(*lines: PositionLine) -> PositionReport:
    report = PositionReport(as_of=AS_OF)
    for line in lines or (a_line(),):
        report.add(line)
    return report


def test_a_flat_book_has_no_gap():
    gap = gap_for("CORN", Decimal("50000"), short(10))
    assert gap.gap_contracts == 0
    assert not gap.is_actionable


def test_an_unhedged_position_wants_selling():
    gap = gap_for("CORN", Decimal("50000"), Account())
    assert gap.want_contracts == 10
    assert gap.on_contracts == 0
    assert gap.gap_contracts == 10
    assert gap.side is Side.SHORT


def test_the_gap_carries_the_bushels_as_well_as_the_contracts():
    assert gap_for("CORN", Decimal("50000"), Account()).gap_bushels == Decimal(
        "50000.0000"
    )


def test_a_short_account_reads_as_hedged_bushels():
    assert gap_for("CORN", Decimal("0"), short(4)).hedged_bushels == Decimal(
        "20000.0000"
    )


def test_a_partial_hedge_leaves_the_rest_to_sell():
    gap = gap_for("CORN", Decimal("50000"), short(6))
    assert gap.gap_contracts == 4
    assert gap.is_actionable


def test_a_house_short_more_than_it_owns_is_over_hedged():
    gap = gap_for("CORN", Decimal("20000"), short(10))
    assert gap.gap_contracts == -6
    assert gap.is_over_hedged
    assert gap.side is Side.LONG


def test_a_flat_book_is_not_over_hedged():
    assert not gap_for("CORN", Decimal("50000"), short(10)).is_over_hedged


def test_the_want_rounds_toward_zero():
    assert gap_for("CORN", Decimal("54999"), Account()).want_contracts == 10


def test_a_position_under_one_contract_wants_nothing():
    gap = gap_for("CORN", Decimal("4999"), Account())
    assert gap.want_contracts == 0
    assert not gap.is_actionable


def test_a_long_lot_reduces_what_is_on():
    account = short(10)
    account.add(a_lot(reference="F-1002", side=Side.LONG, contracts=3))
    assert gap_for("CORN", Decimal("50000"), account).on_contracts == 7


def test_the_cost_of_closing_a_gap_is_bushels_times_price():
    gap = gap_for("CORN", Decimal("50000"), Account())
    assert gap.cost(Decimal("4.4000")) == Decimal("220000.00")


def test_the_cost_of_an_over_hedge_is_still_positive():
    gap = gap_for("CORN", Decimal("0"), short(2))
    assert gap.cost(Decimal("4.00")) == Decimal("40000.00")


def test_the_sheet_has_a_line_for_each_commodity():
    report = a_report(a_line(), a_line(commodity_code="SOYB", on_hand="40000"))
    sheet = sheet_for(report, {})
    assert [gap.commodity_code for gap in sheet.gaps] == ["CORN", "SOYB"]


def test_a_commodity_with_no_account_stays_on_the_sheet():
    sheet = sheet_for(a_report(), {})
    assert sheet.for_commodity("CORN").on_contracts == 0


def test_the_sheet_matches_each_commodity_to_its_own_account():
    report = a_report(a_line(), a_line(commodity_code="SOYB", on_hand="40000"))
    sheet = sheet_for(report, {"CORN": short(24)})
    assert sheet.for_commodity("CORN").on_contracts == 24
    assert sheet.for_commodity("SOYB").on_contracts == 0


def test_asking_for_a_commodity_that_is_not_on_the_sheet_is_refused():
    with pytest.raises(HedgeError):
        sheet_for(a_report(), {}).for_commodity("MILO")


def test_the_sheet_keeps_the_day_and_the_site():
    sheet = sheet_for(a_report(), {}, location_code="LURAY")
    assert sheet.as_of == AS_OF
    assert sheet.location_code == "LURAY"


def test_only_the_gaps_worth_a_telephone_call_are_actionable():
    report = a_report(a_line(), a_line(commodity_code="SOYB", on_hand="4000"))
    sheet = sheet_for(report, {})
    assert [gap.commodity_code for gap in sheet.actionable()] == ["CORN"]


def test_the_over_hedged_lines_are_picked_out():
    report = a_report(a_line(on_hand="20000"))
    sheet = sheet_for(report, {"CORN": short(10)})
    assert [gap.commodity_code for gap in sheet.over_hedged()] == ["CORN"]


def test_a_balanced_sheet_has_nothing_over_hedged():
    assert sheet_for(a_report(), {"CORN": short(24)}).over_hedged() == ()


def test_the_headline_nets_the_commodities():
    report = a_report(a_line(on_hand="50000"), a_line(commodity_code="SOYB", on_hand="0"))
    sheet = sheet_for(report, {"SOYB": short(3)})
    assert sheet.total_gap_contracts() == 7


def test_deferred_price_bushels_count_toward_the_hedge():
    line = a_line(on_hand="100000", deferred_price=Decimal("50000"))
    sheet = sheet_for(a_report(line), {})
    assert sheet.for_commodity("CORN").net_long == line.net_long


def test_storage_bushels_do_not_count_toward_the_hedge():
    owned = sheet_for(a_report(a_line(on_hand="100000")), {})
    owed = sheet_for(
        a_report(a_line(on_hand="100000", storage_obligation=Decimal("60000"))), {}
    )
    assert owed.for_commodity("CORN").want_contracts < owned.for_commodity(
        "CORN"
    ).want_contracts


def test_basis_is_cash_less_futures():
    assert basis(Decimal("4.1000"), Decimal("4.3225")) == Decimal("-0.2225")


def test_a_carry_that_beats_storage_is_positive():
    assert carry(
        Decimal("4.20"), Decimal("4.45"), months=3, storage_per_month=Decimal("0.05")
    ) == Decimal("0.1000")


def test_a_carry_that_does_not_cover_storage_is_negative():
    assert carry(
        Decimal("4.20"), Decimal("4.25"), months=3, storage_per_month=Decimal("0.05")
    ) < Decimal("0")


def test_an_inverted_market_pays_nothing_to_hold():
    assert carry(
        Decimal("4.50"), Decimal("4.20"), months=2, storage_per_month=Decimal("0.04")
    ) < Decimal("0")


def test_a_carry_needs_at_least_one_month():
    with pytest.raises(HedgeError):
        carry(Decimal("4.20"), Decimal("4.45"), months=0, storage_per_month=Decimal("0"))


def test_a_short_gains_when_the_market_falls():
    account = short(10)
    exposure = month_exposure(account, {CZ4: Decimal("4.2225")})
    assert exposure["CORNZ24"] == Decimal("5000.00")


def test_a_short_loses_when_the_market_rises():
    account = short(10)
    assert month_exposure(account, {CZ4: Decimal("4.4225")})["CORNZ24"] == Decimal(
        "-5000.00"
    )


def test_each_month_is_priced_on_its_own():
    account = short(10)
    account.add(a_lot(reference="F-1002", month=CH5, price=Decimal("4.5000")))
    exposure = month_exposure(
        account, {CZ4: Decimal("4.3225"), CH5: Decimal("4.4000")}
    )
    assert exposure["CORNZ24"] == Decimal("0.00")
    assert exposure["CORNH25"] == Decimal("5000.00")


def test_a_month_with_no_settlement_price_is_refused():
    with pytest.raises(HedgeError):
        month_exposure(short(10), {})


def test_a_flattened_month_drops_off_the_exposure():
    account = short(10)
    account.add(a_lot(reference="F-1002", side=Side.LONG, contracts=10))
    assert month_exposure(account, {}) == {}


def test_a_gap_can_be_built_by_hand_for_a_what_if():
    gap = Gap(
        commodity_code="CORN",
        net_long=Decimal("75000"),
        hedged_bushels=Decimal("50000"),
        want_contracts=15,
        on_contracts=10,
    )
    assert gap.gap_contracts == 5
    assert gap.is_actionable
