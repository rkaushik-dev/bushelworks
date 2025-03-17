"""Complaint tables.

A complaint is filed against something, and which something varies: a car the
buyer refused names a sale, a producer arguing about his moisture names a
ticket, and a feedlot ringing about a smell names neither and only knows which
week it was. So every one of those columns is nullable and the only thing that
is not is the subject.

The retained sample is the load bearing one. ``probe_id`` says which sample was
kept, and whether it was still on the shelf when the complaint arrived decides
whether the house argues or pays. It is a plain string rather than a foreign key
because samples are thrown out on a rota and the row that named one should not
stop that happening.
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


class ComplaintRow(Base, TimestampMixin):
    """One thing somebody rang up about."""

    __tablename__ = "complaint"
    __table_args__ = (
        UniqueConstraint("reference", name="uq_complaint_reference"),
        CheckConstraint("credit >= 0", name="credit_not_negative"),
        CheckConstraint("raised_on >= delivered_on", name="raised_after_delivery"),
        Index("ix_complaint_bin_id", "bin_id", "raised_on"),
        Index("ix_complaint_party_code", "party_code"),
        Index("ix_complaint_outcome", "outcome", "raised_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    reference: Mapped[str] = mapped_column(String(24), nullable=False)
    source: Mapped[str] = mapped_column(String(12), nullable=False)
    subject: Mapped[str] = mapped_column(String(200), nullable=False)
    raised_on: Mapped[date] = mapped_column(Date, nullable=False)
    delivered_on: Mapped[date] = mapped_column(Date, nullable=False)
    party_code: Mapped[str | None] = mapped_column(String(24))
    bin_id: Mapped[str | None] = mapped_column(String(12))
    ticket_number: Mapped[str | None] = mapped_column(String(24))
    sale_number: Mapped[str | None] = mapped_column(String(24))
    probe_id: Mapped[str | None] = mapped_column(String(24))
    outcome: Mapped[str] = mapped_column(String(12), nullable=False, default="open")
    credit: Mapped[Decimal] = mapped_column(MONEY, nullable=False, default=Decimal("0"))
    closed_on: Mapped[date | None] = mapped_column(Date)
    note: Mapped[str] = mapped_column(String(400), nullable=False, default="")

    actions: Mapped[list[ComplaintActionRow]] = relationship(
        back_populates="complaint",
        cascade="all, delete-orphan",
        lazy="selectin",
        order_by="ComplaintActionRow.taken_on",
    )

    def __str__(self) -> str:
        return f"{self.reference} {self.outcome}"


class ComplaintActionRow(Base):
    """What was done about a complaint, in the order it was done.

    Kept apart from the complaint row because a complaint that took a fortnight
    and four telephone calls to settle is one row with four actions, and
    flattening that into a note column loses who did what and when.
    """

    __tablename__ = "complaint_action"
    __table_args__ = (
        UniqueConstraint(
            "complaint_id", "sequence", name="uq_complaint_action_complaint_id"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    complaint_id: Mapped[int] = mapped_column(
        ForeignKey("complaint.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(nullable=False)
    taken_on: Mapped[date] = mapped_column(Date, nullable=False)
    taken_by: Mapped[str] = mapped_column(String(40), nullable=False)
    what: Mapped[str] = mapped_column(String(200), nullable=False)

    complaint: Mapped[ComplaintRow] = relationship(back_populates="actions")

    def __str__(self) -> str:
        return f"{self.taken_on} {self.taken_by}: {self.what}"
