from decimal import Decimal

import pytest

from bushelworks.domain import commodity as cm
from bushelworks.domain.blending import (
    TEST_WEIGHT_PENALTY,
    BlendError,
    Lot,
    blend,
    blend_to_grade,
    is_worth_blending,
    value_of,
)
from bushelworks.domain.grade import NumericalGrade

PRICES = {
    NumericalGrade.US_1: Decimal("4.4000"),
    NumericalGrade.US_2: Decimal("4.3500"),
    NumericalGrade.US_3: Decimal("4.2000"),
    NumericalGrade.US_4: Decimal("3.9000"),
    NumericalGrade.US_5: Decimal("3.5000"),
    NumericalGrade.SAMPLE: Decimal("3.0000"),
}


def sample(test_weight="56.5", damage="1.0", bcfm="1.2", heat="0.0"):
    return {
        cm.Factor.TEST_WEIGHT: Decimal(test_weight),
        cm.Factor.MOISTURE: Decimal("14.0"),
        cm.Factor.BCFM: Decimal(bcfm),
        cm.Factor.DAMAGE_TOTAL: Decimal(damage),
        cm.Factor.DAMAGE_HEAT: Decimal(heat),
    }


CLEAN = Lot("bin 4", Decimal("4000"), sample())
POOR = Lot("bin 7", Decimal("2000"), sample(test_weight="53.0", damage="6.5"))


def test_a_lot_needs_bushels():
    with pytest.raises(BlendError):
        Lot("bin 1", Decimal("0"), sample())


def test_a_lot_needs_a_sample():
    with pytest.raises(BlendError):
        Lot("bin 1", Decimal("1000"), {})


def test_blending_nothing_is_refused():
    with pytest.raises(BlendError):
        blend([])


def test_one_lot_blends_to_itself():
    mixture = blend([CLEAN])
    assert mixture.bushels == Decimal("4000.0000")
    assert mixture.sample == CLEAN.sample
    assert mixture.lots == ("bin 4",)


def test_percentage_factors_blend_by_weight():
    mixture = blend([CLEAN, POOR])
    # 4000 at 1.0 and 2000 at 6.5 gives 2.83 rounded
    assert mixture.sample[cm.Factor.DAMAGE_TOTAL] == Decimal("2.83")


def test_the_blend_carries_every_lot_label():
    assert blend([CLEAN, POOR]).lots == ("bin 4", "bin 7")


def test_the_bushels_add_up():
    assert blend([CLEAN, POOR]).bushels == Decimal("6000.0000")


def test_test_weight_comes_in_below_the_weighted_average():
    mixture = blend([CLEAN, POOR])
    weighted = (
        Decimal("56.5") * Decimal("4000") + Decimal("53.0") * Decimal("2000")
    ) / Decimal("6000")
    assert mixture.sample[cm.Factor.TEST_WEIGHT] == (
        weighted - TEST_WEIGHT_PENALTY
    ).quantize(Decimal("0.01"))


def test_a_factor_only_one_lot_was_tested_for_is_left_out():
    partial = Lot(
        "bin 9",
        Decimal("1000"),
        {
            cm.Factor.TEST_WEIGHT: Decimal("56.0"),
            cm.Factor.MOISTURE: Decimal("14.0"),
            cm.Factor.BCFM: Decimal("1.0"),
            cm.Factor.DAMAGE_TOTAL: Decimal("1.0"),
        },
    )
    mixture = blend([CLEAN, partial])
    assert cm.Factor.DAMAGE_HEAT not in mixture.sample


def test_a_blend_grades_worse_than_the_clean_lot_alone():
    clean_grade = blend([CLEAN]).grade(cm.CORN)
    mixed_grade = blend([CLEAN, POOR]).grade(cm.CORN)
    assert clean_grade.grade.is_better_than(mixed_grade.grade)


def test_a_blend_grades_better_than_the_poor_lot_alone():
    poor_grade = blend([POOR]).grade(cm.CORN)
    mixed_grade = blend([CLEAN, POOR]).grade(cm.CORN)
    assert mixed_grade.grade.is_better_than(poor_grade.grade)


def test_blending_to_a_target_finds_the_bushels_needed():
    needed = blend_to_grade(cm.CORN, POOR, CLEAN, NumericalGrade.US_2)
    assert needed is not None
    assert needed > Decimal("0")


def test_a_lot_already_at_the_target_needs_nothing():
    assert blend_to_grade(cm.CORN, CLEAN, CLEAN, NumericalGrade.US_2) == Decimal("0")


def test_a_clean_lot_too_small_to_help_gives_nothing():
    tiny = Lot("bin 2", Decimal("50"), sample())
    assert blend_to_grade(cm.CORN, POOR, tiny, NumericalGrade.US_1) is None


def test_a_blending_step_has_to_be_positive():
    with pytest.raises(BlendError):
        blend_to_grade(cm.CORN, POOR, CLEAN, NumericalGrade.US_2, step=Decimal("0"))


def test_a_blend_is_valued_at_the_price_for_its_grade():
    value = value_of(blend([CLEAN]), cm.CORN, PRICES)
    assert value == Decimal("17600.00")


def test_a_grade_with_no_posted_price_is_refused():
    with pytest.raises(BlendError):
        value_of(blend([CLEAN]), cm.CORN, {NumericalGrade.US_5: Decimal("3.50")})


def test_blending_a_poor_lot_with_a_clean_one_is_worth_doing():
    assert is_worth_blending(cm.CORN, [CLEAN, POOR], PRICES)


def test_blending_two_clean_lots_is_not_worth_doing():
    other = Lot("bin 5", Decimal("3000"), sample())
    assert not is_worth_blending(cm.CORN, [CLEAN, other], PRICES)


def test_the_blend_of_three_lots_still_adds_up():
    third = Lot("bin 5", Decimal("1500"), sample(damage="2.0"))
    mixture = blend([CLEAN, POOR, third])
    assert mixture.bushels == Decimal("7500.0000")
    assert len(mixture.lots) == 3
