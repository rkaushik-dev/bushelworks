"""Putting grain away and getting it back out.

The bin yard is the only place that knows where anything physically is. The
position report says the house owns four hundred thousand bushels of corn; this
says which nine bins they are in and what each one tests, which is what the
loader operator needs at seven in the morning.

Nothing here decides what a load is worth. It decides where it goes, and the
choice matters: a wet load into a bin of dry corn spoils the average and a bin
of No. 3 mixed into a bin of No. 2 costs the difference on every bushel.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import select

from ..domain.bins import WORKING_FILL
from ..domain.commodity import Factor
from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event, get_logger, warn
from ..persistence.base import as_decimal
from ..persistence.models import BinReadingRow, BinRow
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, quantize

_log = get_logger("binyard")

# Above this a bin is working and somebody has to look at it that day.
WARM_CELSIUS = Decimal("21")


class BinYardError(ValidationError):
    """Raised when grain cannot go where it is asked to."""

    code = "bin_yard_error"


class BinNotFound(LookupFailure):
    code = "bin_not_found"


@dataclass(frozen=True, slots=True)
class Placed:
    """Where a load went."""

    bin_id: str
    bushels: Decimal
    commodity_code: str
    headroom_left: Decimal


def bin_at(uow: UnitOfWork, location_code: str, bin_id: str) -> BinRow:
    row = uow.session.scalar(
        select(BinRow).where(
            BinRow.location_code == location_code, BinRow.bin_id == bin_id
        )
    )
    if row is None:
        raise BinNotFound(f"no bin {bin_id} at {location_code}")
    return row


def bins_at(
    uow: UnitOfWork, location_code: str, commodity_code: str | None = None
) -> list[BinRow]:
    query = select(BinRow).where(BinRow.location_code == location_code)
    if commodity_code is not None:
        query = query.where(BinRow.commodity_code == commodity_code)
    return list(uow.session.scalars(query.order_by(BinRow.bin_id)))


def working_headroom(row: BinRow) -> Decimal:
    """What can still go in before the bin is called full."""
    room = as_decimal(row.capacity_bushels) * WORKING_FILL - as_decimal(row.bushels)
    return quantize(room, BUSHEL_PLACES) if room > 0 else Decimal("0.0000")


def choose_bin(
    uow: UnitOfWork, location_code: str, commodity_code: str, bushels: Decimal
) -> BinRow | None:
    """Where to put a load.

    Into a bin already holding the commodity before an empty one, and the
    fullest of those first, so partly filled bins get finished rather than the
    yard ending up with nine bins a third full.
    """
    candidates = [
        row
        for row in bins_at(uow, location_code)
        if not row.out_of_service
        and row.commodity_code in (None, commodity_code)
        and working_headroom(row) >= Decimal(bushels)
    ]
    if not candidates:
        return None
    same = [row for row in candidates if row.commodity_code == commodity_code]
    if same:
        return max(same, key=lambda row: (as_decimal(row.bushels), row.bin_id))
    return min(candidates, key=lambda row: (as_decimal(row.capacity_bushels), row.bin_id))


def put_away(
    uow: UnitOfWork,
    location_code: str,
    commodity_code: str,
    bushels: Decimal,
    sample: dict[Factor, Decimal],
    bin_id: str | None = None,
) -> Placed:
    """Put a load into a bin, blending it with what is already there."""
    wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
    if wanted <= 0:
        raise BinYardError("nothing to put away")

    row = (
        bin_at(uow, location_code, bin_id)
        if bin_id
        else choose_bin(uow, location_code, commodity_code, wanted)
    )
    if row is None:
        raise BinYardError(
            f"nowhere at {location_code} will take {wanted} bushels of {commodity_code}"
        )
    if row.out_of_service:
        raise ConflictError(f"{row} is out of service: {row.out_of_service_reason}")
    if row.commodity_code not in (None, commodity_code):
        raise ConflictError(f"{row} holds {row.commodity_code}, not {commodity_code}")
    if working_headroom(row) < wanted:
        raise BinYardError(
            f"{row}: only {working_headroom(row)} bushels of room, asked for {wanted}"
        )

    existing = as_decimal(row.bushels)
    row.commodity_code = commodity_code
    row.moisture = _blend(row.moisture, existing, sample.get(Factor.MOISTURE), wanted)
    row.test_weight = _blend(
        row.test_weight, existing, sample.get(Factor.TEST_WEIGHT), wanted
    )
    row.damage = _blend(row.damage, existing, sample.get(Factor.DAMAGE_TOTAL), wanted)
    row.bushels = quantize(existing + wanted, BUSHEL_PLACES)
    uow.flush()

    event(
        _log,
        "bin.filled",
        bin=str(row),
        commodity=commodity_code,
        bushels=wanted,
        holding=row.bushels,
    )
    return Placed(
        bin_id=row.bin_id,
        bushels=wanted,
        commodity_code=commodity_code,
        headroom_left=working_headroom(row),
    )


def _blend(
    existing_value: Decimal | None,
    existing_bushels: Decimal,
    incoming_value: Decimal | None,
    incoming_bushels: Decimal,
) -> Decimal | None:
    """Weighted average, or nothing if either side was never measured."""
    if incoming_value is None:
        return existing_value
    if existing_value is None or existing_bushels <= 0:
        return Decimal(incoming_value)
    total = existing_bushels + incoming_bushels
    weighted = (
        Decimal(existing_value) * existing_bushels
        + Decimal(incoming_value) * incoming_bushels
    )
    return quantize(weighted / total, Decimal("0.01"))


def draw_from(
    uow: UnitOfWork, location_code: str, bin_id: str, bushels: Decimal
) -> BinRow:
    """Take grain out. What is left tests the same as what came out."""
    row = bin_at(uow, location_code, bin_id)
    wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
    if wanted <= 0:
        raise BinYardError(f"{row}: nothing to draw")
    if wanted > as_decimal(row.bushels):
        raise BinYardError(
            f"{row}: only {row.bushels} bushels in it, asked for {wanted}"
        )
    left = quantize(as_decimal(row.bushels) - wanted, BUSHEL_PLACES)
    row.bushels = left
    if left == 0:
        row.commodity_code = None
        row.moisture = None
        row.test_weight = None
        row.damage = None
    uow.flush()
    return row


def record_reading(
    uow: UnitOfWork,
    location_code: str,
    bin_id: str,
    taken_at: datetime,
    temperature_c: Decimal,
    cable: int = 1,
    moisture: Decimal | None = None,
    fan_running: bool = False,
    note: str | None = None,
) -> BinReadingRow:
    """Log a cable reading, warning if the bin is working."""
    row = bin_at(uow, location_code, bin_id)
    reading = BinReadingRow(
        taken_at=taken_at,
        cable=cable,
        temperature_c=temperature_c,
        moisture=moisture,
        fan_running=fan_running,
        note=note,
    )
    row.readings.append(reading)
    uow.flush()
    if Decimal(temperature_c) > WARM_CELSIUS:
        warn(
            _log,
            "bin.warm",
            bin=str(row),
            cable=cable,
            temperature=temperature_c,
            fan=fan_running,
        )
    return reading


def _naive(moment: datetime) -> datetime:
    """Compare timestamps without tripping over the driver's tz handling.

    SQLite has no timestamp type and hands a naive datetime back for a column
    declared with a time zone; Postgres hands back an aware one. Comparing the
    two raises, so everything is put on the same footing here rather than in
    every caller.
    """
    return moment.replace(tzinfo=None) if moment.tzinfo is not None else moment


def warm_bins(uow: UnitOfWork, location_code: str, since: datetime) -> list[BinRow]:
    """Bins with a reading above the working temperature since a moment."""
    cutoff = _naive(since)
    found: list[BinRow] = []
    for row in bins_at(uow, location_code):
        recent = [
            reading
            for reading in row.readings
            if _naive(reading.taken_at) >= cutoff and reading.is_warm
        ]
        if recent:
            found.append(row)
    return found


def yard_total(
    uow: UnitOfWork, location_code: str, commodity_code: str | None = None
) -> Decimal:
    rows = bins_at(uow, location_code, commodity_code)
    return quantize(
        sum((as_decimal(row.bushels) for row in rows), Decimal("0")), BUSHEL_PLACES
    )


def take_out_of_service(
    uow: UnitOfWork, location_code: str, bin_id: str, reason: str, on: date
) -> BinRow:
    row = bin_at(uow, location_code, bin_id)
    if not reason:
        raise BinYardError(f"{row}: needs a reason")
    if row.out_of_service:
        raise ConflictError(f"{row} is already out of service")
    row.out_of_service = True
    row.out_of_service_reason = reason
    row.last_turned_on = on
    uow.flush()
    return row
