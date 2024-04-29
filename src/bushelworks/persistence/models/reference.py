"""Tables for the reference data every ticket points at.

Producers, elevators and the commodity master change slowly and are referred to
by code rather than by surrogate key, because those codes are printed on the
ticket and quoted on the telephone. The surrogate keys exist anyway so a code
can be corrected without rewriting every child row.
"""

from __future__ import annotations

from datetime import date, datetime, timezone

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
from sqlalchemy.orm import Mapped, mapped_column

from ..base import Base, PERCENT, PRICE


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class TimestampMixin:
    """Every row records when it was written and when it last moved."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False
    )


class Party(Base, TimestampMixin):
    """A producer, a landlord, a lienholder or a buyer.

    One table rather than four. A landlord on one ticket is a producer on the
    next, and splitting them means keeping two addresses for the same person.
    """

    __tablename__ = "party"
    __table_args__ = (
        UniqueConstraint("code", name="uq_party_code"),
        CheckConstraint("length(code) > 0", name="code_not_blank"),
        Index("ix_party_sort_name", "sort_name"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(24), nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    sort_name: Mapped[str] = mapped_column(String(120), nullable=False)
    tax_id: Mapped[str | None] = mapped_column(String(24))
    state_code: Mapped[str] = mapped_column(String(2), nullable=False, default="KS")
    address_line1: Mapped[str | None] = mapped_column(String(80))
    address_line2: Mapped[str | None] = mapped_column(String(80))
    city: Mapped[str | None] = mapped_column(String(60))
    postal_code: Mapped[str | None] = mapped_column(String(12))
    phone: Mapped[str | None] = mapped_column(String(24))
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    checkoff_exempt: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    opened_on: Mapped[date | None] = mapped_column(Date)
    closed_on: Mapped[date | None] = mapped_column(Date)

    def __str__(self) -> str:
        return f"{self.code} {self.name}"


class Location(Base, TimestampMixin):
    """One elevator. The house may have several and they file separately."""

    __tablename__ = "location"
    __table_args__ = (
        UniqueConstraint("code", name="uq_location_code"),
        CheckConstraint("length(code) > 0", name="code_not_blank"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(12), nullable=False)
    name: Mapped[str] = mapped_column(String(80), nullable=False)
    state_code: Mapped[str] = mapped_column(String(2), nullable=False)
    warehouse_licence: Mapped[str | None] = mapped_column(String(24))
    licensed_capacity: Mapped[int | None] = mapped_column(Integer)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    def __str__(self) -> str:
        return f"{self.code} {self.name}"


class CommodityRow(Base, TimestampMixin):
    """The commodity master as filed, so a new crop needs no deploy."""

    __tablename__ = "commodity"
    __table_args__ = (
        UniqueConstraint("code", name="uq_commodity_code"),
        CheckConstraint("standard_test_weight > 0", name="test_weight_positive"),
        CheckConstraint(
            "base_moisture > 0 AND base_moisture < 100", name="base_moisture_range"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(8), nullable=False)
    name: Mapped[str] = mapped_column(String(60), nullable=False)
    standard_test_weight: Mapped[float] = mapped_column(PRICE, nullable=False)
    base_moisture: Mapped[float] = mapped_column(PERCENT, nullable=False)
    price_unit: Mapped[str] = mapped_column(String(4), nullable=False, default="BU")
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    def __str__(self) -> str:
        return f"{self.code} {self.name}"


class SplitAgreement(Base, TimestampMixin):
    """A standing split between a landlord and a tenant on one farm.

    The scale house does not want to ask for percentages at the window during
    harvest. The agreement is on file, the ticket names the farm, and the split
    is copied onto the ticket at the time so a later change to the agreement
    cannot move money on a settled load.
    """

    __tablename__ = "split_agreement"
    __table_args__ = (
        UniqueConstraint(
            "farm_code", "party_id", "effective_from", name="uq_split_agreement_farm_code"
        ),
        CheckConstraint("percent > 0 AND percent <= 100", name="percent_range"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    farm_code: Mapped[str] = mapped_column(String(24), nullable=False)
    party_id: Mapped[int] = mapped_column(ForeignKey("party.id"), nullable=False)
    percent: Mapped[float] = mapped_column(PERCENT, nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False, default="owner")
    effective_from: Mapped[date] = mapped_column(Date, nullable=False)
    effective_to: Mapped[date | None] = mapped_column(Date)
