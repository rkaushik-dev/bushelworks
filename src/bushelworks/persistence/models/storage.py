"""Storage lot and warehouse receipt tables.

A storage lot is grain the house is holding that belongs to somebody else. The
warehouse licence turns on being able to say, on any day, whose bushels are in
which bin and what they are owed, so these rows are the ones the state
inspector reads.

Withdrawals are their own table rather than a running balance on the lot. The
inspector asks when bushels left, not only how many are left, and a balance
column cannot answer that.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..base import BUSHELS, MONEY, Base
from .reference import TimestampMixin


class StorageLotRow(Base, TimestampMixin):
    """Bushels received into storage on one day for one producer."""

    __tablename__ = "storage_lot"
    __table_args__ = (
        UniqueConstraint("lot_id", name="uq_storage_lot_lot_id"),
        CheckConstraint("bushels > 0", name="bushels_positive"),
        Index("ix_storage_lot_party_code", "party_code", "commodity_code"),
        Index("ix_storage_lot_stored_on", "stored_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    lot_id: Mapped[str] = mapped_column(String(24), nullable=False)
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    commodity_code: Mapped[str] = mapped_column(String(8), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    stored_on: Mapped[date] = mapped_column(Date, nullable=False)
    ticket_id: Mapped[int | None] = mapped_column(ForeignKey("scale_ticket.id"))
    closed_on: Mapped[date | None] = mapped_column(Date)
    billed_through: Mapped[date | None] = mapped_column(Date)
    accrued_to_date: Mapped[Decimal] = mapped_column(
        MONEY, nullable=False, default=Decimal("0.00")
    )
    is_deferred_price: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False
    )

    withdrawals: Mapped[list[WithdrawalRow]] = relationship(
        back_populates="lot",
        cascade="all, delete-orphan",
        lazy="selectin",
    )

    @property
    def withdrawn(self) -> Decimal:
        return sum(
            (Decimal(w.bushels) for w in self.withdrawals), Decimal("0")
        )

    @property
    def open_bushels(self) -> Decimal:
        return Decimal(self.bushels) - self.withdrawn

    def __str__(self) -> str:
        return f"{self.lot_id} {self.party_code} {self.commodity_code}"


class WithdrawalRow(Base, TimestampMixin):
    """Bushels leaving a lot, with the day and the reason."""

    __tablename__ = "storage_withdrawal"
    __table_args__ = (
        CheckConstraint("bushels > 0", name="bushels_positive"),
        Index("ix_storage_withdrawal_withdrawn_on", "withdrawn_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    lot_row_id: Mapped[int] = mapped_column(
        ForeignKey("storage_lot.id", ondelete="CASCADE"), nullable=False
    )
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    withdrawn_on: Mapped[date] = mapped_column(Date, nullable=False)
    reason: Mapped[str] = mapped_column(String(24), nullable=False, default="settled")
    settlement_number: Mapped[str | None] = mapped_column(String(24))

    lot: Mapped[StorageLotRow] = relationship(back_populates="withdrawals")


class WarehouseReceiptRow(Base, TimestampMixin):
    """A negotiable receipt against stored grain.

    Pre-numbered stock like a scale ticket, and the number is the document. A
    receipt is cancelled rather than deleted, and the cancellation keeps the
    number so the sequence the inspector counts stays whole.
    """

    __tablename__ = "warehouse_receipt"
    __table_args__ = (
        UniqueConstraint("receipt_number", name="uq_warehouse_receipt_receipt_number"),
        CheckConstraint("bushels > 0", name="bushels_positive"),
        Index("ix_warehouse_receipt_party_code", "party_code"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    receipt_number: Mapped[str] = mapped_column(String(24), nullable=False)
    lot_row_id: Mapped[int] = mapped_column(ForeignKey("storage_lot.id"), nullable=False)
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    commodity_code: Mapped[str] = mapped_column(String(8), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    grade: Mapped[str] = mapped_column(String(8), nullable=False)
    issued_on: Mapped[date] = mapped_column(Date, nullable=False)
    cancelled_on: Mapped[date | None] = mapped_column(Date)
    lienholder: Mapped[str | None] = mapped_column(String(60))

    @property
    def is_live(self) -> bool:
        return self.cancelled_on is None
