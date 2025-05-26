"""Closing the year.

Revenue and expense accounts measure a year. On the first day of the next one
they have to read nothing, or the second year's income statement shows the
first year's margin on top of its own. Closing is the entry that empties them
into equity, and it is the only entry in this package that is not raised by a
document.

It is built out of the trial balance rather than out of the journal, which
matters when a house has reopened a month and posted into it: the balance is
what the accounts say now, not what they said when each entry went in.

``closes_to`` on an account is what makes this worth having as its own module.
Most houses send everything to retained earnings, but a co-op sends the margin
it earned on member business to a patronage account and the rest to retained
earnings, and those two numbers are read off different accounts by different
people. An account that names a destination goes there; everything else goes to
the default, and each destination gets its own leg so the entry says which is
which.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..domain.ledger import (
    AccountType,
    ChartOfAccounts,
    JournalEntry,
    Leg,
    Side,
    TrialBalance,
    trial_balance,
)
from ..errors import ConflictError, ValidationError
from ..logging import event, get_logger
from ..money import ZERO_AMOUNT, amount
from ..persistence.repositories.ledger import PeriodNotFound
from ..persistence.unitofwork import UnitOfWork
from .monthend import months_of

_log = get_logger("closing")

SOURCE = "year-end"
RETAINED_EARNINGS = "3100"


class ClosingError(ValidationError):
    """Raised when the year will not close."""

    code = "closing_error"


@dataclass(frozen=True, slots=True)
class Destination:
    """One equity account and what is being closed into it."""

    account_code: str
    dollars: Decimal
    from_accounts: tuple[str, ...]


@dataclass
class YearEnd:
    """What closing a year found."""

    year: int
    revenue: Decimal = ZERO_AMOUNT
    expense: Decimal = ZERO_AMOUNT
    destinations: tuple[Destination, ...] = ()
    entry: JournalEntry | None = None
    outstanding: list[str] = field(default_factory=list)
    closed: bool = False

    @property
    def margin(self) -> Decimal:
        return amount(self.revenue - self.expense)

    @property
    def is_clean(self) -> bool:
        return not self.outstanding

    def note(self, detail: str) -> None:
        self.outstanding.append(detail)


def _closes_to(chart: ChartOfAccounts, code: str, default: str) -> str:
    """Where an account's balance goes, inheriting from its heading.

    A house that sets ``closes_to`` on the ``4000 Revenue`` heading means it for
    everything under it, and setting it on twelve leaves instead is twelve
    chances to miss one.
    """
    for account in reversed(chart.lineage(code)):
        if account.closes_to:
            return account.closes_to
    return default


def closing_entry(
    chart: ChartOfAccounts,
    balance: TrialBalance,
    number: str,
    on: date,
    retained: str = RETAINED_EARNINGS,
) -> JournalEntry:
    """The entry that empties every revenue and expense account.

    Each account is posted on the side opposite the one it accumulated on, for
    exactly what it holds, so it reads zero afterwards. A contra balance, which
    is a revenue account sitting on the debit side because it took more
    reversals than sales, is closed the other way round rather than skipped.
    """
    chart.postable(retained)
    legs: list[Leg] = []
    owed: dict[str, Decimal] = {}

    for row in balance.rows:
        account = row.account
        if account.type not in (AccountType.REVENUE, AccountType.EXPENSE):
            continue
        held = row.balance
        if held == ZERO_AMOUNT:
            continue
        side = (
            account.normal_side.opposite if held > 0 else account.normal_side
        )
        legs.append(
            Leg(
                account_code=account.code,
                side=side,
                dollars=abs(held),
                memo=f"close {account.code}",
            )
        )
        target = _closes_to(chart, account.code, retained)
        contribution = held if account.type is AccountType.REVENUE else -held
        owed[target] = amount(owed.get(target, ZERO_AMOUNT) + contribution)

    if not legs:
        raise ClosingError(
            f"nothing to close for {on.year}: no revenue or expense account "
            "carries a balance"
        )

    for target in sorted(owed):
        share = owed[target]
        if share == ZERO_AMOUNT:
            continue
        chart.postable(target)
        legs.append(
            Leg(
                account_code=target,
                side=Side.CREDIT if share > 0 else Side.DEBIT,
                dollars=abs(share),
                memo=f"margin closed to {target}",
            )
        )

    entry = JournalEntry(
        number=number,
        posted_on=on,
        memo=f"year end close {on.year}",
        legs=tuple(legs),
        source=SOURCE,
        source_reference=str(on.year),
    )
    entry.check_balances()
    return entry


def _destinations(
    chart: ChartOfAccounts, entry: JournalEntry, retained: str
) -> tuple[Destination, ...]:
    equity = {
        leg.account_code
        for leg in entry.legs
        if chart.get(leg.account_code).type is AccountType.EQUITY
    }
    equity.add(retained)
    out: list[Destination] = []
    for code in sorted(equity):
        legs = entry.touching(code)
        if not legs:
            continue
        leg = legs[0]
        dollars = leg.dollars if leg.side is Side.CREDIT else amount(-leg.dollars)
        out.append(
            Destination(
                account_code=code,
                dollars=dollars,
                from_accounts=tuple(
                    sorted(
                        other.account_code
                        for other in entry.legs
                        if other.account_code != code
                        and _closes_to(chart, other.account_code, retained) == code
                    )
                ),
            )
        )
    return tuple(out)


def review_year(
    uow: UnitOfWork, year: int, retained: str = RETAINED_EARNINGS
) -> YearEnd:
    """Work out what closing a year would post, and what is in the way."""
    if year < 2000 or year > 2100:
        raise ClosingError(f"implausible accounting year {year}")
    found = YearEnd(year=year)

    open_months = [
        code
        for code, _, _ in months_of(year)
        if not _is_closed(uow, code, found)
    ]
    if open_months:
        found.note(
            f"{len(open_months)} months are still open: {', '.join(open_months[:5])}"
        )

    if uow.ledger.is_posted(SOURCE, str(year)):
        found.note(f"{year} has already been closed")
        found.closed = True

    chart = uow.ledger.chart()
    opens, closes = date(year, 1, 1), date(year, 12, 31)
    entries = uow.ledger.entries_between(opens, closes)
    balance = trial_balance(chart, entries, through=closes, since=opens)
    if not balance.balances:
        found.note(
            f"the year posts {balance.debits} of debits against "
            f"{balance.credits} of credits"
        )

    found.revenue = balance.subtotal(AccountType.REVENUE)
    found.expense = balance.subtotal(AccountType.EXPENSE)
    if found.margin == ZERO_AMOUNT and found.revenue == ZERO_AMOUNT:
        found.note(f"{year} has no revenue or expense on the books")
        return found

    entry = closing_entry(
        chart, balance, uow.ledger.next_number("Y", closes), closes, retained
    )
    found.entry = entry
    found.destinations = _destinations(chart, entry, retained)
    return found


def _is_closed(uow: UnitOfWork, code: str, found: YearEnd) -> bool:
    try:
        return uow.ledger.period(code).closed
    except PeriodNotFound:
        found.note(f"{code} is not on file")
        return True


def close_year(
    uow: UnitOfWork,
    year: int,
    closed_by: str,
    retained: str = RETAINED_EARNINGS,
    force: bool = False,
) -> YearEnd:
    """Post the closing entry, if the year is finished."""
    found = review_year(uow, year, retained)
    if found.closed:
        raise ConflictError(f"{year} has already been closed")
    if not found.is_clean and not force:
        raise ConflictError(
            f"{year} will not close: {'; '.join(found.outstanding[:5])}"
        )
    if found.entry is None:
        raise ClosingError(f"nothing to close for {year}")

    found.entry.check_against(uow.ledger.chart())
    uow.ledger.post(found.entry)
    found.closed = True
    event(
        _log,
        "year.closed",
        year=year,
        by=closed_by,
        entry=found.entry.number,
        margin=str(found.margin),
    )
    return found


def opening_balances(
    uow: UnitOfWork, year: int, retained: str = RETAINED_EARNINGS
) -> dict[str, Decimal]:
    """What the balance sheet accounts carry into a year.

    Revenue and expense are not in here at all. If the year before was closed
    they are zero anyway, and if it was not then carrying them forward would
    hide that nobody had closed it.
    """
    chart = uow.ledger.chart()
    closes = date(year - 1, 12, 31)
    entries = uow.ledger.entries_between(date.min, closes)
    balance = trial_balance(chart, entries, through=closes)
    carried = {
        row.account.code: row.balance
        for row in balance.rows
        if row.account.type.on_balance_sheet and row.balance != ZERO_AMOUNT
    }
    carried.setdefault(retained, ZERO_AMOUNT)
    return carried


def render(found: YearEnd, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The year end as it goes in the minute book."""
    width = 72
    lines = [
        house_name.center(width).rstrip(),
        f"YEAR END {found.year}".center(width).rstrip(),
        "=" * width,
        f"{'revenue':<28}{found.revenue:>16}",
        f"{'expense':<28}{found.expense:>16}",
        f"{'margin':<28}{found.margin:>16}",
        "-" * width,
    ]
    for destination in found.destinations:
        lines.append(f"{destination.account_code:<28}{destination.dollars:>16}")
    lines.append("-" * width)
    if found.is_clean:
        lines.append("closed" if found.closed else "would close clean")
    else:
        lines.append(f"{len(found.outstanding)} outstanding:")
        lines.extend(f"  {item}" for item in found.outstanding)
    return "\n".join(lines)


def accounts_left_open(
    chart: ChartOfAccounts, balance: TrialBalance, codes: Sequence[str] = ()
) -> tuple[str, ...]:
    """Revenue or expense accounts still carrying a balance after a close.

    Run against the trial balance for the day after the close. Anything in here
    is an account the closing entry missed, which happens when somebody posts
    into a closed year.
    """
    wanted = set(codes) if codes else None
    return tuple(
        row.account.code
        for row in balance.rows
        if row.account.type in (AccountType.REVENUE, AccountType.EXPENSE)
        and row.balance != ZERO_AMOUNT
        and (wanted is None or row.account.code in wanted)
    )
