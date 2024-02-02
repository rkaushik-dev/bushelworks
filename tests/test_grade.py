from decimal import Decimal

import pytest

from bushelworks.domain import commodity as cm
from bushelworks.domain import grade as gr


def corn_sample(**overrides):
    sample = {
        cm.Factor.TEST_WEIGHT: Decimal("57.0"),
        cm.Factor.MOISTURE: Decimal("14.2"),
        cm.Factor.BCFM: Decimal("1.1"),
        cm.Factor.DAMAGE_TOTAL: Decimal("1.4"),
        cm.Factor.DAMAGE_HEAT: Decimal("0.0"),
    }
    sample.update(overrides)
    return sample


def test_clean_corn_grades_one_with_nothing_holding_it_back():
    result = gr.determine(cm.CORN, corn_sample())
    assert result.grade is gr.NumericalGrade.US_1
    assert result.limiting_factors == ()
    assert not result.is_sample_grade


def test_worst_factor_decides_the_grade():
    # Everything is a No. 1 except test weight, which only reaches No. 3.
    result = gr.determine(cm.CORN, corn_sample(**{cm.Factor.TEST_WEIGHT: Decimal("52.5")}))
    assert result.grade is gr.NumericalGrade.US_3
    assert result.limiting_factors == (cm.Factor.TEST_WEIGHT,)


def test_two_factors_can_hold_a_load_back_together():
    result = gr.determine(
        cm.CORN,
        corn_sample(
            **{
                cm.Factor.TEST_WEIGHT: Decimal("53.0"),
                cm.Factor.DAMAGE_TOTAL: Decimal("6.2"),
            }
        ),
    )
    assert result.grade is gr.NumericalGrade.US_3
    assert result.limiting_factors == (cm.Factor.DAMAGE_TOTAL, cm.Factor.TEST_WEIGHT)


def test_limit_is_inclusive_at_the_line():
    exactly_two = gr.determine(cm.CORN, corn_sample(**{cm.Factor.BCFM: Decimal("2.0")}))
    assert exactly_two.grade is gr.NumericalGrade.US_1
    just_over = gr.determine(cm.CORN, corn_sample(**{cm.Factor.BCFM: Decimal("2.1")}))
    assert just_over.grade is gr.NumericalGrade.US_2


def test_test_weight_is_inclusive_from_below():
    at_line = gr.determine(cm.CORN, corn_sample(**{cm.Factor.TEST_WEIGHT: Decimal("56.0")}))
    assert at_line.grade is gr.NumericalGrade.US_1
    under = gr.determine(cm.CORN, corn_sample(**{cm.Factor.TEST_WEIGHT: Decimal("55.9")}))
    assert under.grade is gr.NumericalGrade.US_2


def test_falling_off_the_bottom_is_sample_grade():
    result = gr.determine(cm.CORN, corn_sample(**{cm.Factor.DAMAGE_TOTAL: Decimal("22.0")}))
    assert result.is_sample_grade
    assert cm.Factor.DAMAGE_TOTAL in result.limiting_factors


def test_missing_required_factor_is_refused():
    sample = corn_sample()
    del sample[cm.Factor.BCFM]
    with pytest.raises(gr.IncompleteSample):
        gr.determine(cm.CORN, sample)


def test_optional_factor_may_be_absent():
    sample = corn_sample()
    del sample[cm.Factor.DAMAGE_HEAT]
    assert gr.determine(cm.CORN, sample).grade is gr.NumericalGrade.US_1


def test_soybean_splits_ladder():
    sample = {
        cm.Factor.TEST_WEIGHT: Decimal("56.5"),
        cm.Factor.MOISTURE: Decimal("12.4"),
        cm.Factor.FOREIGN_MATERIAL: Decimal("0.6"),
        cm.Factor.DAMAGE_TOTAL: Decimal("1.2"),
        cm.Factor.SPLITS: Decimal("24.0"),
    }
    result = gr.determine(cm.SOYBEANS, sample)
    assert result.grade is gr.NumericalGrade.US_3
    assert result.limiting_factors == (cm.Factor.SPLITS,)


def test_soybeans_stop_at_grade_four():
    grades = [rung.grade for rung in gr.ladder("SOYB")]
    assert gr.NumericalGrade.US_5 not in grades
    assert grades[-1] is gr.NumericalGrade.US_4


def test_factors_holding_back_a_target_grade():
    sample = corn_sample(**{cm.Factor.TEST_WEIGHT: Decimal("53.0")})
    assert gr.factors_holding_back(cm.CORN, sample, gr.NumericalGrade.US_1) == (
        cm.Factor.TEST_WEIGHT,
    )
    assert gr.factors_holding_back(cm.CORN, sample, gr.NumericalGrade.US_3) == ()


def test_graded_factors_reports_the_ladder_columns():
    assert gr.graded_factors("CORN") == frozenset(
        {
            cm.Factor.TEST_WEIGHT,
            cm.Factor.DAMAGE_HEAT,
            cm.Factor.DAMAGE_TOTAL,
            cm.Factor.BCFM,
        }
    )


def test_grade_ordering():
    assert gr.NumericalGrade.US_1.is_better_than(gr.NumericalGrade.US_2)
    assert not gr.NumericalGrade.US_4.is_better_than(gr.NumericalGrade.US_2)
    assert gr.NumericalGrade.US_5.is_better_than(gr.NumericalGrade.SAMPLE)


def test_unknown_ladder_raises():
    with pytest.raises(gr.NoGradeTable):
        gr.ladder("BARLEY")
