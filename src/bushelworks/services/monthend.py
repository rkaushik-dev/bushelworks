"""Closing a month, and what has to be true before it will close.

The day close asks whether one day ties. This asks whether a month is finished,
which is a different question with a harder answer: a day can tie perfectly and
still be missing a settlement nobody has keyed yet.

Closing is one way. There is a ``reopen`` here and it takes a reason and writes
it down, because the alternative is somebody editing the ``closed`` column
directly and nobody ever finding out. A month that has been reopened is not the
same as a month that was never closed, and the row says which it is.

The three checks, in the order they are cheapest to fix:

1. every settlement dated in the month is in the journal
2. the month's entries balance
3. the month before this one is closed

The third is the one people argue about. Closing March while February is open
means the February return goes in against figures that can still move, so the
close refuses and says which month to do first.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import select

from ..domain.ledger import (
    AccountType,
    TrialBalance,
    rollup,
    trial_balance,
)
from ..errors import ConflictError, ValidationError
from ..logging import event, failure, get_logger
from ..money import ZERO_AMOUNT, amount
from ..persistence.models import PeriodRow, SettlementRow
from ..persistence.repositories.ledger import PeriodNotFound
from ..persistence.unitofwork import UnitOfWork
from .posting import SOURCE

_log = get_logger("monthend")


class MonthEndError(ValidationError):
    """Raised when a month cannot be closed."""

    code = "month_end_error"


@dataclass(frozen=True, slots=True)
class Outstanding:
    """One thing standing between the month and its close."""

    kind: str
    detail: str

    def __str__(self) -> str:
        return f"{self.kind}: {self.detail}"


@dataclass
class MonthEnd:
    """What closing a month found."""

    code: str
    starts_on: date
    ends_on: date
    settlements: int = 0
    unposted: tuple[str, ...] = ()
    entries: int = 0
    balance: TrialBalance | None = None
    outstanding: list[Outstanding] = field(default_factory=list)
    closed: bool = False

    @property
    def is_clean(self) -> bool:
        return not self.outstanding

    def note(self, kind: str, detail: str) -> None:
        self.outstanding.append(Outstanding(kind, detail))

    @property
    def revenue(self) -> Decimal:
        if self.balance is None:
            return ZERO_AMOUNT
        return self.balance.subtotal(AccountType.REVENUE)

    @property
    def expense(self) -> Decimal:
        if self.balance is None:
            return ZERO_AMOUNT
        return self.balance.subtotal(AccountType.EXPENSE)

    @property
    def margin(self) -> Decimal:
        return amount(self.revenue - self.expense)


def months_of(year: int) -> list[tuple[str, date, date]]:
    """The twelve periods of a calendar year, as code, first day, last day."""
    out: list[tuple[str, date, date]] = []
    for month in range(1, 13):
        starts = date(year, month, 1)
        if month == 12:
            ends = date(year, 12, 31)
        else:
            ends = date(year, month + 1, 1) - timedelta(days=1)
        out.append((f"{year}-{month:02d}", starts, ends))
    return out


def file_year(uow: UnitOfWork, year: int) -> list[PeriodRow]:
    """Put a year of open months on file.

    Run once when the house is set up and once each December. A year that is
    already filed is a conflict rather than a no-op, because the usual way to
    reach this twice is a script that ran further than somebody meant it to.
    """
    if year < 2000 or year > 2100:
        raise MonthEndError(f"implausible accounting year {year}")
    rows = [
        PeriodRow(code=code, starts_on=starts, ends_on=ends)
        for code, starts, ends in months_of(year)
    ]
    filed = uow.ledger.add_periods(rows)
    event(_log, "periods.filed", year=year, months=len(filed))
    return list(filed)


def _previous_code(code: str) -> str:
    year, month = (int(part) for part in code.split("-"))
    if month == 1:
        return f"{year - 1}-12"
    return f"{year}-{month - 1:02d}"


def _settlements_in(uow: UnitOfWork, opens: date, closes: date) -> list[SettlementRow]:
    return list(
        uow.session.scalars(
            select(SettlementRow)
            .where(
                SettlementRow.settled_on >= opens,
                SettlementRow.settled_on <= closes,
                SettlementRow.voided.is_(False),
            )
            .order_by(SettlementRow.number)
        )
    )


def review(uow: UnitOfWork, code: str) -> MonthEnd:
    """Work out whether a month would close, without closing it."""
    period = uow.ledger.period(code)
    found = MonthEnd(
        code=period.code, starts_on=period.starts_on, ends_on=period.ends_on
    )

    settlements = _settlements_in(uow, period.starts_on, period.ends_on)
    found.settlements = len(settlements)
    missing = tuple(
        row.number
        for row in settlements
        if not uow.ledger.is_posted(SOURCE, row.number)
    )
    found.unposted = missing
    if missing:
        listed = ", ".join(missing[:5])
        found.note(
            "unposted",
            f"{len(missing)} settlements are not in the journal: {listed}",
        )

    entries = uow.ledger.entries_between(period.starts_on, period.ends_on)
    found.entries = len(entries)
    balance = trial_balance(
        uow.ledger.chart(), entries, through=period.ends_on, since=period.starts_on
    )
    found.balance = balance
    if not balance.balances:
        found.note(
            "journal",
            f"the month posts {balance.debits} of debits against "
            f"{balance.credits} of credits",
        )

    if period.closed:
        found.note("closed", f"{period.code} was closed on {period.closed_on}")
        found.closed = True
        return found

    try:
        before: PeriodRow | None = uow.ledger.period(_previous_code(period.code))
    except PeriodNotFound:
        before = None
    if before is not None and not before.closed:
        found.note(
            "sequence",
            f"{before.code} is still open, so close that one first",
        )
    return found


def close_period(
    uow: UnitOfWork,
    code: str,
    closed_by: str,
    on: date | None = None,
    force: bool = False,
) -> MonthEnd:
    """Close a month, if it is finished.

    ``force`` closes over the outstanding items and records who did it. It
    exists because the last month of a year sometimes has to go in while one
    settlement is still being argued about, and the alternative to a flag that
    writes down who overrode the check is somebody updating the row by hand.
    """
    found = review(uow, code)
    if found.closed:
        raise ConflictError(f"{code} is already closed")
    if not found.is_clean and not force:
        listed = "; ".join(str(item) for item in found.outstanding[:5])
        raise ConflictError(f"{code} will not close: {listed}")

    period = uow.ledger.period(code)
    period.closed = True
    period.closed_on = on or date.today()
    period.closed_by = closed_by
    uow.flush()
    found.closed = True

    if found.is_clean:
        event(
            _log,
            "period.closed",
            period=code,
            by=closed_by,
            settlements=found.settlements,
            entries=found.entries,
            margin=str(found.margin),
        )
    else:
        failure(
            _log,
            "period.forced",
            period=code,
            by=closed_by,
            outstanding=len(found.outstanding),
        )
    return found


def reopen_period(uow: UnitOfWork, code: str, reason: str) -> PeriodRow:
    """Open a closed month again, and say why in the log.

    The return has already gone in by the time anybody wants this, so reopening
    a month means the return will have to be amended. That is a decision for a
    person; all this does is make it a recorded one.
    """
    if not reason.strip():
        raise MonthEndError("reopening a month needs a reason")
    period = uow.ledger.period(code)
    if not period.closed:
        raise ConflictError(f"{code} is not closed")
    period.closed = False
    period.closed_on = None
    period.closed_by = ""
    uow.flush()
    failure(_log, "period.reopened", period=code, reason=reason)
    return period


def earliest_open(uow: UnitOfWork) -> PeriodRow | None:
    """The month a late ticket should be keyed into."""
    rows = uow.ledger.open_periods()
    return rows[0] if rows else None


def margins_by_account(uow: UnitOfWork, code: str) -> dict[str, Decimal]:
    """What each revenue and expense account did in a month, headings filled in."""
    period = uow.ledger.period(code)
    chart = uow.ledger.chart()
    entries = uow.ledger.entries_between(period.starts_on, period.ends_on)
    balance = trial_balance(
        chart, entries, through=period.ends_on, since=period.starts_on
    )
    totals = rollup(chart, balance)
    return {
        account.code: totals[account.code]
        for account in chart
        if account.type in (AccountType.REVENUE, AccountType.EXPENSE)
    }
