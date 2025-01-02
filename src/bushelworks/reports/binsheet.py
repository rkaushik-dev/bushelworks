"""The bin sheet, which lives on a clipboard by the loader.

One line per bin, what is in it, what it tests and how much room is left. The
loader operator reads it at seven in the morning to decide which bin a car comes
out of, so the columns are in the order he asks the questions:

what is in it, how much, what does it test, can I put more in.

The warm column is the one the manager looks at. A bin over the working
temperature with the fan off is the thing that turns into a spoiled bin, and it
is easier to see on a printed sheet than on any screen.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from ..domain.bins import WORKING_FILL
from ..errors import ValidationError

WIDTH = 96


class BinSheetError(ValidationError):
    """Raised when a sheet cannot be built."""

    code = "bin_sheet_error"


@dataclass(frozen=True, slots=True)
class Line:
    """One bin, flattened for printing."""

    bin_id: str
    commodity_code: str
    bushels: Decimal
    capacity: Decimal
    moisture: Decimal | None
    test_weight: Decimal | None
    headroom: Decimal
    warmest: Decimal | None
    fan_running: bool
    out_of_service: bool

    @property
    def percent_full(self) -> Decimal:
        if self.capacity <= 0:
            return Decimal("0.0")
        return (self.bushels / self.capacity * Decimal("100")).quantize(Decimal("0.1"))

    @property
    def is_warm(self) -> bool:
        return self.warmest is not None and self.warmest > Decimal("21")

    @property
    def needs_attention(self) -> bool:
        """Warm with the fan off, which is the one that spoils."""
        return self.is_warm and not self.fan_running


def line_for(row, since: datetime | None = None) -> Line:
    """Flatten a bin row and its recent readings into one printable line."""
    readings = list(row.readings)
    if since is not None:
        readings = [
            reading
            for reading in readings
            if _naive(reading.taken_at) >= _naive(since)
        ]
    warmest = (
        max(Decimal(reading.temperature_c) for reading in readings)
        if readings
        else None
    )
    fan = any(reading.fan_running for reading in readings) if readings else False
    capacity = Decimal(row.capacity_bushels)
    bushels = Decimal(row.bushels)
    room = capacity * WORKING_FILL - bushels
    return Line(
        bin_id=row.bin_id,
        commodity_code=row.commodity_code or "",
        bushels=bushels,
        capacity=capacity,
        moisture=None if row.moisture is None else Decimal(row.moisture),
        test_weight=None if row.test_weight is None else Decimal(row.test_weight),
        headroom=room if room > 0 else Decimal("0"),
        warmest=warmest,
        fan_running=fan,
        out_of_service=row.out_of_service,
    )


def _naive(moment: datetime) -> datetime:
    return moment.replace(tzinfo=None) if moment.tzinfo is not None else moment


def _cell(value: Decimal | None, places: str = "1") -> str:
    if value is None:
        return "-"
    return str(value.quantize(Decimal(places)))


def render(
    lines: Sequence[Line],
    location_code: str,
    house_name: str = "BUSHELWORKS ELEVATOR",
) -> str:
    """The sheet as it goes on the clipboard."""
    out = [
        house_name.center(WIDTH).rstrip(),
        f"BIN SHEET  {location_code}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"{'BIN':<8}{'CROP':<7}{'BUSHELS':>12}{'FULL':>8}{'MOIST':>8}"
        f"{'TEST WT':>9}{'ROOM':>12}{'WARM':>8}{'FAN':>6}{'NOTE':>10}",
        "-" * WIDTH,
    ]
    for line in sorted(lines, key=lambda item: item.bin_id):
        note = "OUT" if line.out_of_service else ("LOOK" if line.needs_attention else "")
        out.append(
            f"{line.bin_id:<8}{line.commodity_code or '-':<7}"
            f"{line.bushels.quantize(Decimal('1')):>12}"
            f"{line.percent_full:>7}%"
            f"{_cell(line.moisture):>8}"
            f"{_cell(line.test_weight):>9}"
            f"{line.headroom.quantize(Decimal('1')):>12}"
            f"{_cell(line.warmest):>8}"
            f"{('yes' if line.fan_running else 'no'):>6}"
            f"{note:>10}"
        )
    out.append("-" * WIDTH)
    total = sum((line.bushels for line in lines), Decimal("0"))
    room = sum((line.headroom for line in lines), Decimal("0"))
    out.append(
        f"{'TOTAL':<8}{'':<7}{total.quantize(Decimal('1')):>12}"
        f"{'':>8}{'':>8}{'':>9}{room.quantize(Decimal('1')):>12}"
    )
    attention = [line.bin_id for line in lines if line.needs_attention]
    if attention:
        out.append("")
        out.append("LOOK AT TODAY: " + ", ".join(sorted(attention)))
    return "\n".join(out)


def to_csv(lines: Sequence[Line]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "bin_id",
            "commodity_code",
            "bushels",
            "capacity",
            "percent_full",
            "moisture",
            "test_weight",
            "headroom",
            "warmest",
            "fan_running",
            "out_of_service",
            "needs_attention",
        ]
    )
    for line in sorted(lines, key=lambda item: item.bin_id):
        writer.writerow(
            [
                line.bin_id,
                line.commodity_code,
                line.bushels,
                line.capacity,
                line.percent_full,
                "" if line.moisture is None else line.moisture,
                "" if line.test_weight is None else line.test_weight,
                line.headroom,
                "" if line.warmest is None else line.warmest,
                "yes" if line.fan_running else "no",
                "yes" if line.out_of_service else "no",
                "yes" if line.needs_attention else "no",
            ]
        )
    return buffer.getvalue()


def room_for(lines: Sequence[Line], commodity_code: str) -> Decimal:
    """Everywhere a load of this commodity could still go."""
    return sum(
        (
            line.headroom
            for line in lines
            if not line.out_of_service
            and line.commodity_code in ("", commodity_code)
        ),
        Decimal("0"),
    )
