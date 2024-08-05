from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.assessments import WithheldLine
from bushelworks.reports.remittance import (
    Collected,
    RemittanceError,
    Return,
    group,
    quarter_end,
    quarter_of,
    quarter_start,
    render,
    to_csv,
    totals,
)

KSCORN = WithheldLine(
    code="KSCORN",
    name="Kansas Corn Commission",
    authority="Kansas Department of Agriculture",
    dollars=Decimal("89.76"),
    refundable=True,
)

KSWHEAT = WithheldLine(
    code="KSWHEAT",
    name="Kansas Wheat Commission",
    authority="Kansas Department of Agriculture",
    dollars=Decimal("40.00"),
    refundable=False,
)


def collected(**overrides) -> Collected:
    base = dict(
        settlement_number="S-2024-0417",
        settled_on=date(2024, 10, 20),
        party_code="MERRICK",
        commodity_code="CORN",
        bushels=Decimal("8976.0000"),
        line=KSCORN,
    )
    base.update(overrides)
    return Collected(**base)


def test_quarters_are_worked_out_from_the_month():
    assert quarter_of(date(2024, 1, 5)) == 1
    assert quarter_of(date(2024, 6, 30)) == 2
    assert quarter_of(date(2024, 10, 20)) == 4


def test_quarter_boundaries():
    assert quarter_start(2024, 4) == date(2024, 10, 1)
    assert quarter_end(2024, 4) == date(2024, 12, 31)
    assert quarter_end(2024, 1) == date(2024, 3, 31)


def test_a_bad_quarter_is_refused():
    with pytest.raises(RemittanceError):
        quarter_end(2024, 5)
    with pytest.raises(RemittanceError):
        quarter_start(2024, 0)


def a_return() -> Return:
    return Return(
        authority=KSCORN.authority,
        code="KSCORN",
        commodity_code="CORN",
        year=2024,
        quarter=4,
    )


def test_a_new_return_is_empty():
    one = a_return()
    assert one.is_empty
    assert one.withheld == Decimal("0.00")
    assert one.period == "2024 Q4"


def test_an_entry_lands_on_the_return():
    one = a_return()
    one.add(collected())
    assert not one.is_empty
    assert one.bushels == Decimal("8976.0000")
    assert one.withheld == Decimal("89.76")


def test_a_levy_from_another_commission_is_refused():
    one = a_return()
    with pytest.raises(RemittanceError):
        one.add(collected(line=KSWHEAT))


def test_a_different_commodity_is_refused():
    one = a_return()
    with pytest.raises(RemittanceError):
        one.add(collected(commodity_code="HRW"))


def test_a_settlement_from_another_quarter_is_refused():
    one = a_return()
    with pytest.raises(RemittanceError):
        one.add(collected(settled_on=date(2024, 8, 20)))


def test_the_return_covers_its_own_quarter():
    one = a_return()
    assert one.covers(date(2024, 10, 1))
    assert one.covers(date(2024, 12, 31))
    assert not one.covers(date(2025, 1, 1))


def test_producers_are_listed_once_each():
    one = a_return()
    one.add(collected())
    one.add(collected(settlement_number="S-2024-0418"))
    one.add(collected(settlement_number="S-2024-0419", party_code="HOLLIS"))
    assert one.producers == ("HOLLIS", "MERRICK")


def test_the_refundable_portion_is_separate():
    one = a_return()
    one.add(collected())
    assert one.refundable == Decimal("89.76")


def test_a_non_refundable_levy_has_no_refundable_portion():
    one = Return(
        authority=KSWHEAT.authority,
        code="KSWHEAT",
        commodity_code="HRW",
        year=2024,
        quarter=4,
    )
    one.add(collected(commodity_code="HRW", line=KSWHEAT))
    assert one.refundable == Decimal("0.00")
    assert one.withheld == Decimal("40.00")


def test_grouping_splits_by_commission_and_commodity():
    entries = [
        collected(),
        collected(settlement_number="S-2", commodity_code="HRW", line=KSWHEAT),
    ]
    returns = group(entries, 2024, 4)
    assert [one.code for one in returns] == ["KSCORN", "KSWHEAT"]


def test_grouping_puts_two_settlements_on_one_return():
    entries = [collected(), collected(settlement_number="S-2")]
    returns = group(entries, 2024, 4)
    assert len(returns) == 1
    assert len(returns[0].entries) == 2


def test_the_printed_return_names_the_commission_and_the_period():
    one = a_return()
    one.add(collected())
    text = render(one)
    assert "CHECKOFF RETURN 2024 Q4" in text
    assert "Kansas Department of Agriculture" in text
    assert "S-2024-0417" in text
    assert "1 settlements, 1 producers" in text


def test_a_refundable_return_says_so_at_the_bottom():
    one = a_return()
    one.add(collected())
    assert "of which refundable" in render(one)


def test_a_non_refundable_return_does_not():
    one = Return(
        authority=KSWHEAT.authority,
        code="KSWHEAT",
        commodity_code="HRW",
        year=2024,
        quarter=4,
    )
    one.add(collected(commodity_code="HRW", line=KSWHEAT))
    assert "of which refundable" not in render(one)


def test_the_csv_has_a_row_per_settlement():
    entries = [collected(), collected(settlement_number="S-2")]
    text = to_csv(group(entries, 2024, 4))
    rows = text.strip().split("\n")
    assert rows[0].startswith("authority,levy,commodity,period")
    assert len(rows) == 3


def test_the_totals_say_what_is_owed_to_each_commission():
    entries = [
        collected(),
        collected(settlement_number="S-2"),
        collected(settlement_number="S-3", commodity_code="HRW", line=KSWHEAT),
    ]
    owed = totals(group(entries, 2024, 4))
    assert owed == {"KSCORN": Decimal("179.52"), "KSWHEAT": Decimal("40.00")}
