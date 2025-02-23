from datetime import date, timedelta
from decimal import Decimal

import pytest

from bushelworks.domain.certification import (
    CERTIFICATION_LIFE,
    TOLERANCE,
    CertificationError,
    Log,
    Result,
    ScaleTest,
    correction_for,
    exposure,
    overdue_certifications,
)

TESTED = date(2024, 8, 15)


def a_test(**overrides) -> ScaleTest:
    base = dict(
        scale_id="SCALE-1",
        location_code="HOME",
        tested_on=TESTED,
        test_load_pounds=Decimal("60000"),
        indicated_pounds=Decimal("60000"),
        tested_by="KS Weights and Measures",
        certificate_number="WM-2024-0912",
    )
    base.update(overrides)
    return ScaleTest(**base)


def test_a_test_needs_a_known_load_and_a_tester():
    with pytest.raises(CertificationError):
        a_test(test_load_pounds=Decimal("0"))
    with pytest.raises(CertificationError):
        a_test(tested_by="")


def test_a_perfect_test_has_no_error():
    test = a_test()
    assert test.error_pounds == Decimal("0")
    assert test.is_within_tolerance


def test_a_scale_reading_heavy_shows_a_positive_error():
    test = a_test(indicated_pounds=Decimal("60040"))
    assert test.error_pounds == Decimal("40")
    assert test.error_fraction > 0


def test_a_scale_reading_light_shows_a_negative_error():
    assert a_test(indicated_pounds=Decimal("59800")).error_pounds == Decimal("-200")


def test_a_small_error_is_inside_tolerance():
    assert a_test(indicated_pounds=Decimal("60040")).is_within_tolerance
    assert TOLERANCE == Decimal("0.001")


def test_a_large_error_is_outside_tolerance():
    assert not a_test(indicated_pounds=Decimal("59800")).is_within_tolerance


def test_an_official_test_carries_a_certificate():
    assert a_test().is_official
    assert not a_test(certificate_number=None).is_official


def test_a_certificate_stands_for_a_year():
    test = a_test()
    assert test.expires_on() == TESTED + CERTIFICATION_LIFE
    assert test.is_current_on(date(2024, 10, 3))
    assert not test.is_current_on(date(2026, 1, 1))


def test_a_house_test_has_no_expiry():
    assert a_test(certificate_number=None).expires_on() is None
    assert not a_test(certificate_number=None).is_current_on(TESTED)


def a_log() -> Log:
    log = Log(scale_id="SCALE-1", location_code="HOME")
    log.record(a_test())
    return log


def test_a_test_of_another_scale_is_refused():
    log = a_log()
    with pytest.raises(CertificationError):
        log.record(a_test(scale_id="SCALE-2"))


def test_the_log_keeps_its_tests_in_order():
    log = a_log()
    log.record(a_test(tested_on=date(2024, 6, 1), certificate_number=None))
    assert [test.tested_on for test in log.tests] == [date(2024, 6, 1), TESTED]


def test_the_latest_test_is_the_most_recent():
    log = a_log()
    log.record(a_test(tested_on=date(2024, 11, 1), certificate_number=None))
    assert log.latest().tested_on == date(2024, 11, 1)


def test_an_empty_log_has_no_latest():
    assert Log(scale_id="S", location_code="HOME").latest() is None


def test_the_last_good_test_skips_the_failures():
    log = a_log()
    log.record(
        a_test(
            tested_on=date(2024, 11, 1),
            indicated_pounds=Decimal("59800"),
            certificate_number=None,
            result=Result.FAIL,
        )
    )
    assert log.last_good_before(date(2024, 12, 1)).tested_on == TESTED


def test_a_log_with_no_good_test_has_none():
    log = Log(scale_id="SCALE-1", location_code="HOME")
    log.record(a_test(indicated_pounds=Decimal("59800")))
    assert log.last_good_before(date(2024, 12, 1)) is None


def test_a_scale_is_certified_while_the_certificate_stands():
    assert a_log().certified_on(date(2024, 10, 3))
    assert not a_log().certified_on(date(2026, 1, 1))


def test_a_failure_puts_the_span_since_the_last_good_test_in_question():
    log = a_log()
    failed = a_test(
        tested_on=date(2024, 11, 1),
        indicated_pounds=Decimal("59800"),
        certificate_number=None,
        result=Result.FAIL,
    )
    log.record(failed)
    window = log.suspect_window(failed)
    assert window == (TESTED + timedelta(days=1), date(2024, 11, 1))


def test_a_passing_test_puts_nothing_in_question():
    log = a_log()
    assert log.suspect_window(a_log().latest()) is None


def test_a_failure_with_no_good_test_before_it_says_nothing():
    log = Log(scale_id="SCALE-1", location_code="HOME")
    failed = a_test(indicated_pounds=Decimal("59800"))
    log.record(failed)
    assert log.suspect_window(failed) is None


def test_a_scale_reading_light_underpaid_and_the_correction_is_positive():
    test = a_test(indicated_pounds=Decimal("59800"))
    assert correction_for(test, Decimal("52240")) == Decimal("174")


def test_a_scale_reading_heavy_overpaid_and_the_correction_is_negative():
    test = a_test(indicated_pounds=Decimal("60200"))
    assert correction_for(test, Decimal("52240")) < Decimal("0")


def test_the_exposure_is_the_size_of_the_correction():
    test = a_test(indicated_pounds=Decimal("59800"))
    assert exposure(test, Decimal("5000000")) == Decimal("16665")


def test_the_scales_the_state_would_shut_are_found():
    good = a_log()
    stale = Log(scale_id="SCALE-2", location_code="LURAY")
    stale.record(a_test(scale_id="SCALE-2", tested_on=date(2022, 1, 1)))
    found = overdue_certifications([good, stale], date(2024, 10, 3))
    assert [log.scale_id for log in found] == ["SCALE-2"]
