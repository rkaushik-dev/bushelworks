"""The audit trail.

Not a log. The log is for the office and rotates; this is evidence and does not.
Every row records who did what to which document and what the money was, and
nothing in the application updates or deletes one.

The rows are deliberately denormalised. A foreign key to a settlement that is
later voided and reissued leaves the audit row pointing at a document that no
longer says what it said, so the document number is stored as text alongside the
figures as they stood.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import CheckConstraint, Date, DateTime, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from ..base import MONEY, Base
from .reference import utc_now


class AuditRow(Base):
    """One thing that happened, as it looked when it happened."""

    __tablename__ = "audit_entry"
    __table_args__ = (
        CheckConstraint("length(action) > 0", name="action_not_blank"),
        Index("ix_audit_entry_recorded_at", "recorded_at"),
        Index("ix_audit_entry_document", "document_kind", "document_number"),
        Index("ix_audit_entry_actor", "actor"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )
    effective_on: Mapped[date | None] = mapped_column(Date)
    actor: Mapped[str] = mapped_column(String(60), nullable=False, default="system")
    action: Mapped[str] = mapped_column(String(40), nullable=False)
    document_kind: Mapped[str] = mapped_column(String(24), nullable=False)
    document_number: Mapped[str] = mapped_column(String(24), nullable=False)
    party_code: Mapped[str | None] = mapped_column(String(24))
    location_code: Mapped[str | None] = mapped_column(String(12))
    amount: Mapped[Decimal | None] = mapped_column(MONEY)
    detail: Mapped[str | None] = mapped_column(String(400))
    request_id: Mapped[str | None] = mapped_column(String(32))

    def __str__(self) -> str:
        return f"{self.action} {self.document_kind} {self.document_number}"
