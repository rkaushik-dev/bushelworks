"""The ledger repository.

The journal has no update method and no delete method, and that is the point of
the class rather than an omission. Everything the office needs to do to a wrong
entry is done by posting another one.

``chart`` reads the whole account table and builds the domain object, because
the chart is twenty five rows and every caller wants the tree rather than a row.
The journal is the other way round: it is read a month at a time and returned as
rows, and the caller decides whether it wants domain entries out of them.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ...domain.ledger import (
    Account,
    AccountType,
    ChartOfAccounts,
    JournalEntry,
    Leg,
    Side,
)
from ...errors import ConflictError, LookupFailure
from ..base import as_decimal
from ..models import AccountRow, JournalEntryRow, JournalLegRow, PeriodRow


class JournalEntryNotFound(LookupFailure):
    code = "journal_entry_not_found"


class PeriodNotFound(LookupFailure):
    code = "period_not_found"


def _account_from(row: AccountRow) -> Account:
    return Account(
        code=row.code,
        name=row.name,
        type=AccountType(row.type),
        parent=row.parent_code,
        closes_to=row.closes_to,
    )


def _entry_from(row: JournalEntryRow) -> JournalEntry:
    return JournalEntry(
        number=row.number,
        posted_on=row.posted_on,
        memo=row.memo,
        legs=tuple(
            Leg(
                account_code=leg.account_code,
                side=Side(leg.side),
                dollars=as_decimal(leg.dollars),
                memo=leg.memo,
            )
            for leg in row.legs
        ),
        source=row.source,
        source_reference=row.source_reference,
    )


class LedgerRepository:
    """The chart, the journal and the periods."""

    def __init__(self, session: Session) -> None:
        self.session = session

    # The chart

    def chart(self, include_inactive: bool = False) -> ChartOfAccounts:
        """The filed chart, parents before children so it builds in one pass."""
        query = select(AccountRow)
        if not include_inactive:
            query = query.where(AccountRow.active.is_(True))
        rows = list(self.session.scalars(query.order_by(AccountRow.code)))
        chart = ChartOfAccounts()
        placed: set[str] = set()
        remaining = list(rows)
        while remaining:
            progressed = False
            still: list[AccountRow] = []
            for row in remaining:
                if row.parent_code is None or row.parent_code in placed:
                    chart.add(_account_from(row))
                    placed.add(row.code)
                    progressed = True
                else:
                    still.append(row)
            if not progressed:
                orphans = ", ".join(sorted(r.code for r in still))
                raise ConflictError(
                    f"these accounts name a parent that is not on file: {orphans}"
                )
            remaining = still
        return chart

    def file_chart(self, chart: ChartOfAccounts) -> None:
        """Write a chart that is not on file yet. Refuses to touch one that is."""
        existing = self.session.scalar(select(func.count()).select_from(AccountRow))
        if existing:
            raise ConflictError(
                f"the chart already has {existing} accounts on file; add to it "
                "rather than filing it again"
            )
        for account in chart:
            self.session.add(
                AccountRow(
                    code=account.code,
                    name=account.name,
                    type=account.type.value,
                    parent_code=account.parent,
                    closes_to=account.closes_to,
                )
            )
        self.session.flush()

    def add_account(self, account: Account) -> AccountRow:
        clash = self.session.scalar(
            select(AccountRow).where(AccountRow.code == account.code)
        )
        if clash is not None:
            raise ConflictError(f"account {account.code} is already on file")
        row = AccountRow(
            code=account.code,
            name=account.name,
            type=account.type.value,
            parent_code=account.parent,
            closes_to=account.closes_to,
        )
        self.session.add(row)
        self.session.flush()
        return row

    # The journal

    def post(self, entry: JournalEntry, reverses: str | None = None) -> JournalEntryRow:
        """Write a balanced entry. The caller checks it against the chart first."""
        entry.check_balances()
        clash = self.session.scalar(
            select(JournalEntryRow).where(JournalEntryRow.number == entry.number)
        )
        if clash is not None:
            raise ConflictError(f"journal entry {entry.number} is already posted")
        row = JournalEntryRow(
            number=entry.number,
            posted_on=entry.posted_on,
            memo=entry.memo,
            source=entry.source,
            source_reference=entry.source_reference,
            reverses=reverses,
        )
        for sequence, leg in enumerate(entry.legs, start=1):
            row.legs.append(
                JournalLegRow(
                    sequence=sequence,
                    account_code=leg.account_code,
                    side=leg.side.value,
                    dollars=leg.dollars,
                    memo=leg.memo,
                )
            )
        self.session.add(row)
        self.session.flush()
        return row

    def row(self, number: str) -> JournalEntryRow:
        found = self.session.scalar(
            select(JournalEntryRow).where(JournalEntryRow.number == number)
        )
        if found is None:
            raise JournalEntryNotFound(f"no journal entry {number}")
        return found

    def entry(self, number: str) -> JournalEntry:
        return _entry_from(self.row(number))

    def between(self, opens: date, closes: date) -> list[JournalEntryRow]:
        return list(
            self.session.scalars(
                select(JournalEntryRow)
                .where(
                    JournalEntryRow.posted_on >= opens,
                    JournalEntryRow.posted_on <= closes,
                )
                .order_by(JournalEntryRow.posted_on, JournalEntryRow.number)
            )
        )

    def entries_between(self, opens: date, closes: date) -> list[JournalEntry]:
        return [_entry_from(row) for row in self.between(opens, closes)]

    def for_source(self, source: str, reference: str) -> list[JournalEntryRow]:
        """Every entry raised out of one document, the reversal included."""
        return list(
            self.session.scalars(
                select(JournalEntryRow)
                .where(
                    JournalEntryRow.source == source,
                    JournalEntryRow.source_reference == reference,
                )
                .order_by(JournalEntryRow.posted_on, JournalEntryRow.number)
            )
        )

    def is_posted(self, source: str, reference: str) -> bool:
        return bool(self.for_source(source, reference))

    def reversal_of(self, number: str) -> JournalEntryRow | None:
        return self.session.scalar(
            select(JournalEntryRow).where(JournalEntryRow.reverses == number)
        )

    def legs_on(
        self, account_code: str, opens: date, closes: date
    ) -> list[JournalLegRow]:
        return list(
            self.session.scalars(
                select(JournalLegRow)
                .join(JournalEntryRow)
                .where(
                    JournalLegRow.account_code == account_code,
                    JournalEntryRow.posted_on >= opens,
                    JournalEntryRow.posted_on <= closes,
                )
                .order_by(JournalEntryRow.posted_on, JournalLegRow.sequence)
            )
        )

    def next_number(self, prefix: str, on: date) -> str:
        """A journal number in the house's own shape, for tests and back fills.

        Real numbering comes off the locked counter row in
        :mod:`bushelworks.services.numbering`. This is here so a repository test
        does not have to stand a counter up to post two entries.
        """
        stem = f"{prefix}-{on:%Y%m}-"
        taken = self.session.scalars(
            select(JournalEntryRow.number).where(
                JournalEntryRow.number.like(f"{stem}%")
            )
        )
        highest = 0
        for number in taken:
            tail = number.rsplit("-", 1)[-1]
            if tail.isdigit():
                highest = max(highest, int(tail))
        return f"{stem}{highest + 1:04d}"

    # Periods

    def period(self, code: str) -> PeriodRow:
        found = self.session.scalar(select(PeriodRow).where(PeriodRow.code == code))
        if found is None:
            raise PeriodNotFound(f"no accounting period {code}")
        return found

    def period_covering(self, day: date) -> PeriodRow:
        found = self.session.scalar(
            select(PeriodRow).where(
                PeriodRow.starts_on <= day, PeriodRow.ends_on >= day
            )
        )
        if found is None:
            raise PeriodNotFound(f"no accounting period covers {day}")
        return found

    def open_periods(self) -> list[PeriodRow]:
        return list(
            self.session.scalars(
                select(PeriodRow)
                .where(PeriodRow.closed.is_(False))
                .order_by(PeriodRow.starts_on)
            )
        )

    def add_periods(self, periods: Iterable[PeriodRow]) -> Sequence[PeriodRow]:
        added = list(periods)
        for row in added:
            clash = self.session.scalar(
                select(PeriodRow).where(PeriodRow.code == row.code)
            )
            if clash is not None:
                raise ConflictError(f"period {row.code} is already on file")
            self.session.add(row)
        self.session.flush()
        return added
