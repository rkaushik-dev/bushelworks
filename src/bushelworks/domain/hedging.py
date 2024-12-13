"""The futures account, as far as the elevator needs to model it.

The house does not trade. It sells futures against grain it owns and buys them
back when the grain is sold, and the only questions it asks of the account are:

* how many contracts are on, by month
* what the position is worth against today's settlement price
* what it would cost to roll a month forward

Nothing here places an order. Orders go to the broker on the telephone and the
fills come back on a statement, which is what gets keyed in.

Contracts are whole. There is no such thing as three tenths of a corn contract,
and rounding a hedge to fractions is how a house ends up with a position it
cannot explain.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ConflictError, ValidationError
from ..money import PRICE_PLACES, amount, price
from ..units import quantize
from .pricing import FuturesMonth, next_month

CONTRACT_BUSHELS = Decimal("5000")


class Side(str, Enum):
    """Which way a lot is on. Short is the elevator's normal state."""

    SHORT = "short"
    LONG = "long"


class HedgeError(ValidationError):
    """Raised when the futures account cannot do what is asked."""

    code = "hedge_error"


@dataclass(frozen=True, slots=True)
class Lot:
    """One fill off the broker's statement."""

    reference: str
    month: FuturesMonth
    side: Side
    contracts: int
    price: Decimal
    filled_on: date

    def __post_init__(self) -> None:
        if self.contracts <= 0:
            raise HedgeError(f"{self.reference}: a fill needs contracts")
        if self.price <= 0:
            raise HedgeError(f"{self.reference}: a fill needs a price")

    @property
    def bushels(self) -> Decimal:
        return Decimal(self.contracts) * CONTRACT_BUSHELS

    @property
    def signed_contracts(self) -> int:
        return -self.contracts if self.side is Side.SHORT else self.contracts


@dataclass
class Account:
    """Everything on with the broker."""

    lots: list[Lot] = field(default_factory=list)

    def add(self, lot: Lot) -> None:
        if any(item.reference == lot.reference for item in self.lots):
            raise ConflictError(f"{lot.reference} is already on the account")
        self.lots.append(lot)

    def by_month(self) -> dict[FuturesMonth, int]:
        """Net contracts on, per month, dropping the months that flattened."""
        totals: dict[FuturesMonth, int] = {}
        for lot in self.lots:
            totals[lot.month] = totals.get(lot.month, 0) + lot.signed_contracts
        return {month: net for month, net in totals.items() if net != 0}

    def net_contracts(self) -> int:
        return sum(lot.signed_contracts for lot in self.lots)

    def net_bushels(self) -> Decimal:
        return Decimal(self.net_contracts()) * CONTRACT_BUSHELS

    @property
    def is_short(self) -> bool:
        return self.net_contracts() < 0

    def average_price(self, month: FuturesMonth) -> Decimal:
        """The weighted average fill on one month, for the position sheet."""
        lots = [lot for lot in self.lots if lot.month == month]
        if not lots:
            raise HedgeError(f"nothing on in {month.symbol}")
        contracts = sum(lot.contracts for lot in lots)
        weighted = sum(
            (lot.price * Decimal(lot.contracts) for lot in lots), Decimal("0")
        )
        return quantize(weighted / Decimal(contracts), PRICE_PLACES)

    def open_value(self, settlements: dict[FuturesMonth, Decimal]) -> Decimal:
        """Gain or loss against today's settlement prices.

        A short gains when the market falls, which is the point: the loss on the
        grain in the bin is what it is offsetting.
        """
        value = Decimal("0")
        for month, net in self.by_month().items():
            settle = settlements.get(month)
            if settle is None:
                raise HedgeError(f"no settlement price for {month.symbol}")
            average = self.average_price(month)
            move = Decimal(settle) - average
            value += move * Decimal(net) * CONTRACT_BUSHELS
        return amount(value)


def contracts_for(bushels: Decimal) -> int:
    """How many whole contracts a bushel position needs, rounded toward zero."""
    lots = Decimal(bushels) / CONTRACT_BUSHELS
    return int(lots.to_integral_value(rounding="ROUND_DOWN"))


def hedge_gap(net_long_bushels: Decimal, account: Account) -> int:
    """Contracts the desk is short of being flat.

    Positive means more should be sold. This is the number the manager reads
    before he telephones the broker, and it is deliberately in contracts rather
    than bushels because that is what he will say down the telephone.
    """
    wanted = -contracts_for(net_long_bushels)
    return wanted - account.net_contracts()


def roll_cost(
    account: Account,
    month: FuturesMonth,
    near_price: Decimal,
    far_price: Decimal,
) -> Decimal:
    """What it costs to move a month's position to the next one.

    The spread times the bushels. A carry market costs money to roll and an
    inverse pays, and which one it is changes weekly.
    """
    net = account.by_month().get(month)
    if net is None:
        raise HedgeError(f"nothing on in {month.symbol} to roll")
    spread = price(far_price) - price(near_price)
    return amount(spread * Decimal(net) * CONTRACT_BUSHELS)


def roll(
    account: Account,
    month: FuturesMonth,
    near_price: Decimal,
    far_price: Decimal,
    on: date,
    reference_prefix: str = "ROLL",
) -> list[Lot]:
    """Close a month and open the same position in the next one."""
    net = account.by_month().get(month)
    if net is None:
        raise HedgeError(f"nothing on in {month.symbol} to roll")

    closing = Lot(
        reference=f"{reference_prefix}-{month.symbol}-C",
        month=month,
        side=Side.LONG if net < 0 else Side.SHORT,
        contracts=abs(net),
        price=price(near_price),
        filled_on=on,
    )
    far = next_month(month)
    opening = Lot(
        reference=f"{reference_prefix}-{far.symbol}-O",
        month=far,
        side=Side.SHORT if net < 0 else Side.LONG,
        contracts=abs(net),
        price=price(far_price),
        filled_on=on,
    )
    account.add(closing)
    account.add(opening)
    return [closing, opening]


def months_on(account: Account) -> Sequence[FuturesMonth]:
    return sorted(account.by_month(), key=lambda month: month.sort_key)
