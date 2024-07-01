"""Bids, basis and the futures months a cash price is built out of.

A country cash bid is not a number somebody types. It is a futures price for a
delivery month plus a basis, and the two move independently. The board shows the
sum, the contract file keeps the parts, and a basis contract prices the parts on
different days on purpose.

Futures months are the CME grain codes. They are ordered by year then by the
month letter's position in the delivery cycle, never alphabetically, which is
the mistake that puts December before July.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..money import PRICE_PLACES, price
from ..units import quantize

MONTH_CODES: Mapping[str, int] = {
    "F": 1,
    "G": 2,
    "H": 3,
    "J": 4,
    "K": 5,
    "M": 6,
    "N": 7,
    "Q": 8,
    "U": 9,
    "V": 10,
    "X": 11,
    "Z": 12,
}

DELIVERY_MONTHS: Mapping[str, tuple[str, ...]] = {
    "CORN": ("H", "K", "N", "U", "Z"),
    "SOYB": ("F", "H", "K", "N", "Q", "U", "X"),
    "HRW": ("H", "K", "N", "U", "Z"),
    "MILO": ("H", "K", "N", "U", "Z"),
}


class PricingError(ValidationError):
    """Raised when a price cannot be assembled from what is on file."""


@dataclass(frozen=True, slots=True)
class FuturesMonth:
    """A contract month such as CZ4, written here as a code and a year."""

    commodity_code: str
    month_code: str
    year: int

    def __post_init__(self) -> None:
        if self.month_code not in MONTH_CODES:
            raise PricingError(f"{self.month_code} is not a delivery month code")
        allowed = DELIVERY_MONTHS.get(self.commodity_code)
        if allowed is not None and self.month_code not in allowed:
            raise PricingError(
                f"{self.commodity_code} does not trade a {self.month_code} contract"
            )
        if self.year < 2000 or self.year > 2100:
            raise PricingError(f"implausible contract year {self.year}")

    @property
    def month_number(self) -> int:
        return MONTH_CODES[self.month_code]

    @property
    def sort_key(self) -> tuple[int, int]:
        return (self.year, self.month_number)

    @property
    def symbol(self) -> str:
        return f"{self.commodity_code}{self.month_code}{self.year % 100:02d}"

    def first_notice(self) -> date:
        """The last business use of the month, approximated at the 1st.

        The exchange calendar lives elsewhere. What this package needs is an
        ordering and a rough cut off for rolling a hedge to arrive contract, and
        the first of the delivery month is the convention the office uses.
        """
        return date(self.year, self.month_number, 1)

    def is_before(self, other: FuturesMonth) -> bool:
        return self.sort_key < other.sort_key


def next_month(month: FuturesMonth) -> FuturesMonth:
    """The next tradable month for the same commodity."""
    cycle = DELIVERY_MONTHS.get(month.commodity_code)
    if not cycle:
        raise PricingError(f"no delivery cycle known for {month.commodity_code}")
    index = cycle.index(month.month_code)
    if index + 1 < len(cycle):
        return FuturesMonth(month.commodity_code, cycle[index + 1], month.year)
    return FuturesMonth(month.commodity_code, cycle[0], month.year + 1)


def order_months(months: Iterable[FuturesMonth]) -> list[FuturesMonth]:
    return sorted(months, key=lambda m: m.sort_key)


@dataclass(frozen=True, slots=True)
class Basis:
    """A basis quote against a named month, in dollars per bushel."""

    month: FuturesMonth
    value: Decimal
    quoted_on: date
    location_code: str = "*"

    def __post_init__(self) -> None:
        if abs(self.value) > Decimal("5"):
            raise PricingError(f"basis of {self.value} is out of any plausible range")


@dataclass(frozen=True, slots=True)
class CashBid:
    """What the board shows: futures plus basis for one delivery slot."""

    commodity_code: str
    month: FuturesMonth
    futures: Decimal
    basis: Decimal
    quoted_on: date
    location_code: str = "*"

    def __post_init__(self) -> None:
        if self.futures <= 0:
            raise PricingError("a futures price has to be positive")
        if self.month.commodity_code != self.commodity_code:
            raise PricingError(
                f"{self.month.symbol} is not a {self.commodity_code} contract"
            )

    @property
    def cash(self) -> Decimal:
        """The posted cash price, floored at zero.

        A basis wide enough to drive the cash bid below zero happens in a
        harvest pile up. The board shows zero and the desk stops buying rather
        than printing a negative bid.
        """
        raw = Decimal(self.futures) + Decimal(self.basis)
        return price(raw if raw > 0 else Decimal("0"))

    def with_basis(self, basis: Decimal) -> CashBid:
        return CashBid(
            commodity_code=self.commodity_code,
            month=self.month,
            futures=self.futures,
            basis=price(basis),
            quoted_on=self.quoted_on,
            location_code=self.location_code,
        )


@dataclass(frozen=True, slots=True)
class PricedQuantity:
    """Bushels at a price, which is what every settlement line reduces to."""

    bushels: Decimal
    unit_price: Decimal

    def __post_init__(self) -> None:
        if self.bushels < 0:
            raise PricingError("cannot price a negative quantity")

    @property
    def value(self) -> Decimal:
        from ..money import extend

        return extend(self.bushels, self.unit_price)


def blend(quantities: Iterable[PricedQuantity]) -> Decimal:
    """The weighted average price of several priced lots.

    Used when a producer prices half a bin in March and the rest in July and
    then asks what he averaged.
    """
    lots = list(quantities)
    total_bushels = sum((q.bushels for q in lots), Decimal("0"))
    if total_bushels <= 0:
        raise PricingError("cannot blend a price across no bushels")
    weighted = sum((q.bushels * q.unit_price for q in lots), Decimal("0"))
    return quantize(weighted / total_bushels, PRICE_PLACES)
