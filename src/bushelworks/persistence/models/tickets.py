"""Scale ticket tables.

A ticket is append only. There is no update path in the repository and there is
no ``deleted_at``: a mistake is corrected by writing a void and then writing a
replacement, and both rows stay. The ticket number is unique per location rather
than globally, because each scale house runs its own book of pre-numbered stock.

The probe sample is a child table rather than a column per factor. Wheat records
nine factors and sunflowers record three, and a wide table with thirteen mostly
null columns cannot tell "not tested" from "tested at zero", which is a
distinction the grade ladder depends on.
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

from ..base import Base, BUSHELS, PERCENT, POUNDS
from .reference import TimestampMixin


class ScaleTicketRow(Base, TimestampMixin):
    """One load across the scale, exactly as the scale head reported it."""

    __tablename__ = "scale_ticket"
    __table_args__ = (
        UniqueConstraint("location_code", "ticket_number", name="uq_scale_ticket_location_code"),
        CheckConstraint("gross_pounds >= tare_pounds", name="gross_at_least_tare"),
        CheckConstraint("tare_pounds >= 0", name="tare_not_negative"),
        CheckConstraint(
            "dockage_percent >= 0 AND dockage_percent < 100", name="dockage_range"
        ),
        Index("ix_scale_ticket_scale_date", "scale_date"),
        Index("ix_scale_ticket_party_code", "party_code"),
        Index("ix_scale_ticket_commodity_code", "commodity_code", "scale_date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_number: Mapped[str] = mapped_column(String(24), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    scale_date: Mapped[date] = mapped_column(Date, nullable=False)
    commodity_code: Mapped[str] = mapped_column(String(8), nullable=False)
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    gross_pounds: Mapped[Decimal] = mapped_column(POUNDS, nullable=False)
    tare_pounds: Mapped[Decimal] = mapped_column(POUNDS, nullable=False)
    dockage_percent: Mapped[Decimal] = mapped_column(
        PERCENT, nullable=False, default=Decimal("0")
    )
    disposition: Mapped[str] = mapped_column(String(16), nullable=False)
    contract_number: Mapped[str | None] = mapped_column(String(24))
    truck_id: Mapped[str | None] = mapped_column(String(16))
    driver: Mapped[str | None] = mapped_column(String(60))
    farm: Mapped[str | None] = mapped_column(String(60))
    field_name: Mapped[str | None] = mapped_column(String(60))
    remarks: Mapped[str | None] = mapped_column(String(200))
    voided: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    settled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    samples: Mapped[list["SampleRow"]] = relationship(
        back_populates="ticket",
        cascade="all, delete-orphan",
        lazy="selectin",
    )
    splits: Mapped[list["TicketSplitRow"]] = relationship(
        back_populates="ticket",
        cascade="all, delete-orphan",
        lazy="selectin",
    )

    @property
    def net_pounds(self) -> Decimal:
        return Decimal(self.gross_pounds) - Decimal(self.tare_pounds)

    def __str__(self) -> str:
        return f"{self.location_code}/{self.ticket_number}"


class SampleRow(Base):
    """One factor reading off the probe sample."""

    __tablename__ = "ticket_sample"
    __table_args__ = (
        UniqueConstraint("ticket_id", "factor", name="uq_ticket_sample_ticket_id"),
        CheckConstraint("value >= 0", name="value_not_negative"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_id: Mapped[int] = mapped_column(
        ForeignKey("scale_ticket.id", ondelete="CASCADE"), nullable=False
    )
    factor: Mapped[str] = mapped_column(String(24), nullable=False)
    value: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    instrument: Mapped[str | None] = mapped_column(String(24))

    ticket: Mapped[ScaleTicketRow] = relationship(back_populates="samples")


class TicketSplitRow(Base):
    """One party's share of one ticket, copied from the agreement at the window."""

    __tablename__ = "ticket_split"
    __table_args__ = (
        UniqueConstraint("ticket_id", "party_code", name="uq_ticket_split_ticket_id"),
        CheckConstraint("percent > 0 AND percent <= 100", name="percent_range"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_id: Mapped[int] = mapped_column(
        ForeignKey("scale_ticket.id", ondelete="CASCADE"), nullable=False
    )
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    percent: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False, default="owner")
    bushels: Mapped[Decimal | None] = mapped_column(BUSHELS)

    ticket: Mapped[ScaleTicketRow] = relationship(back_populates="splits")
