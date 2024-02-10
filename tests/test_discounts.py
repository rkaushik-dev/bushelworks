from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.discounts import (
    DiscountBand,
    DiscountBook,
    DiscountMode,
    DiscountSchedule,
    ScheduleError,
)

# Below 54 lb: a cent per half pound to 52, then two cents per half pound.
CORN_TEST_WEIGHT = DiscountSchedule(
    commodity_code="CORN",
    factor=Factor.TEST_WEIGHT,
    label="light test weight",
    bands=[
        DiscountBand(
            start=Decimal("54.0"),
            end=Decimal("52.0"),
            rate=Decimal("0.0100"),
            increment=Decimal("0.5"),
        ),
        DiscountBand(
            start=Decimal("52.0"),
            end=None,
            rate=Decimal("0.0200"),
            increment=Decimal("0.5"),
        ),
    ],
)

# Over 3% BCFM: two cents per tenth of a point, prorated.
CORN_BCFM = DiscountSchedule(
    commodity_code="CORN",
    factor=Factor.BCFM,
    bands=[
        DiscountBand(
            start=Decimal("3.0"),
            rate=Decimal("0.0020"),
            increment=Decimal("0.1"),
            mode=DiscountMode.PRORATE,
        )
    ],
)


def test_at_or_above_the_threshold_costs_nothing():
    assert CORN_TEST_WEIGHT.rate_for(Decimal("56.0")) == Decimal("0.0000")
    assert CORN_TEST_WEIGHT.rate_for(Decimal("54.0")) == Decimal("0.0000")


def test_a_partial_step_is_charged_as_a_whole_one():
    # 0.1 lb under 54 still buys a full half pound step
    assert CORN_TEST_WEIGHT.rate_for(Decimal("53.9")) == Decimal("0.0100")
    assert CORN_TEST_WEIGHT.rate_for(Decimal("53.5")) == Decimal("0.0100")
    assert CORN_TEST_WEIGHT.rate_for(Decimal("53.4")) == Decimal("0.0200")


def test_bands_stack_rather_than_replace():
    # 2 lb in the first band at 0.01 per half pound is 0.04,
    # then 1 lb in the second at 0.02 per half pound is 0.04
    assert CORN_TEST_WEIGHT.rate_for(Decimal("51.0")) == Decimal("0.0800")


def test_the_first_band_is_fully_consumed_before_the_second_starts():
    assert CORN_TEST_WEIGHT.rate_for(Decimal("52.0")) == Decimal("0.0400")


def test_prorate_charges_the_exact_fraction():
    # 0.35 over 3.0 is 3.5 increments at 0.002
    assert CORN_BCFM.rate_for(Decimal("3.35")) == Decimal("0.0070")


def test_flat_mode_charges_once_however_far_over():
    schedule = DiscountSchedule(
        commodity_code="SOYB",
        factor=Factor.FOREIGN_MATERIAL,
        bands=[
            DiscountBand(
                start=Decimal("2.0"),
                rate=Decimal("0.0500"),
                mode=DiscountMode.FLAT,
            )
        ],
    )
    assert schedule.rate_for(Decimal("2.1")) == Decimal("0.0500")
    assert schedule.rate_for(Decimal("9.9")) == Decimal("0.0500")
    assert schedule.rate_for(Decimal("2.0")) == Decimal("0.0000")


def test_excess_is_measured_toward_the_bad_end():
    assert CORN_TEST_WEIGHT.excess(Decimal("51.5")) == Decimal("2.5")
    assert CORN_BCFM.excess(Decimal("4.2")) == Decimal("1.2")
    assert CORN_BCFM.excess(Decimal("1.0")) == Decimal("0")


def test_charge_extends_the_rate_over_bushels():
    assert CORN_TEST_WEIGHT.charge(Decimal("932.8571"), Decimal("51.0")) == Decimal("74.63")


def test_a_schedule_needs_bands():
    with pytest.raises(ScheduleError):
        DiscountSchedule(commodity_code="CORN", factor=Factor.BCFM, bands=[])


def test_bands_may_not_leave_a_gap():
    with pytest.raises(ScheduleError):
        DiscountSchedule(
            commodity_code="CORN",
            factor=Factor.TEST_WEIGHT,
            bands=[
                DiscountBand(start=Decimal("54"), end=Decimal("52"), rate=Decimal("0.01")),
                DiscountBand(start=Decimal("50"), rate=Decimal("0.02")),
            ],
        )


def test_nothing_may_follow_an_open_band():
    with pytest.raises(ScheduleError):
        DiscountSchedule(
            commodity_code="CORN",
            factor=Factor.TEST_WEIGHT,
            bands=[
                DiscountBand(start=Decimal("54"), rate=Decimal("0.01")),
                DiscountBand(start=Decimal("52"), rate=Decimal("0.02")),
            ],
        )


def test_a_low_is_bad_band_has_to_descend():
    with pytest.raises(ScheduleError):
        DiscountSchedule(
            commodity_code="CORN",
            factor=Factor.TEST_WEIGHT,
            bands=[DiscountBand(start=Decimal("54"), end=Decimal("56"), rate=Decimal("0.01"))],
        )


def test_increment_must_be_positive():
    with pytest.raises(ScheduleError):
        DiscountBand(start=Decimal("54"), rate=Decimal("0.01"), increment=Decimal("0"))


def test_book_adds_every_applicable_discount():
    book = DiscountBook([CORN_TEST_WEIGHT, CORN_BCFM])
    sample = {
        Factor.TEST_WEIGHT: Decimal("53.4"),
        Factor.BCFM: Decimal("3.35"),
        Factor.MOISTURE: Decimal("18.2"),
    }
    assert book.total_rate("CORN", sample) == Decimal("0.0270")


def test_book_breakdown_skips_the_factors_that_cost_nothing():
    book = DiscountBook([CORN_TEST_WEIGHT, CORN_BCFM])
    sample = {
        Factor.TEST_WEIGHT: Decimal("56.0"),
        Factor.BCFM: Decimal("3.35"),
    }
    assert book.breakdown("CORN", sample) == {Factor.BCFM: Decimal("0.0070")}


def test_book_refuses_two_schedules_for_one_factor():
    book = DiscountBook([CORN_BCFM])
    with pytest.raises(ScheduleError):
        book.add(CORN_BCFM)


def test_book_lists_the_factors_it_covers():
    book = DiscountBook([CORN_TEST_WEIGHT, CORN_BCFM])
    assert book.factors("CORN") == (Factor.BCFM, Factor.TEST_WEIGHT)
    assert book.factors("SOYB") == ()
    assert book.for_factor("SOYB", Factor.BCFM) is None
