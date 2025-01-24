"""Bin yard routes.

The loader operator's screen. Putting a load away is a POST that may or may not
name a bin: when it does not, the yard picks the one that gets finished first,
which is the rule nobody wants to remember at seven in the morning.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Query, Response, status
from pydantic import Field, field_validator

from ...domain.commodity import Factor
from ...persistence.unitofwork import UnitOfWork
from ...reports.binsheet import line_for, render, room_for, to_csv
from ...services.binyard import (
    bin_at,
    bins_at,
    draw_from,
    put_away,
    record_reading,
    take_out_of_service,
    warm_bins,
    working_headroom,
    yard_total,
)
from ..deps import get_settings, get_uow
from ..schemas import Money, Quantity, _as_decimal
from ..security import scale_access

router = APIRouter(
    prefix="/bins",
    tags=["bins"],
    dependencies=[Depends(scale_access)],
)


class ReadingIn(Money):
    factor: Factor
    value: Quantity

    @field_validator("value", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class PutAwayIn(Money):
    commodity_code: str = Field(min_length=1, max_length=8)
    bushels: Quantity
    bin_id: str | None = None
    sample: list[ReadingIn] = Field(default_factory=list)

    @field_validator("bushels", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class CableIn(Money):
    taken_at: datetime
    temperature_c: Quantity
    cable: int = 1
    moisture: Quantity | None = None
    fan_running: bool = False
    note: str | None = None

    @field_validator("temperature_c", "moisture", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Any:
        return None if value is None else _as_decimal(value)


class BinOut(Money):
    bin_id: str
    location_code: str
    commodity_code: str | None = None
    bushels: Quantity
    capacity_bushels: Quantity
    headroom: Quantity
    moisture: Quantity | None = None
    test_weight: Quantity | None = None
    out_of_service: bool
    out_of_service_reason: str | None = None


def _bin_out(row) -> BinOut:
    return BinOut(
        bin_id=row.bin_id,
        location_code=row.location_code,
        commodity_code=row.commodity_code,
        bushels=row.bushels,
        capacity_bushels=row.capacity_bushels,
        headroom=working_headroom(row),
        moisture=row.moisture,
        test_weight=row.test_weight,
        out_of_service=row.out_of_service,
        out_of_service_reason=row.out_of_service_reason,
    )


@router.get("/{location_code}", response_model=list[BinOut])
def list_bins(
    location_code: str,
    commodity_code: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> list[BinOut]:
    return [_bin_out(row) for row in bins_at(uow, location_code, commodity_code)]


@router.get("/{location_code}/{bin_id}", response_model=BinOut)
def read_bin(
    location_code: str, bin_id: str, uow: UnitOfWork = Depends(get_uow)
) -> BinOut:
    return _bin_out(bin_at(uow, location_code, bin_id))


@router.post(
    "/{location_code}/loads",
    response_model=BinOut,
    status_code=status.HTTP_201_CREATED,
)
def put_load_away(
    location_code: str, body: PutAwayIn, uow: UnitOfWork = Depends(get_uow)
) -> BinOut:
    placed = put_away(
        uow,
        location_code,
        body.commodity_code,
        body.bushels,
        {reading.factor: reading.value for reading in body.sample},
        bin_id=body.bin_id,
    )
    out = _bin_out(bin_at(uow, location_code, placed.bin_id))
    uow.commit()
    return out


@router.post("/{location_code}/{bin_id}/draws", response_model=BinOut)
def draw(
    location_code: str,
    bin_id: str,
    bushels: str,
    uow: UnitOfWork = Depends(get_uow),
) -> BinOut:
    row = draw_from(uow, location_code, bin_id, Decimal(bushels))
    out = _bin_out(row)
    uow.commit()
    return out


@router.post("/{location_code}/{bin_id}/readings", status_code=status.HTTP_201_CREATED)
def add_reading(
    location_code: str,
    bin_id: str,
    body: CableIn,
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    reading = record_reading(
        uow,
        location_code,
        bin_id,
        body.taken_at,
        body.temperature_c,
        cable=body.cable,
        moisture=body.moisture,
        fan_running=body.fan_running,
        note=body.note,
    )
    warm = reading.is_warm
    uow.commit()
    return {"bin_id": bin_id, "warm": "yes" if warm else "no"}


@router.post("/{location_code}/{bin_id}/out-of-service", response_model=BinOut)
def out_of_service(
    location_code: str,
    bin_id: str,
    reason: str = Query(min_length=1, max_length=120),
    on: date | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> BinOut:
    row = take_out_of_service(
        uow, location_code, bin_id, reason, on or date.today()
    )
    out = _bin_out(row)
    uow.commit()
    return out


@router.get("/{location_code}/report/sheet", response_class=Response)
def print_sheet(
    location_code: str,
    since: datetime | None = None,
    uow: UnitOfWork = Depends(get_uow),
    settings=Depends(get_settings),
) -> Response:
    lines = [line_for(row, since) for row in bins_at(uow, location_code)]
    return Response(
        content=render(lines, location_code, house_name=settings.house_name),
        media_type="text/plain; charset=utf-8",
    )


@router.get("/{location_code}/report/export", response_class=Response)
def export_sheet(
    location_code: str,
    since: datetime | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    lines = [line_for(row, since) for row in bins_at(uow, location_code)]
    return Response(
        content=to_csv(lines),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="bins-{location_code}.csv"'
        },
    )


# Under ``report`` rather than at ``/bins/{location}/summary``. The two segment
# path is already taken by the bin lookup, which answered it as a bin called
# "summary" and returned a 404 nobody could explain.
@router.get("/{location_code}/report/summary")
def read_summary(
    location_code: str,
    commodity_code: str = "CORN",
    since: datetime | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """What is in the yard, what room is left and what needs looking at."""
    lines = [line_for(row, since) for row in bins_at(uow, location_code)]
    warm = warm_bins(uow, location_code, since) if since else []
    return {
        "location_code": location_code,
        "commodity_code": commodity_code,
        "bushels": str(yard_total(uow, location_code, commodity_code)),
        "room": str(room_for(lines, commodity_code)),
        "warm": ",".join(row.bin_id for row in warm),
    }
