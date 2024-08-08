"""Instruments on the driveway.

The scale indicator, the moisture meter and the test weight scale all talk over
serial and none of them agree about anything. Each adapter parses one make's
output into a value object and refuses anything it cannot read, because a weight
guessed from a corrupted line is worse than no weight at all.

Nothing here opens a port. The adapters take bytes and return values, which is
what makes them testable without a truck.
"""

from .scale import (
    IndicatorError,
    ScaleReading,
    Stability,
    parse_reading,
    parse_stream,
)

__all__ = [
    "IndicatorError",
    "ScaleReading",
    "Stability",
    "parse_reading",
    "parse_stream",
]
