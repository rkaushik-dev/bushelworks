"""Chart and journal tables.

The journal is append only and there is no update path to it anywhere in this
package. An entry that was wrong is corrected by posting its reverse, which is
why ``reverses`` points at another entry and why nothing here has a status
column that could be flipped to ``void``. An examiner who can watch a row change
cannot rely on any of the rest of it.

Legs hang off the entry with a cascade, because a leg without its entry is not a
half of anything. The check constraint on the amount is the same rule the domain
object carries, restated where the database can enforce it: a leg is a positive
magnitude and a side, never a signed number.

``posted_on`` is indexed with the entry number rather than alone. Every read in
this package is "the journal for a month" or "the journal for a month in code
order", and the second one was doing a filesort until the index picked up the
number as well.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import (
    CheckConstraint,
    Date,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..base import MONEY, Base
from .reference import TimestampMixin


class AccountRow(Base, TimestampMixin):
    """One account in the filed chart."""

    __tablename__ = "ledger_account"
    __table_args__ = (
        UniqueConstraint("code", name="uq_ledger_account_code"),
        CheckConstraint(
            "type in ('asset', 'liability', 'equity', 'revenue', 'expense')",
            name="type_is_known",
        ),
        CheckConstraint("code <> parent_code", name="not_its_own_parent"),
        Index("ix_ledger_account_parent_code", "parent_code"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(12), nullable=False)
    name: Mapped[str] = mapped_column(String(80), nullable=False)
    type: Mapped[str] = mapped_column(String(12), nullable=False)
    parent_code: Mapped[str | None] = mapped_column(String(12))
    closes_to: Mapped[str | None] = mapped_column(String(12))
    active: Mapped[bool] = mapped_column(nullable=False, default=True)

    def __str__(self) -> str:
        return f"{self.code} {self.name}"


class JournalEntryRow(Base, TimestampMixin):
    """A balanced posting, with the thing it came from named."""

    __tablename__ = "journal_entry"
    __table_args__ = (
        UniqueConstraint("number", name="uq_journal_entry_number"),
        Index("ix_journal_entry_posted_on", "posted_on", "number"),
        Index("ix_journal_entry_source", "source", "source_reference"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), nullable=False)
    posted_on: Mapped[date] = mapped_column(Date, nullable=False)
    memo: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    source: Mapped[str] = mapped_column(String(24), nullable=False, default="")
    source_reference: Mapped[str] = mapped_column(String(48), nullable=False, default="")
    reverses: Mapped[str | None] = mapped_column(String(24))

    legs: Mapped[list[JournalLegRow]] = relationship(
        back_populates="entry",
        cascade="all, delete-orphan",
        lazy="selectin",
        order_by="JournalLegRow.sequence",
    )

    def __str__(self) -> str:
        return f"{self.number} {self.posted_on}"


class JournalLegRow(Base):
    """One side of one entry.

    ``sequence`` exists so the legs come back in the order they were written
    rather than in whatever order the primary keys happen to fall. An entry read
    back with its credits before its debits is still correct and still reads
    wrong to the person checking it.
    """

    __tablename__ = "journal_leg"
    __table_args__ = (
        UniqueConstraint("entry_id", "sequence", name="uq_journal_leg_entry_id"),
        CheckConstraint("dollars > 0", name="leg_is_positive"),
        CheckConstraint("side in ('debit', 'credit')", name="side_is_known"),
        Index("ix_journal_leg_account_code", "account_code"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    entry_id: Mapped[int] = mapped_column(
        ForeignKey("journal_entry.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(nullable=False)
    account_code: Mapped[str] = mapped_column(String(12), nullable=False)
    side: Mapped[str] = mapped_column(String(6), nullable=False)
    dollars: Mapped[Decimal] = mapped_column(MONEY, nullable=False)
    memo: Mapped[str] = mapped_column(String(120), nullable=False, default="")

    entry: Mapped[JournalEntryRow] = relationship(back_populates="legs")

    def __str__(self) -> str:
        return f"{self.account_code} {self.side} {self.dollars}"


class PeriodRow(Base, TimestampMixin):
    """An accounting month, and whether anything can still be posted into it.

    The house closes a month when the return goes to the state, and a ticket
    keyed late after that has to land in the open month instead. Without a row
    saying so, the late ticket posts into a period whose figures have already
    been filed and nobody finds out until the next return does not tie back.
    """

    __tablename__ = "ledger_period"
    __table_args__ = (
        UniqueConstraint("code", name="uq_ledger_period_code"),
        CheckConstraint("ends_on >= starts_on", name="period_runs_forwards"),
        Index("ix_ledger_period_starts_on", "starts_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(7), nullable=False)
    starts_on: Mapped[date] = mapped_column(Date, nullable=False)
    ends_on: Mapped[date] = mapped_column(Date, nullable=False)
    closed: Mapped[bool] = mapped_column(nullable=False, default=False)
    closed_on: Mapped[date | None] = mapped_column(Date)
    closed_by: Mapped[str] = mapped_column(String(48), nullable=False, default="")

    def __str__(self) -> str:
        return f"{self.code} {'closed' if self.closed else 'open'}"
