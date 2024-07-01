"""Contract, application and settlement tables.

Three rules are enforced in the schema rather than only in the service layer,
because all three have been broken by hand in a console at some point:

* an application cannot exist without both a ticket and a contract
* a settlement line stores a positive magnitude and a kind, never a signed
  amount, so a sign convention cannot drift between the writer and the reader
* a settlement number is unique across the house, unlike a ticket number, since
  the cheque run keys on it
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

from ..base import BUSHELS, MONEY, PERCENT, PRICE, Base
from .reference import TimestampMixin


class ContractRow(Base, TimestampMixin):
    """One purchase contract with whichever halves of its price are set."""

    __tablename__ = "purchase_contract"
    __table_args__ = (
        UniqueConstraint("number", name="uq_purchase_contract_number"),
        CheckConstraint("bushels > 0", name="bushels_positive"),
        CheckConstraint("window_closes >= window_opens", name="window_ordered"),
        CheckConstraint(
            "tolerance_percent >= 0 AND tolerance_percent <= 25", name="tolerance_range"
        ),
        Index("ix_purchase_contract_party_code", "party_code", "status"),
        Index("ix_purchase_contract_window_closes", "window_closes"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), nullable=False)
    kind: Mapped[str] = mapped_column(String(12), nullable=False)
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    commodity_code: Mapped[str] = mapped_column(String(8), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    written_on: Mapped[date] = mapped_column(Date, nullable=False)
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    window_opens: Mapped[date] = mapped_column(Date, nullable=False)
    window_closes: Mapped[date] = mapped_column(Date, nullable=False)
    futures_symbol: Mapped[str | None] = mapped_column(String(12))
    futures_price: Mapped[Decimal | None] = mapped_column(PRICE)
    basis: Mapped[Decimal | None] = mapped_column(PRICE)
    flat_price: Mapped[Decimal | None] = mapped_column(PRICE)
    tolerance_percent: Mapped[Decimal] = mapped_column(
        PERCENT, nullable=False, default=Decimal("2")
    )
    service_rate_per_bushel: Mapped[Decimal] = mapped_column(
        PRICE, nullable=False, default=Decimal("0")
    )
    status: Mapped[str] = mapped_column(String(12), nullable=False, default="open")

    applications: Mapped[list[ApplicationRow]] = relationship(
        back_populates="contract", lazy="selectin"
    )

    def __str__(self) -> str:
        return f"{self.number} {self.commodity_code} {self.bushels}"


class ApplicationRow(Base, TimestampMixin):
    """Bushels off one ticket landing on one contract."""

    __tablename__ = "contract_application"
    __table_args__ = (
        CheckConstraint("bushels > 0", name="bushels_positive"),
        UniqueConstraint(
            "ticket_id", "contract_id", name="uq_contract_application_ticket_id"
        ),
        Index("ix_contract_application_applied_on", "applied_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    ticket_id: Mapped[int] = mapped_column(ForeignKey("scale_ticket.id"), nullable=False)
    contract_id: Mapped[int] = mapped_column(
        ForeignKey("purchase_contract.id"), nullable=False
    )
    bushels: Mapped[Decimal] = mapped_column(BUSHELS, nullable=False)
    applied_on: Mapped[date] = mapped_column(Date, nullable=False)
    reversed_on: Mapped[date | None] = mapped_column(Date)

    contract: Mapped[ContractRow] = relationship(back_populates="applications")


class SettlementRow(Base, TimestampMixin):
    """A finished statement, with its lines."""

    __tablename__ = "settlement"
    __table_args__ = (
        UniqueConstraint("number", name="uq_settlement_number"),
        Index("ix_settlement_party_code", "party_code", "settled_on"),
        Index("ix_settlement_settled_on", "settled_on"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    number: Mapped[str] = mapped_column(String(24), nullable=False)
    settled_on: Mapped[date] = mapped_column(Date, nullable=False)
    party_code: Mapped[str] = mapped_column(String(24), nullable=False)
    location_code: Mapped[str] = mapped_column(String(12), nullable=False)
    gross: Mapped[Decimal] = mapped_column(MONEY, nullable=False)
    deductions: Mapped[Decimal] = mapped_column(MONEY, nullable=False)
    net: Mapped[Decimal] = mapped_column(MONEY, nullable=False)
    paid_on: Mapped[date | None] = mapped_column(Date)
    cheque_number: Mapped[str | None] = mapped_column(String(16))
    voided: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    lines: Mapped[list[SettlementLineRow]] = relationship(
        back_populates="settlement",
        cascade="all, delete-orphan",
        lazy="selectin",
        order_by="SettlementLineRow.sequence",
    )

    def __str__(self) -> str:
        return f"{self.number} {self.party_code} {self.net}"


class SettlementLineRow(Base):
    """One printed row, stored as a positive magnitude plus a kind."""

    __tablename__ = "settlement_line"
    __table_args__ = (
        CheckConstraint("dollars >= 0", name="dollars_not_negative"),
        UniqueConstraint("settlement_id", "sequence", name="uq_settlement_line_settlement_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    settlement_id: Mapped[int] = mapped_column(
        ForeignKey("settlement.id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(nullable=False)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    code: Mapped[str] = mapped_column(String(24), nullable=False)
    description: Mapped[str] = mapped_column(String(120), nullable=False)
    dollars: Mapped[Decimal] = mapped_column(MONEY, nullable=False)
    quantity: Mapped[Decimal | None] = mapped_column(BUSHELS)
    rate: Mapped[Decimal | None] = mapped_column(PRICE)

    settlement: Mapped[SettlementRow] = relationship(back_populates="lines")


class SettlementTicketRow(Base):
    """Which tickets a settlement covers."""

    __tablename__ = "settlement_ticket"
    __table_args__ = (
        UniqueConstraint(
            "settlement_id", "ticket_id", name="uq_settlement_ticket_settlement_id"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    settlement_id: Mapped[int] = mapped_column(
        ForeignKey("settlement.id", ondelete="CASCADE"), nullable=False
    )
    ticket_id: Mapped[int] = mapped_column(ForeignKey("scale_ticket.id"), nullable=False)
