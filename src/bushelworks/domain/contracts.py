"""Purchase contracts between the house and a producer.

Five shapes cover almost everything a country elevator writes, and they differ
in which half of the price is fixed and when:

* ``CASH`` fixes both halves today and delivers today.
* ``FORWARD`` fixes both halves today and delivers in a window.
* ``HTA`` fixes the futures half now and leaves the basis to be set later.
* ``BASIS`` fixes the basis half now and leaves the futures to be set later.
* ``DEFERRED`` fixes neither and charges a service fee for the privilege.

A contract is not priced until both halves are set. Everything downstream keys
off :meth:`PurchaseContract.is_fully_priced`, because an unpriced contract can
take delivery but cannot pay for it.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date
from decimal import Decimal
from enum import Enum

from ..money import PRICE_PLACES, price
from ..units import quantize
from .pricing import FuturesMonth, PricingError


class ContractKind(str, Enum):
    CASH = "cash"
    FORWARD = "forward"
    HTA = "hta"
    BASIS = "basis"
    DEFERRED = "deferred"


class ContractStatus(str, Enum):
    OPEN = "open"
    FILLED = "filled"
    CLOSED = "closed"
    CANCELLED = "cancelled"


class ContractError(ValueError):
    """Raised when a contract cannot stand as written or as amended."""


@dataclass(frozen=True, slots=True)
class DeliveryWindow:
    """The span the producer may deliver against."""

    opens: date
    closes: date

    def __post_init__(self) -> None:
        if self.closes < self.opens:
            raise ContractError("a delivery window has to close after it opens")

    def contains(self, day: date) -> bool:
        return self.opens <= day <= self.closes

    @property
    def days(self) -> int:
        return (self.closes - self.opens).days + 1


@dataclass(frozen=True, slots=True)
class PurchaseContract:
    """One contract, with whichever halves of its price are set so far."""

    number: str
    kind: ContractKind
    party_id: str
    commodity_code: str
    location_code: str
    written_on: date
    bushels: Decimal
    window: DeliveryWindow
    futures_month: FuturesMonth | None = None
    futures_price: Decimal | None = None
    basis: Decimal | None = None
    flat_price: Decimal | None = None
    tolerance_percent: Decimal = Decimal("2")
    service_rate_per_bushel: Decimal = Decimal("0")
    status: ContractStatus = ContractStatus.OPEN

    def __post_init__(self) -> None:
        if not self.number:
            raise ContractError("a contract needs a number")
        if self.bushels <= 0:
            raise ContractError(f"{self.number}: contracted bushels must be positive")
        if self.tolerance_percent < 0 or self.tolerance_percent > Decimal("25"):
            raise ContractError(f"{self.number}: tolerance out of range")
        if not self.window.contains(self.window.opens):
            raise ContractError(f"{self.number}: empty delivery window")

        if self.kind in (ContractKind.CASH, ContractKind.FORWARD):
            if self.flat_price is None:
                raise ContractError(f"{self.number}: a {self.kind.value} needs a price")
            if self.flat_price <= 0:
                raise ContractError(f"{self.number}: a flat price has to be positive")
        if self.kind is ContractKind.HTA:
            if self.futures_month is None or self.futures_price is None:
                raise ContractError(
                    f"{self.number}: a hedge to arrive needs a month and a futures price"
                )
        if self.kind is ContractKind.BASIS and self.basis is None:
            raise ContractError(f"{self.number}: a basis contract needs a basis")
        if self.kind is ContractKind.DEFERRED and self.flat_price is not None:
            raise ContractError(
                f"{self.number}: a deferred price contract cannot carry a price"
            )
        if self.futures_month is not None:
            if self.futures_month.commodity_code != self.commodity_code:
                raise ContractError(
                    f"{self.number}: {self.futures_month.symbol} is the wrong commodity"
                )

    @property
    def is_fully_priced(self) -> bool:
        if self.flat_price is not None:
            return True
        return self.futures_price is not None and self.basis is not None

    @property
    def unit_price(self) -> Decimal:
        """The contract price per bushel, or refuse to guess."""
        if self.flat_price is not None:
            return price(self.flat_price)
        if self.futures_price is None or self.basis is None:
            raise ContractError(f"{self.number}: contract is not fully priced yet")
        return quantize(
            Decimal(self.futures_price) + Decimal(self.basis), PRICE_PLACES
        )

    @property
    def minimum_bushels(self) -> Decimal:
        floor = self.bushels * (Decimal("100") - self.tolerance_percent) / Decimal("100")
        return quantize(floor, Decimal("0.0001"))

    @property
    def maximum_bushels(self) -> Decimal:
        ceiling = self.bushels * (Decimal("100") + self.tolerance_percent) / Decimal("100")
        return quantize(ceiling, Decimal("0.0001"))

    def accepts_delivery_on(self, day: date) -> bool:
        return self.status is ContractStatus.OPEN and self.window.contains(day)

    def set_basis(self, basis: Decimal, on: date) -> "PurchaseContract":
        """Fix the basis half of an HTA."""
        if self.kind is not ContractKind.HTA:
            raise ContractError(f"{self.number}: only a hedge to arrive sets a basis")
        if self.basis is not None:
            raise ContractError(f"{self.number}: basis is already set")
        if on > self.window.closes:
            raise ContractError(f"{self.number}: basis cannot be set after the window")
        return replace(self, basis=price(basis))

    def set_futures(self, futures: Decimal, on: date) -> "PurchaseContract":
        """Fix the futures half of a basis contract."""
        if self.kind is not ContractKind.BASIS:
            raise ContractError(f"{self.number}: only a basis contract sets futures")
        if self.futures_price is not None:
            raise ContractError(f"{self.number}: futures are already set")
        if futures <= 0:
            raise PricingError(f"{self.number}: futures price has to be positive")
        if on > self.window.closes:
            raise ContractError(f"{self.number}: futures cannot be set after the window")
        return replace(self, futures_price=price(futures))

    def roll_to(self, month: FuturesMonth, spread: Decimal) -> "PurchaseContract":
        """Move an unpriced futures half to a later month, paying the spread.

        Rolling is only legal forward. A producer who wants to roll back is
        asking the house to carry his position for him, and the house does not.
        """
        if self.futures_month is None or self.futures_price is None:
            raise ContractError(f"{self.number}: nothing to roll")
        if not self.futures_month.is_before(month):
            raise ContractError(
                f"{self.number}: cannot roll back from "
                f"{self.futures_month.symbol} to {month.symbol}"
            )
        return replace(
            self,
            futures_month=month,
            futures_price=quantize(
                Decimal(self.futures_price) + Decimal(spread), PRICE_PLACES
            ),
        )

    def cancel(self) -> "PurchaseContract":
        if self.status is not ContractStatus.OPEN:
            raise ContractError(f"{self.number}: only an open contract can be cancelled")
        return replace(self, status=ContractStatus.CANCELLED)

    def service_charge(self, bushels: Decimal, days: int) -> Decimal:
        """The deferred price service fee, charged per bushel per month started."""
        if self.service_rate_per_bushel == 0 or days <= 0:
            return Decimal("0.00")
        months = (Decimal(days) / Decimal("30")).to_integral_value(rounding="ROUND_CEILING")
        from ..money import extend

        return extend(Decimal(bushels), self.service_rate_per_bushel * months)
