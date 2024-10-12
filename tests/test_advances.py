from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.advances import (
    SETTLEMENT_ADVANCE_CAP,
    Advance,
    AdvanceError,
    AdvanceKind,
    apply_to_settlement,
    cap_for,
    check_settlement_advance,
    outstanding_for,
)
from bushelworks.errors import ConflictError

DRAWN = date(2024, 9, 12)
SETTLED = date(2024, 10, 20)


def a_settlement_advance(**overrides) -> Advance:
    base = dict(
        reference="ADV-2024-0031",
        party_code="MERRICK",
        kind=AdvanceKind.SETTLEMENT,
        principal=Decimal("5000.00"),
        drawn_on=DRAWN,
    )
    base.update(overrides)
    return Advance(**base)


def a_crop_advance(**overrides) -> Advance:
    base = dict(
        reference="CRP-2024-0007",
        party_code="MERRICK",
        kind=AdvanceKind.CROP,
        principal=Decimal("50000.00"),
        drawn_on=DRAWN,
        annual_rate_percent=Decimal("8.5"),
        due_on=date(2025, 3, 1),
    )
    base.update(overrides)
    return Advance(**base)


def test_an_advance_needs_a_reference_and_an_amount():
    with pytest.raises(AdvanceError):
        a_settlement_advance(reference="")
    with pytest.raises(AdvanceError):
        a_settlement_advance(principal=Decimal("0"))


def test_a_settlement_advance_carries_no_interest():
    with pytest.raises(AdvanceError):
        a_settlement_advance(annual_rate_percent=Decimal("8.5"))
    assert a_settlement_advance().interest_to(SETTLED) == Decimal("0.00")


def test_a_negative_rate_is_refused():
    with pytest.raises(AdvanceError):
        a_crop_advance(annual_rate_percent=Decimal("-1"))


def test_an_advance_due_before_it_was_drawn_is_refused():
    with pytest.raises(AdvanceError):
        a_crop_advance(due_on=date(2024, 9, 1))


def test_a_new_advance_is_wholly_outstanding():
    advance = a_settlement_advance()
    assert advance.outstanding == Decimal("5000.00")
    assert not advance.is_settled
    assert advance.repaid == Decimal("0.00")


def test_days_out_is_counted_from_the_draw():
    assert a_settlement_advance().days_out(SETTLED) == 38


def test_asking_before_the_draw_is_refused():
    with pytest.raises(AdvanceError):
        a_settlement_advance().days_out(date(2024, 9, 1))


def test_crop_advance_interest_is_simple_on_actual_days():
    # 50000 at 8.5% for 38 days over 365
    assert a_crop_advance().interest_to(SETTLED) == Decimal("442.47")


def test_the_payoff_is_principal_plus_interest():
    assert a_crop_advance().payoff(SETTLED) == Decimal("50442.47")


def test_interest_on_the_day_it_was_drawn_is_nothing():
    assert a_crop_advance().interest_to(DRAWN) == Decimal("0.00")


def test_a_repayment_reduces_what_is_outstanding():
    advance = a_settlement_advance().repay("S-1", Decimal("2000.00"), SETTLED)
    assert advance.repaid == Decimal("2000.00")
    assert advance.outstanding == Decimal("3000.00")


def test_repaying_the_whole_thing_settles_it():
    advance = a_settlement_advance().repay("S-1", Decimal("5000.00"), SETTLED)
    assert advance.is_settled
    assert advance.outstanding == Decimal("0.00")


def test_repaying_a_settled_advance_is_a_conflict():
    advance = a_settlement_advance().repay("S-1", Decimal("5000.00"), SETTLED)
    with pytest.raises(ConflictError):
        advance.repay("S-2", Decimal("1.00"), SETTLED)


def test_repaying_more_than_is_owed_is_refused():
    with pytest.raises(AdvanceError):
        a_settlement_advance().repay("S-1", Decimal("5001.00"), SETTLED)


def test_repaying_nothing_is_refused():
    with pytest.raises(AdvanceError):
        a_settlement_advance().repay("S-1", Decimal("0"), SETTLED)


def test_interest_accrues_on_what_is_left_after_a_repayment():
    advance = a_crop_advance().repay("S-1", Decimal("25000.00"), date(2024, 10, 1))
    assert advance.interest_to(SETTLED) == Decimal("221.23")


def test_an_advance_with_no_due_date_never_goes_overdue():
    assert not a_settlement_advance().is_overdue(date(2030, 1, 1))


def test_an_advance_past_its_due_date_is_overdue():
    assert not a_crop_advance().is_overdue(date(2025, 2, 1))
    assert a_crop_advance().is_overdue(date(2025, 4, 1))


def test_a_repaid_advance_is_never_overdue():
    advance = a_crop_advance().repay("S-1", Decimal("50000.00"), SETTLED)
    assert not advance.is_overdue(date(2025, 4, 1))


def test_the_cap_is_a_percentage_of_the_grain_value():
    assert cap_for(Decimal("10000.00")) == Decimal("8000.00")
    assert cap_for(Decimal("10000.00"), Decimal("50")) == Decimal("5000.00")
    assert SETTLEMENT_ADVANCE_CAP == Decimal("80")


def test_a_negative_grain_value_cannot_be_advanced_against():
    with pytest.raises(AdvanceError):
        cap_for(Decimal("-1"))


def test_an_advance_inside_the_cap_passes():
    check_settlement_advance(Decimal("8000.00"), Decimal("10000.00"))


def test_an_advance_above_the_cap_is_refused_with_both_figures():
    with pytest.raises(AdvanceError) as caught:
        check_settlement_advance(Decimal("9000.00"), Decimal("10000.00"))
    assert "8000.00" in str(caught.value)


def test_what_is_outstanding_across_several_advances():
    advances = [a_settlement_advance(), a_crop_advance()]
    assert outstanding_for(advances) == Decimal("55000.00")


def test_a_settlement_pays_the_oldest_advance_first():
    advances = [
        a_crop_advance(drawn_on=date(2024, 8, 1), principal=Decimal("1000.00")),
        a_settlement_advance(),
    ]
    updated, left = apply_to_settlement(
        advances, Decimal("1500.00"), "S-1", SETTLED
    )
    assert updated[0].is_settled
    assert not updated[1].is_settled
    assert left == Decimal("0.00")


def test_a_settlement_bigger_than_the_advances_leaves_the_rest():
    advances = [a_settlement_advance()]
    updated, left = apply_to_settlement(
        advances, Decimal("8000.00"), "S-1", SETTLED
    )
    assert updated[0].is_settled
    assert left == Decimal("3000.00")


def test_a_settlement_that_pays_nothing_leaves_everything_outstanding():
    advances = [a_settlement_advance()]
    updated, left = apply_to_settlement(advances, Decimal("0.00"), "S-1", SETTLED)
    assert updated[0].outstanding == Decimal("5000.00")
    assert left == Decimal("0.00")


def test_a_negative_settlement_recovers_nothing():
    advances = [a_settlement_advance()]
    updated, left = apply_to_settlement(advances, Decimal("-100.00"), "S-1", SETTLED)
    assert updated[0].outstanding == Decimal("5000.00")
    assert left == Decimal("0.00")


def test_a_partial_recovery_leaves_the_remainder_for_next_time():
    advances = [a_settlement_advance()]
    updated, left = apply_to_settlement(
        advances, Decimal("2000.00"), "S-1", SETTLED
    )
    assert updated[0].outstanding == Decimal("3000.00")
    assert left == Decimal("0.00")


def test_an_already_settled_advance_is_skipped():
    advances = [a_settlement_advance().repay("S-0", Decimal("5000.00"), SETTLED)]
    updated, left = apply_to_settlement(
        advances, Decimal("1000.00"), "S-1", SETTLED
    )
    assert left == Decimal("1000.00")
    assert len(updated[0].repayments) == 1
