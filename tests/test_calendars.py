from datetime import date

import pytest

from bushelworks.domain.calendars import (
    BusinessCalendar,
    CalendarError,
    each_month_end,
    harvest_calendar,
    month_end,
    month_start,
    months_between,
)
from bushelworks.errors import ConfigurationError


def plain() -> BusinessCalendar:
    return BusinessCalendar(name="plain", holidays={date(2024, 7, 4)})


def test_weekends_are_not_business_days():
    cal = plain()
    assert cal.is_business_day(date(2024, 10, 4))
    assert not cal.is_business_day(date(2024, 10, 5))
    assert not cal.is_business_day(date(2024, 10, 6))


def test_a_declared_holiday_is_not_a_business_day():
    assert not plain().is_business_day(date(2024, 7, 4))


def test_a_declared_working_day_beats_the_weekend():
    cal = plain()
    cal.declare_working_day(date(2024, 10, 5))
    assert cal.is_business_day(date(2024, 10, 5))


def test_a_day_cannot_be_both_a_holiday_and_a_working_day():
    cal = plain()
    with pytest.raises(ConfigurationError):
        cal.declare_working_day(date(2024, 7, 4))
    cal2 = plain()
    cal2.declare_working_day(date(2024, 10, 5))
    with pytest.raises(ConfigurationError):
        cal2.declare_holiday(date(2024, 10, 5))


def test_a_calendar_built_with_a_clash_is_refused():
    with pytest.raises(ConfigurationError):
        BusinessCalendar(
            name="bad",
            holidays={date(2024, 7, 4)},
            extra_working_days={date(2024, 7, 4)},
        )


def test_next_business_day_skips_the_weekend():
    cal = plain()
    assert cal.next_business_day(date(2024, 10, 4)) == date(2024, 10, 7)


def test_next_business_day_skips_a_holiday_too():
    cal = plain()
    assert cal.next_business_day(date(2024, 7, 3)) == date(2024, 7, 5)


def test_previous_business_day_walks_back():
    cal = plain()
    assert cal.previous_business_day(date(2024, 10, 7)) == date(2024, 10, 4)


def test_roll_forward_leaves_a_business_day_alone():
    cal = plain()
    assert cal.roll_forward(date(2024, 10, 4)) == date(2024, 10, 4)
    assert cal.roll_forward(date(2024, 10, 5)) == date(2024, 10, 7)


def test_roll_backward_leaves_a_business_day_alone():
    cal = plain()
    assert cal.roll_backward(date(2024, 10, 4)) == date(2024, 10, 4)
    assert cal.roll_backward(date(2024, 10, 6)) == date(2024, 10, 4)


def test_adding_business_days_counts_only_working_ones():
    cal = plain()
    assert cal.add_business_days(date(2024, 10, 3), 3) == date(2024, 10, 8)
    assert cal.add_business_days(date(2024, 10, 8), -3) == date(2024, 10, 3)


def test_adding_zero_business_days_rolls_onto_one():
    cal = plain()
    assert cal.add_business_days(date(2024, 10, 5), 0) == date(2024, 10, 7)


def test_counting_business_days_between_two_dates():
    cal = plain()
    assert cal.business_days_between(date(2024, 10, 1), date(2024, 10, 8)) == 5


def test_counting_backwards_is_an_error():
    cal = plain()
    with pytest.raises(CalendarError):
        cal.business_days_between(date(2024, 10, 8), date(2024, 10, 1))


def test_month_end_and_month_start():
    assert month_end(date(2024, 2, 14)) == date(2024, 2, 29)
    assert month_end(date(2023, 2, 14)) == date(2023, 2, 28)
    assert month_end(date(2024, 12, 3)) == date(2024, 12, 31)
    assert month_start(date(2024, 10, 17)) == date(2024, 10, 1)


def test_months_between_counts_whole_months():
    assert months_between(date(2024, 1, 15), date(2024, 4, 15)) == 3
    assert months_between(date(2024, 1, 15), date(2024, 4, 14)) == 2
    assert months_between(date(2024, 4, 15), date(2024, 4, 15)) == 0


def test_months_between_never_goes_negative():
    assert months_between(date(2024, 4, 15), date(2024, 4, 16)) == 0


def test_each_month_end_walks_the_span():
    ends = list(each_month_end(date(2024, 10, 15), date(2025, 1, 15)))
    assert ends == [
        date(2024, 10, 31),
        date(2024, 11, 30),
        date(2024, 12, 31),
    ]


def test_the_harvest_calendar_opens_on_saturdays():
    cal = harvest_calendar(2024)
    assert cal.is_business_day(date(2024, 10, 5))
    assert not cal.is_business_day(date(2024, 10, 6))
    assert not cal.is_business_day(date(2024, 12, 25))
    assert not cal.is_business_day(date(2024, 8, 31))
