"""Weight and volume conversions used everywhere in the scale house.

Grain moves through a country elevator as pounds on a truck scale and leaves as
bushels on a settlement statement. The conversion is not a physical constant: a
bushel is defined per commodity as a number of pounds of test weight, and the
industry rounds it in a specific way at a specific place. Getting the rounding
wrong by one ten-thousandth of a bushel is enough to make a producer's cheque
disagree with the elevator's ledger, so all of it happens here and nowhere else.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal, localcontext
from typing import Final

from .errors import ValidationError

# Pounds. Truck scales in the US report whole pounds, and every weight that
# enters this package has already been rounded to one by the scale head.
POUND_PLACES: Final = Decimal("1")

# Bushels carry four decimal places on a scale ticket. Five would survive the
# arithmetic but no printed ticket has ever shown one.
BUSHEL_PLACES: Final = Decimal("0.0001")

# Hundredweight, used for sunflowers and for anything sold by the ton.
CWT_PLACES: Final = Decimal("0.01")

POUNDS_PER_CWT: Final = Decimal("100")
POUNDS_PER_TON: Final = Decimal("2000")


class UnitError(ValidationError):
    """Raised when a conversion is asked for something it cannot express."""


def quantize(value: Decimal, places: Decimal) -> Decimal:
    """Round half up at a stated precision, with headroom for the intermediate.

    Every rounding in the package goes through here so there is exactly one
    answer to "which way does a half go", and it is up.
    """
    with localcontext() as ctx:
        ctx.prec = 28
        return Decimal(value).quantize(places, rounding=ROUND_HALF_UP)


def to_pounds(value: Decimal | int | str) -> Decimal:
    """Coerce a weight to whole pounds.

    Anything that reaches the settlement path has already been through a scale
    head, so a fractional pound means somebody typed it. We round rather than
    reject because the alternative is a stuck ticket at four in the morning.
    """
    weight = Decimal(value)
    if weight < 0:
        raise UnitError(f"weight cannot be negative: {weight}")
    return quantize(weight, POUND_PLACES)


def net_pounds(gross: Decimal | int, tare: Decimal | int) -> Decimal:
    """Gross minus tare, guarded against a tare that outweighs the load."""
    g = to_pounds(gross)
    t = to_pounds(tare)
    if t > g:
        raise UnitError(f"tare {t} exceeds gross {g}")
    return g - t


def pounds_to_bushels(pounds: Decimal | int, test_weight_per_bushel: Decimal) -> Decimal:
    """Convert pounds to bushels at a commodity's standard test weight.

    The divisor here is the *standard* pounds per bushel for the commodity, not
    the test weight measured on the probe sample. Corn is 56 whether the load
    tested 52 or 58; a light load is handled by the test weight discount
    schedule and never by moving the divisor.
    """
    std = Decimal(test_weight_per_bushel)
    if std <= 0:
        raise UnitError(f"standard test weight must be positive: {std}")
    return quantize(to_pounds(pounds) / std, BUSHEL_PLACES)


def bushels_to_pounds(bushels: Decimal, test_weight_per_bushel: Decimal) -> Decimal:
    std = Decimal(test_weight_per_bushel)
    if std <= 0:
        raise UnitError(f"standard test weight must be positive: {std}")
    return quantize(Decimal(bushels) * std, POUND_PLACES)


def pounds_to_cwt(pounds: Decimal | int) -> Decimal:
    return quantize(to_pounds(pounds) / POUNDS_PER_CWT, CWT_PLACES)


def pounds_to_tons(pounds: Decimal | int) -> Decimal:
    return quantize(to_pounds(pounds) / POUNDS_PER_TON, Decimal("0.001"))


def percent_of(value: Decimal, percent: Decimal, places: Decimal = BUSHEL_PLACES) -> Decimal:
    """Take a percentage of a quantity, rounded at the caller's precision."""
    return quantize(Decimal(value) * Decimal(percent) / Decimal("100"), places)
