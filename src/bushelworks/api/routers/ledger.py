"""Ledger routes.

Read only, apart from closing and reopening a month. There is deliberately no
route that posts a journal entry: entries come out of the documents that caused
them, and an endpoint that writes one directly is an endpoint that can put a
number in the books with nothing behind it.

The trial balance drops the accounts nothing landed in unless the caller asks
for them. A chart of twenty five accounts with four in use reads better as four
lines, and the auditor who wants the empty ones asks for them once a year.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select

from ...domain.ledger import Account, TrialBalance, trial_balance
from ...persistence.models import PeriodRow
from ...persistence.unitofwork import UnitOfWork
from ...services.monthend import MonthEnd, close_period, file_year, reopen_period, review
from ..deps import get_uow
from ..schemas import Money, Quantity
from ..security import office_access

router = APIRouter(
    prefix="/ledger",
    tags=["ledger"],
    dependencies=[Depends(office_access)],
)


class AccountOut(Money):
    code: str
    name: str
    type: str
    parent: str | None = None
    is_heading: bool


class BalanceRowOut(Money):
    code: str
    name: str
    type: str
    debits: Quantity
    credits: Quantity
    balance: Quantity


class TrialBalanceOut(Money):
    through: date
    since: date | None = None
    debits: Quantity
    credits: Quantity
    balances: bool
    rows: list[BalanceRowOut]


class LegOut(Money):
    account_code: str
    side: str
    dollars: Quantity
    memo: str


class EntryOut(Money):
    number: str
    posted_on: date
    memo: str
    source: str
    source_reference: str
    reverses: str | None = None
    debits: Quantity
    credits: Quantity
    balances: bool
    legs: list[LegOut]


class PeriodOut(Money):
    code: str
    starts_on: date
    ends_on: date
    closed: bool
    closed_on: date | None = None
    closed_by: str


class OutstandingOut(Money):
    kind: str
    detail: str


class MonthEndOut(Money):
    code: str
    starts_on: date
    ends_on: date
    settlements: int
    unposted: list[str]
    entries: int
    revenue: Quantity
    expense: Quantity
    margin: Quantity
    is_clean: bool
    closed: bool
    outstanding: list[OutstandingOut]


class CloseIn(Money):
    closed_by: str
    on: date | None = None
    force: bool = False


class ReopenIn(Money):
    reason: str


def _account_out(account: Account, is_heading: bool) -> AccountOut:
    return AccountOut(
        code=account.code,
        name=account.name,
        type=account.type.value,
        parent=account.parent,
        is_heading=is_heading,
    )


def _balance_out(
    balance: TrialBalance, since: date | None, include_empty: bool
) -> TrialBalanceOut:
    rows = balance.rows if include_empty else balance.nonempty()
    return TrialBalanceOut(
        through=balance.through,
        since=since,
        debits=balance.debits,
        credits=balance.credits,
        balances=balance.balances,
        rows=[
            BalanceRowOut(
                code=row.account.code,
                name=row.account.name,
                type=row.account.type.value,
                debits=row.debits,
                credits=row.credits,
                balance=row.balance,
            )
            for row in rows
        ],
    )


def _all_periods(uow: UnitOfWork) -> list[PeriodRow]:
    return list(uow.session.scalars(select(PeriodRow).order_by(PeriodRow.starts_on)))


def _period_out(row: PeriodRow) -> PeriodOut:
    return PeriodOut(
        code=row.code,
        starts_on=row.starts_on,
        ends_on=row.ends_on,
        closed=row.closed,
        closed_on=row.closed_on,
        closed_by=row.closed_by,
    )


def _entry_out(uow: UnitOfWork, number: str, reverses: str | None) -> EntryOut:
    entry = uow.ledger.entry(number)
    return EntryOut(
        number=entry.number,
        posted_on=entry.posted_on,
        memo=entry.memo,
        source=entry.source,
        source_reference=entry.source_reference,
        reverses=reverses,
        debits=entry.debits,
        credits=entry.credits,
        balances=entry.balances,
        legs=[
            LegOut(
                account_code=leg.account_code,
                side=leg.side.value,
                dollars=leg.dollars,
                memo=leg.memo,
            )
            for leg in entry.legs
        ],
    )


def _month_out(found: MonthEnd) -> MonthEndOut:
    return MonthEndOut(
        code=found.code,
        starts_on=found.starts_on,
        ends_on=found.ends_on,
        settlements=found.settlements,
        unposted=list(found.unposted),
        entries=found.entries,
        revenue=found.revenue,
        expense=found.expense,
        margin=found.margin,
        is_clean=found.is_clean,
        closed=found.closed,
        outstanding=[
            OutstandingOut(kind=item.kind, detail=item.detail)
            for item in found.outstanding
        ],
    )


@router.get("/accounts", response_model=list[AccountOut])
def read_chart(
    include_inactive: bool = False,
    uow: UnitOfWork = Depends(get_uow),
) -> list[AccountOut]:
    """The filed chart, in code order."""
    chart = uow.ledger.chart(include_inactive=include_inactive)
    return [_account_out(a, chart.is_heading(a.code)) for a in chart]


@router.get("/trial-balance", response_model=TrialBalanceOut)
def read_trial_balance(
    through: date,
    since: date | None = None,
    include_empty: bool = False,
    uow: UnitOfWork = Depends(get_uow),
) -> TrialBalanceOut:
    """The books as at a day, or across a span if ``since`` is given."""
    chart = uow.ledger.chart()
    entries = uow.ledger.entries_between(since or date.min, through)
    balance = trial_balance(chart, entries, through=through, since=since)
    return _balance_out(balance, since, include_empty)


# ``/journal/{number}`` would swallow ``/journal/source/settlement/S-1`` and
# answer it as an entry numbered "source", which is what the audit routes were
# caught doing. The lookups get their own segments.
@router.get("/journal", response_model=list[EntryOut])
def read_journal(
    opens: date,
    closes: date,
    limit: int = Query(default=200, ge=1, le=1000),
    uow: UnitOfWork = Depends(get_uow),
) -> list[EntryOut]:
    """The journal across a span, oldest first."""
    rows = uow.ledger.between(opens, closes)[:limit]
    return [_entry_out(uow, row.number, row.reverses) for row in rows]


@router.get("/journal/entries/{number}", response_model=EntryOut)
def read_entry(number: str, uow: UnitOfWork = Depends(get_uow)) -> EntryOut:
    row = uow.ledger.row(number)
    return _entry_out(uow, row.number, row.reverses)


@router.get("/journal/source/{source}/{reference}", response_model=list[EntryOut])
def read_source_trail(
    source: str,
    reference: str,
    uow: UnitOfWork = Depends(get_uow),
) -> list[EntryOut]:
    """Every entry one document raised, the reversals included."""
    return [
        _entry_out(uow, row.number, row.reverses)
        for row in uow.ledger.for_source(source, reference)
    ]


@router.get("/periods", response_model=list[PeriodOut])
def read_periods(
    open_only: bool = False,
    uow: UnitOfWork = Depends(get_uow),
) -> list[PeriodOut]:
    rows = uow.ledger.open_periods() if open_only else _all_periods(uow)
    return [_period_out(row) for row in rows]


@router.get("/periods/{code}", response_model=MonthEndOut)
def read_period(code: str, uow: UnitOfWork = Depends(get_uow)) -> MonthEndOut:
    """What closing this month would find, without closing it."""
    return _month_out(review(uow, code))


@router.post("/periods/{code}/close", response_model=MonthEndOut)
def close_month(
    code: str,
    body: CloseIn,
    uow: UnitOfWork = Depends(get_uow),
) -> MonthEndOut:
    found = close_period(uow, code, body.closed_by, on=body.on, force=body.force)
    out = _month_out(found)
    uow.commit()
    return out


@router.post("/periods/{code}/reopen", response_model=PeriodOut)
def reopen_month(
    code: str,
    body: ReopenIn,
    uow: UnitOfWork = Depends(get_uow),
) -> PeriodOut:
    row = reopen_period(uow, code, body.reason)
    out = _period_out(row)
    uow.commit()
    return out


@router.post("/years/{year}", response_model=list[PeriodOut])
def file_accounting_year(
    year: int,
    uow: UnitOfWork = Depends(get_uow),
) -> list[PeriodOut]:
    """Put a year of open months on file."""
    rows = file_year(uow, year)
    out = [_period_out(row) for row in rows]
    uow.commit()
    return out
