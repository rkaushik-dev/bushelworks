"""Bin and bin reading tables.

A bin row is where grain physically is. The contents are a running blend and the
sample columns hold that blend, which is what a draw out of the bin will test.

Readings are separate rows because the aeration log is a compliance record: the
state asks what the temperature was doing in the week before a load went out,
and a single "last reading" column cannot answer that.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ..base import BUSHELS, PERCENT, Base
from .reference import TimestampMixin


class BinRow(Base, TimestampMixin):
    """One bin at one site, with what is in it."""

    __tablename__ = "bin"
    __table_args__ = (
        UniqueConstraint("location_code", "bin_id", name="uq_bin_location_code"),
        CheckConstraint("capacity_bushels > 0", name="capacity_positive"),
        CheckConstraint("bushels >= 0", name="bushels_not_negative"),
        CheckConstraint("bushels <= capacity_bushels", name="bushels_fit"),
        Index("ix_bin_commodity_code", "commodity_code"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    bin_id: Mapped[str] = mapped_column(String(12), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    capacity_bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    bushels: Mapped[Decimal] = mapped_column(
        BUSHELS, nullable=False, default=Decimal("0")
    )
    commodity_code: Mapped[str | None] = mapped_column(String(8))
    moisture: Mapped[Decimal | None] = mapped_column(PERCENT)
    test_weight: Mapped[Decimal | None] = mapped_column(PERCENT)
    damage: Mapped[Decimal | None] = mapped_column(PERCENT)
    is_aerated: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    out_of_service: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    out_of_service_reason: Mapped[str | None] = mapped_column(String(120))
    last_turned_on: Mapped[date | None] = mapped_column(Date)

    readings: Mapped[list[BinReadingRow]] = relationship(
        back_populates="bin",
        cascade="all, delete-orphan",
        lazy="selectin",
        order_by="BinReadingRow.taken_at",
    )

    @property
    def is_empty(self) -> bool:
        return Decimal(self.bushels) == 0

    @property
    def headroom(self) -> Decimal:
        return Decimal(self.capacity_bushels) - Decimal(self.bushels)

    def __str__(self) -> str:
        return f"{self.location_code}/{self.bin_id}"


class BinReadingRow(Base):
    """One temperature and moisture reading off a bin's cables."""

    __tablename__ = "bin_reading"
    __table_args__ = (
        CheckConstraint(
            "temperature_c > -60 AND temperature_c < 90", name="temperature_range"
        ),
        Index("ix_bin_reading_taken_at", "taken_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    bin_row_id: Mapped[int] = mapped_column(
        ForeignKey("bin.id", ondelete="CASCADE"), nullable=False
    )
    taken_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    cable: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    temperature_c: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    moisture: Mapped[Decimal | None] = mapped_column(PERCENT)
    fan_running: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    note: Mapped[str | None] = mapped_column(String(120))

    bin: Mapped[BinRow] = relationship(back_populates="readings")

    @property
    def is_warm(self) -> bool:
        """Above this the grain is working and somebody has to look at it."""
        return Decimal(self.temperature_c) > Decimal("21")
