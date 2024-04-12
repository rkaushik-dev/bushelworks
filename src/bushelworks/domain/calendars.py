"""The business calendar the office actually keeps.

Storage free periods are counted in plain days, but cheque runs, futures rolls
and the daily position close all move to the next business day, and a country
elevator's business days are not the bank's. Harvest is the obvious case: the
scale runs on the Saturday after Thanksgiving whatever the calendar says.

Holidays are declared rather than computed. A rule that derives Thanksgiving
from the fourth Thursday is correct and useless, because the house closes when
the manager says it closes.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Iterable

from ..errors import ConfigurationError, ValidationError

SATURDAY = 5
SUNDAY = 6


class CalendarError(ValidationError):
    """Raised when a date cannot be resolved on this calendar."""

    code = "calendar_error"


@dataclass
class BusinessCalendar:
    """Which days the office counts as working days."""

    name: str
    holidays: set[date] = field(default_factory=set)
    weekend_days: frozenset[int] = frozenset({SATURDAY, SUNDAY})
    extra_working_days: set[date] = field(default_factory=set)

    def __post_init__(self) -> None:
        clash = self.holidays & self.extra_working_days
        if clash:
            listed = ", ".join(str(day) for day in sorted(clash))
            raise ConfigurationError(
                f"{self.name}: {listed} is both a holiday and a working day"
            )
        if not self.weekend_days <= frozenset(range(7)):
            raise ConfigurationError(f"{self.name}: weekend days out of range")

    def is_business_day(self, day: date) -> bool:
        if day in self.extra_working_days:
            return True
        if day in self.holidays:
            return False
        return day.weekday() not in self.weekend_days

    def next_business_day(self, day: date) -> date:
        cursor = day + timedelta(days=1)
        for _ in range(400):
            if self.is_business_day(cursor):
                return cursor
            cursor += timedelta(days=1)
        raise CalendarError(f"{self.name}: no business day within a year of {day}")

    def previous_business_day(self, day: date) -> date:
        cursor = day - timedelta(days=1)
        for _ in range(400):
            if self.is_business_day(cursor):
                return cursor
            cursor -= timedelta(days=1)
        raise CalendarError(f"{self.name}: no business day within a year before {day}")

    def roll_forward(self, day: date) -> date:
        """The day itself if it works, otherwise the next one that does."""
        return day if self.is_business_day(day) else self.next_business_day(day)

    def roll_backward(self, day: date) -> date:
        return day if self.is_business_day(day) else self.previous_business_day(day)

    def add_business_days(self, day: date, count: int) -> date:
        if count == 0:
            return self.roll_forward(day)
        step = self.next_business_day if count > 0 else self.previous_business_day
        cursor = day
        for _ in range(abs(count)):
            cursor = step(cursor)
        return cursor

    def business_days_between(self, start: date, end: date) -> int:
        """Working days after ``start`` up to and including ``end``."""
        if end < start:
            raise CalendarError(f"{self.name}: {end} is before {start}")
        count = 0
        cursor = start + timedelta(days=1)
        while cursor <= end:
            if self.is_business_day(cursor):
                count += 1
            cursor += timedelta(days=1)
        return count

    def declare_holiday(self, day: date) -> None:
        if day in self.extra_working_days:
            raise ConfigurationError(
                f"{self.name}: {day} is already declared a working day"
            )
        self.holidays.add(day)

    def declare_working_day(self, day: date) -> None:
        if day in self.holidays:
            raise ConfigurationError(f"{self.name}: {day} is already a holiday")
        self.extra_working_days.add(day)


def month_end(day: date) -> date:
    """The last calendar day of the month ``day`` falls in."""
    if day.month == 12:
        return date(day.year, 12, 31)
    return date(day.year, day.month + 1, 1) - timedelta(days=1)


def month_start(day: date) -> date:
    return date(day.year, day.month, 1)


def months_between(start: date, end: date) -> int:
    """Whole calendar months from ``start`` to ``end``, never negative."""
    if end < start:
        raise CalendarError(f"{end} is before {start}")
    months = (end.year - start.year) * 12 + (end.month - start.month)
    if end.day < start.day:
        months -= 1
    return max(months, 0)


def each_month_end(start: date, end: date) -> Iterable[date]:
    """Every month end in the span, which is what the storage run walks."""
    if end < start:
        raise CalendarError(f"{end} is before {start}")
    cursor = month_end(start)
    while cursor <= end:
        yield cursor
        cursor = month_end(cursor + timedelta(days=1))


def harvest_calendar(year: int) -> BusinessCalendar:
    """The house calendar for one crop year.

    Closed on the fixed holidays, open on the Saturdays of harvest, which for
    this house runs from the middle of September to the end of November.
    """
    calendar = BusinessCalendar(name=f"house-{year}")
    for month, day in ((1, 1), (7, 4), (12, 25)):
        calendar.declare_holiday(date(year, month, day))
    cursor = date(year, 9, 15)
    while cursor <= date(year, 11, 30):
        if cursor.weekday() == SATURDAY:
            calendar.declare_working_day(cursor)
        cursor += timedelta(days=1)
    return calendar
