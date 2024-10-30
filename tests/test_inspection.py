from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.grade import GradeResult, NumericalGrade
from bushelworks.domain.inspection import (
    CERTIFICATE_LIFE,
    Certificate,
    InspectionError,
    SpecialGrade,
    compare,
    compare_factors,
    grade_loss,
    worst_of,
)

SAMPLED = date(2024, 11, 12)
ISSUED = date(2024, 11, 13)

PRICES = {
    NumericalGrade.US_1: Decimal("4.4000"),
    NumericalGrade.US_2: Decimal("4.3500"),
    NumericalGrade.US_3: Decimal("4.2000"),
    NumericalGrade.SAMPLE: Decimal("3.0000"),
}


def a_certificate(**overrides) -> Certificate:
    base = dict(
        number="KS-2024-114772",
        agency="Kansas Grain Inspection Service",
        commodity_code="CORN",
        sampled_on=SAMPLED,
        issued_on=ISSUED,
        grade=NumericalGrade.US_2,
        factors={
            Factor.TEST_WEIGHT: Decimal("55.1"),
            Factor.MOISTURE: Decimal("14.4"),
            Factor.BCFM: Decimal("2.6"),
            Factor.DAMAGE_TOTAL: Decimal("3.1"),
        },
    )
    base.update(overrides)
    return Certificate(**base)


def test_a_certificate_needs_a_number_and_an_agency():
    with pytest.raises(InspectionError):
        a_certificate(number="")
    with pytest.raises(InspectionError):
        a_certificate(agency="")


def test_a_certificate_cannot_be_issued_before_the_sample():
    with pytest.raises(InspectionError):
        a_certificate(issued_on=date(2024, 11, 1))


def test_a_certificate_stands_for_thirty_days_from_the_sample():
    certificate = a_certificate()
    assert certificate.expires_on == SAMPLED + CERTIFICATE_LIFE
    assert certificate.is_current_on(date(2024, 12, 1))
    assert not certificate.is_current_on(date(2024, 12, 20))


def test_a_certificate_is_not_current_before_it_is_issued():
    assert not a_certificate().is_current_on(SAMPLED)


def test_a_plain_certificate_describes_itself_by_its_number():
    assert a_certificate().description == "US No. 2 CORN"


def test_sample_grade_is_written_out_rather_than_numbered():
    certificate = a_certificate(grade=NumericalGrade.SAMPLE)
    assert certificate.description == "Sample Grade CORN"


def test_a_special_grade_goes_after_the_number():
    certificate = a_certificate(
        special_grades=frozenset({SpecialGrade.MUSTY})
    )
    assert certificate.description == "US No. 2 CORN, musty"


def test_two_special_grades_are_listed_in_order():
    certificate = a_certificate(
        special_grades=frozenset({SpecialGrade.WEEVILY, SpecialGrade.MUSTY})
    )
    assert certificate.description == "US No. 2 CORN, musty, weevily"


def test_an_ordinary_certificate_is_not_rejectable():
    assert not a_certificate().is_rejectable
    assert not a_certificate(
        special_grades=frozenset({SpecialGrade.MUSTY})
    ).is_rejectable


def test_a_load_on_grade_can_still_be_refused_for_a_special_grade():
    certificate = a_certificate(
        grade=NumericalGrade.US_2,
        special_grades=frozenset({SpecialGrade.WEEVILY}),
    )
    assert certificate.grade is NumericalGrade.US_2
    assert certificate.is_rejectable


def test_the_factors_read_back():
    certificate = a_certificate()
    assert certificate.factor(Factor.TEST_WEIGHT) == Decimal("55.1")
    assert certificate.factor(Factor.PROTEIN) is None


def test_agreeing_grades_are_no_disagreement():
    house = GradeResult(NumericalGrade.US_2, ())
    assert compare(house, a_certificate()) == []


def test_a_different_grade_is_a_disagreement():
    house = GradeResult(NumericalGrade.US_1, ())
    found = compare(house, a_certificate())
    assert len(found) == 1
    assert found[0].is_grade
    assert found[0].house == "1"
    assert found[0].official == "2"


def test_factors_inside_the_tolerance_do_not_disagree():
    house_sample = {
        Factor.TEST_WEIGHT: Decimal("55.4"),
        Factor.MOISTURE: Decimal("14.2"),
    }
    tolerance = {
        Factor.TEST_WEIGHT: Decimal("0.5"),
        Factor.MOISTURE: Decimal("0.3"),
    }
    assert compare_factors(house_sample, a_certificate(), tolerance) == []


def test_a_factor_outside_the_tolerance_disagrees():
    house_sample = {Factor.TEST_WEIGHT: Decimal("56.5")}
    tolerance = {Factor.TEST_WEIGHT: Decimal("0.5")}
    found = compare_factors(house_sample, a_certificate(), tolerance)
    assert [item.factor for item in found] == [Factor.TEST_WEIGHT]


def test_a_factor_the_house_never_measured_is_not_a_disagreement():
    certificate = a_certificate(
        factors={Factor.PROTEIN: Decimal("11.4"), Factor.MOISTURE: Decimal("14.4")}
    )
    found = compare_factors({Factor.MOISTURE: Decimal("14.4")}, certificate)
    assert found == []


def test_with_no_tolerance_any_difference_disagrees():
    house_sample = {Factor.MOISTURE: Decimal("14.5")}
    found = compare_factors(house_sample, a_certificate())
    assert [item.factor for item in found] == [Factor.MOISTURE]


def test_the_grade_loss_is_positive_when_the_certificate_is_worse():
    house = GradeResult(NumericalGrade.US_1, ())
    assert grade_loss(house, a_certificate(), PRICES) == Decimal("0.0500")


def test_the_grade_loss_is_negative_when_the_certificate_is_better():
    house = GradeResult(NumericalGrade.US_3, ())
    assert grade_loss(house, a_certificate(), PRICES) == Decimal("-0.1500")


def test_a_grade_with_no_posted_price_cannot_be_compared():
    house = GradeResult(NumericalGrade.US_5, ())
    with pytest.raises(InspectionError):
        grade_loss(house, a_certificate(), PRICES)


def test_the_worst_certificate_of_a_set():
    better = a_certificate(number="KS-1", grade=NumericalGrade.US_1)
    worse = a_certificate(number="KS-3", grade=NumericalGrade.US_3)
    assert worst_of([better, a_certificate(), worse]).number == "KS-3"


def test_the_worst_of_nothing_is_refused():
    with pytest.raises(InspectionError):
        worst_of([])
