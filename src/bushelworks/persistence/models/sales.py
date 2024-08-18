"""Sale contract and shipment tables.

A shipment is its own row rather than a running total on the sale, for the same
reason a storage withdrawal is: the buyer's settlement arrives weeks later and
is reconciled car by car, so the house has to be able to say which car and on
what day.

Destination weights and grades are nullable. They arrive on the buyer's
settlement, which is often a fortnight behind the truck, and a shipment with no
destination weight yet is the normal state rather than an error.
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

from ..base import Base, BUSHELS, MONEY, PERCENT, POUNDS, PRICE
from .reference import TimestampMixin


class SaleRow(Base, TimestampMixin):
    """One sale out of the house."""

    __tablename__ = "sale_contract"
    __table_args__ = (
        UniqueConstraint("number", name="uq_sale_contract_number"),
        CheckConstraint("bushels > 0", name="bushels_positive"),
        CheckConstraint("shipped >= 0", name="shipped_not_negative"),
        CheckConstraint("window_closes >= window_opens", name="window_ordered"),
        CheckConstraint("freight_per_bushel >= 0", name="freight_not_negative"),
        Index("ix_sale_contract_buyer", "buyer", "status"),
        Index("ix_sale_contract_commodity_code", "commodity_code", "window_closes"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), nullable=False)
    kind: Mapped[str] = mapped_column(String(12), nullable=False)
    buyer: Mapped[str] = mapped_column(String(80), nullable=False)
    destination: Mapped[str] = mapped_column(String(16), nullable=False)
    carrier: Mapped[str] = mapped_column(String(8), nullable=False, default="truck")
    commodity_code: Mapped[str] = mapped_column(String(8), nullable=False)
    origin_code: Mapped[str] = mapped_column(String(12), nullable=False)
    written_on: Mapped[date] = mapped_column(Date, nullable=False)
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    shipped: Mapped[Decimal] = mapped_column(
        BUSHELS, nullable=False, default=Decimal("0")
    )
    window_opens: Mapped[date] = mapped_column(Date, nullable=False)
    window_closes: Mapped[date] = mapped_column(Date, nullable=False)
    futures_symbol: Mapped[str | None] = mapped_column(String(12))
    futures_price: Mapped[Decimal | None] = mapped_column(PRICE)
    basis: Mapped[Decimal | None] = mapped_column(PRICE)
    flat_price: Mapped[Decimal | None] = mapped_column(PRICE)
    tolerance_percent: Mapped[Decimal] = mapped_column(
        PERCENT, nullable=False, default=Decimal("2")
    )
    freight_per_bushel: Mapped[Decimal] = mapped_column(
        PRICE, nullable=False, default=Decimal("0")
    )
    spec_max_moisture: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    spec_min_test_weight: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    spec_max_damage: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    spec_max_foreign: Mapped[Decimal] = mapped_column(PERCENT, nullable=False)
    status: Mapped[str] = mapped_column(String(12), nullable=False, default="open")

    shipments: Mapped[list["ShipmentRow"]] = relationship(
        back_populates="sale",
        cascade="all, delete-orphan",
        lazy="selectin",
    )

    def __str__(self) -> str:
        return f"{self.number} {self.buyer} {self.commodity_code}"


class ShipmentRow(Base, TimestampMixin):
    """One truck or car off a sale."""

    __tablename__ = "shipment"
    __table_args__ = (
        UniqueConstraint("sale_id", "reference", name="uq_shipment_sale_id"),
        CheckConstraint("origin_pounds > 0", name="origin_pounds_positive"),
        Index("ix_shipment_shipped_on", "shipped_on"),
        Index("ix_shipment_carrier_id", "carrier_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    sale_id: Mapped[int] = mapped_column(
        ForeignKey("sale_contract.id", ondelete="CASCADE"), nullable=False
    )
    reference: Mapped[str] = mapped_column(String(24), nullable=False)
    shipped_on: Mapped[date] = mapped_column(Date, nullable=False)
    carrier_id: Mapped[str | None] = mapped_column(String(24))
    origin_pounds: Mapped[Decimal] = mapped_column(POUNDS, nullable=False)
    origin_bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    destination_pounds: Mapped[Decimal | None] = mapped_column(POUNDS)
    destination_bushels: Mapped[Decimal | None] = mapped_column(BUSHELS)
    destination_grade: Mapped[str | None] = mapped_column(String(8))
    settled_on: Mapped[date | None] = mapped_column(Date)
    proceeds: Mapped[Decimal | None] = mapped_column(MONEY)
    rejected: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    rejection_reason: Mapped[str | None] = mapped_column(String(200))

    sale: Mapped[SaleRow] = relationship(back_populates="shipments")

    @property
    def is_settled(self) -> bool:
        return self.settled_on is not None

    @property
    def transit_shrink(self) -> Decimal | None:
        """Bushels that went missing between here and there.

        Nothing until the destination weight arrives. A negative figure means
        the destination weighed heavy, which happens and is not an error.
        """
        if self.destination_bushels is None:
            return None
        return Decimal(self.origin_bushels) - Decimal(self.destination_bushels)
