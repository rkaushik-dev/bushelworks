"""Money for grain settlement.

Two kinds of number show up on a settlement statement and they round in
different places. A *price* is dollars per bushel carried to four decimals,
because a basis of minus twenty-two and a half cents is a real quote. An
*amount* is dollars and cents, and it is the only thing that ever reaches a
cheque. Multiplying a four-decimal price by a four-decimal bushel count and
rounding once at the end is the whole reason this module exists: rounding in
the middle drifts, and the drift lands on the producer.
"""

from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP, localcontext
from typing import Final, Iterable, Sequence

PRICE_PLACES: Final = Decimal("0.0001")
AMOUNT_PLACES: Final = Decimal("0.01")

ZERO_AMOUNT: Final = Decimal("0.00")
ZERO_PRICE: Final = Decimal("0.0000")


class MoneyError(ValueError):
    """Raised for a money value that cannot be represented or allocated."""


def _q(value: Decimal, places: Decimal) -> Decimal:
    with localcontext() as ctx:
        ctx.prec = 34
        return Decimal(value).quantize(places, rounding=ROUND_HALF_UP)


def price(value: Decimal | str | int) -> Decimal:
    """A dollars-per-unit price at four decimals. Negative is legal: basis."""
    return _q(Decimal(value), PRICE_PLACES)


def amount(value: Decimal | str | int) -> Decimal:
    """A dollars-and-cents amount. Negative is legal: a deduction."""
    return _q(Decimal(value), AMOUNT_PLACES)


def extend(quantity: Decimal, unit_price: Decimal) -> Decimal:
    """Quantity times price, rounded to cents exactly once."""
    with localcontext() as ctx:
        ctx.prec = 34
        raw = Decimal(quantity) * Decimal(unit_price)
    return _q(raw, AMOUNT_PLACES)


def total(amounts: Iterable[Decimal]) -> Decimal:
    """Sum a run of already-rounded amounts without re-rounding each one."""
    acc = Decimal("0")
    for a in amounts:
        acc += Decimal(a)
    return _q(acc, AMOUNT_PLACES)


def allocate(value: Decimal, weights: Sequence[Decimal]) -> list[Decimal]:
    """Split an amount across weights so the parts add back to the whole.

    Largest-remainder. A two way fifty-fifty split of a settlement ending in an
    odd cent has to give that cent to somebody, and doing it consistently is the
    difference between a ledger that balances and one that is off by a penny
    every third ticket. Ties go to the earlier share, which by convention is the
    landlord on a crop share lease.
    """
    if not weights:
        raise MoneyError("cannot allocate across an empty set of weights")
    weight_sum = sum(Decimal(w) for w in weights)
    if weight_sum <= 0:
        raise MoneyError(f"allocation weights must sum to more than zero: {weight_sum}")

    target = _q(Decimal(value), AMOUNT_PLACES)
    cents = int((target * 100).to_integral_value(rounding=ROUND_HALF_UP))
    sign = -1 if cents < 0 else 1
    cents = abs(cents)

    raw = [Decimal(cents) * Decimal(w) / weight_sum for w in weights]
    floors = [int(r.to_integral_value(rounding="ROUND_FLOOR")) for r in raw]
    remainder = cents - sum(floors)

    order = sorted(
        range(len(weights)),
        key=lambda i: (-(raw[i] - floors[i]), i),
    )
    for k in range(remainder):
        floors[order[k % len(order)]] += 1

    return [Decimal(sign * c) / Decimal(100) for c in floors]


def negate(value: Decimal) -> Decimal:
    return _q(-Decimal(value), AMOUNT_PLACES)


def format_amount(value: Decimal) -> str:
    """Render an amount the way the statement printer wants it."""
    v = _q(Decimal(value), AMOUNT_PLACES)
    if v < 0:
        return f"({-v:,.2f})"
    return f"{v:,.2f}"


def format_price(value: Decimal) -> str:
    return f"{_q(Decimal(value), PRICE_PLACES):.4f}"
