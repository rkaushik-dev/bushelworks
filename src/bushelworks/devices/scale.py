"""Reading the truck scale indicator.

The indicator on the pit sends a continuous stream at 9600 baud whether or not
anybody is listening. Each frame is:

    STX  S  sign  d d d d d d  unit unit  CR

where the status byte ``S`` is ``S`` for stable, ``M`` for in motion and ``O``
for over capacity, the six digits are the displayed weight with no decimal
point, and the unit is ``lb`` or ``kg``.

Three rules, all of them learned the hard way:

* only a stable frame is a weight. A frame in motion is the truck rocking and
  taking it is how a load ends up two hundred pounds light.
* the stream is read from the end. The indicator buffers, and the first complete
  frame in the buffer may be thirty seconds old.
* a frame that does not parse is dropped in silence. The line picks up noise
  from the auger motor every time it starts, and an operator who is told about
  it stops reading the messages.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError

STX = 0x02
CR = 0x0D
FRAME_LENGTH = 12

POUNDS_PER_KILOGRAM = Decimal("2.20462262")


class IndicatorError(ValidationError):
    """Raised when a frame cannot be read as a weight."""

    code = "indicator_error"


class Stability(str, Enum):
    STABLE = "S"
    MOTION = "M"
    OVER_CAPACITY = "O"


@dataclass(frozen=True, slots=True)
class ScaleReading:
    """One frame off the indicator."""

    pounds: Decimal
    stability: Stability
    unit: str

    @property
    def is_usable(self) -> bool:
        """Only a stable frame is a weight worth writing on a ticket."""
        return self.stability is Stability.STABLE

    @property
    def is_over_capacity(self) -> bool:
        return self.stability is Stability.OVER_CAPACITY


def parse_reading(frame: bytes) -> ScaleReading:
    """Read one frame, or say exactly what is wrong with it."""
    if len(frame) != FRAME_LENGTH:
        raise IndicatorError(
            f"a frame is {FRAME_LENGTH} bytes, got {len(frame)}"
        )
    if frame[0] != STX:
        raise IndicatorError("frame does not start with STX")
    if frame[-1] != CR:
        raise IndicatorError("frame does not end with CR")

    status = chr(frame[1])
    try:
        stability = Stability(status)
    except ValueError as exc:
        raise IndicatorError(f"{status!r} is not a status byte") from exc

    sign = chr(frame[2])
    if sign not in ("+", "-"):
        raise IndicatorError(f"{sign!r} is not a sign")

    digits = frame[3:9].decode("ascii", errors="replace")
    if not digits.isdigit():
        raise IndicatorError(f"{digits!r} is not a weight")

    unit = frame[9:11].decode("ascii", errors="replace").strip().lower()
    if unit not in ("lb", "kg"):
        raise IndicatorError(f"{unit!r} is not a unit this indicator sends")

    value = Decimal(digits)
    if sign == "-":
        value = -value
    if unit == "kg":
        value = (value * POUNDS_PER_KILOGRAM).quantize(Decimal("1"))

    return ScaleReading(pounds=value, stability=stability, unit=unit)


def frames(buffer: bytes) -> list[bytes]:
    """Split a buffer into whole frames, dropping anything partial."""
    found: list[bytes] = []
    start = 0
    while True:
        index = buffer.find(bytes([STX]), start)
        if index == -1:
            break
        end = index + FRAME_LENGTH
        if end > len(buffer):
            break
        candidate = buffer[index:end]
        if candidate[-1] == CR:
            found.append(candidate)
            start = end
        else:
            start = index + 1
    return found


def parse_stream(buffer: bytes) -> ScaleReading | None:
    """The most recent usable weight in a buffer, or nothing.

    Reads backwards. The indicator buffers while nobody is asking, so the first
    frame in the buffer can be a minute old and belong to the truck before this
    one.
    """
    for frame in reversed(frames(buffer)):
        try:
            reading = parse_reading(frame)
        except IndicatorError:
            continue
        if reading.is_usable:
            return reading
    return None


def latest(buffer: bytes) -> ScaleReading | None:
    """The most recent frame that parsed at all, stable or not.

    Used by the display on the scale house wall, which shows the weight moving
    while the truck settles.
    """
    for frame in reversed(frames(buffer)):
        try:
            return parse_reading(frame)
        except IndicatorError:
            continue
    return None


def build_frame(
    pounds: int, stability: Stability = Stability.STABLE, unit: str = "lb"
) -> bytes:
    """Make a frame. Used by the tests and by the driveway simulator."""
    if abs(pounds) > 999_999:
        raise IndicatorError(f"{pounds} does not fit in six digits")
    sign = "-" if pounds < 0 else "+"
    return (
        bytes([STX])
        + stability.value.encode("ascii")
        + sign.encode("ascii")
        + f"{abs(pounds):06d}".encode("ascii")
        + unit.ljust(2).encode("ascii")
        + bytes([CR])
    )
