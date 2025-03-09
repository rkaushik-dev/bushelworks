"""Board reports.

Everything here is a read and everything here is slow. The patronage table walks
every settlement in a crop year, which on a real house is thirty thousand rows,
so these are not routes anybody should poll. The office runs them once in
September and prints the answer.

Two shapes come back off the same data. ``.../table`` is the eighty column page
that goes in the board pack, and ``.../export`` is the CSV the accountant opens.
Neither is computed here: both come out of ``reports.patronage`` so a number on
the printed page and the same number in the spreadsheet cannot drift apart.

The membership roll is not in this database. It arrives as a repeated query
parameter, which is ugly with four hundred members and correct with all of them,
and the alternative was a second roll that goes stale.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Depends, Query, Response

from ...persistence.unitofwork import UnitOfWork
from ...reports.patronage import PatronageError, Year, render, to_csv
from ...services.patronage import allocate, build_year, crop_year
from ..deps import get_uow
from ..schemas import Money, Quantity
from ..security import office_access

router = APIRouter(
    prefix="/reports",
    tags=["reports"],
    dependencies=[Depends(office_access)],
)


class PatronageLineOut(Money):
    party_code: str
    name: str
    is_member: bool
    loads: int
    bushels: Quantity
    gross: Quantity
    margin: Quantity
    margin_per_bushel: Quantity
    share_percent: Quantity


class PatronageOut(Money):
    location_code: str
    opens: date
    closes: date
    bushels: Quantity
    gross: Quantity
    margin: Quantity
    concentration_percent: Quantity
    lines: list[PatronageLineOut]


def _span(year: int | None, opens: date | None, closes: date | None) -> tuple[date, date]:
    """A crop year, or a span the caller named. Not both."""
    if year is not None:
        return crop_year(year)
    if opens is None or closes is None:
        raise PatronageError("give a crop year, or both ends of a span")
    return opens, closes


def _built(
    uow: UnitOfWork,
    year: int | None,
    opens: date | None,
    closes: date | None,
    location_code: str | None,
    members: list[str] | None,
) -> Year:
    start, end = _span(year, opens, closes)
    return build_year(uow, start, end, location_code, members)


@router.get("/patronage", response_model=PatronageOut)
def read_patronage(
    year: int | None = None,
    opens: date | None = None,
    closes: date | None = None,
    location_code: str | None = None,
    member: list[str] | None = Query(default=None),
    uow: UnitOfWork = Depends(get_uow),
) -> PatronageOut:
    """The whole table, one line per producer who settled."""
    built = _built(uow, year, opens, closes, location_code, member)
    return PatronageOut(
        location_code=built.location_code,
        opens=built.opens,
        closes=built.closes,
        bushels=built.bushels,
        gross=built.gross,
        margin=built.margin,
        concentration_percent=built.concentration(),
        lines=[
            PatronageLineOut(
                party_code=line.party_code,
                name=line.name,
                is_member=line.is_member,
                loads=line.loads,
                bushels=line.bushels,
                gross=line.gross,
                margin=line.margin,
                margin_per_bushel=line.margin_per_bushel,
                share_percent=built.share_of_bushels(line.party_code),
            )
            for line in built.by_bushels()
        ],
    )


@router.get(
    "/patronage/table",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
)
def print_patronage(
    year: int | None = None,
    opens: date | None = None,
    closes: date | None = None,
    location_code: str | None = None,
    order: str = "bushels",
    limit: int = 25,
    member: list[str] | None = Query(default=None),
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    """The page that goes in the board pack, eighty columns wide."""
    built = _built(uow, year, opens, closes, location_code, member)
    return Response(
        content=render(built, order=order, limit=limit),
        media_type="text/plain; charset=utf-8",
    )


@router.get(
    "/patronage/export",
    response_class=Response,
    responses={200: {"content": {"text/csv": {}}}},
)
def export_patronage(
    year: int | None = None,
    opens: date | None = None,
    closes: date | None = None,
    location_code: str | None = None,
    member: list[str] | None = Query(default=None),
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    built = _built(uow, year, opens, closes, location_code, member)
    stamp = year if year is not None else built.opens
    return Response(
        content=to_csv(built),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="patronage-{stamp}.csv"'
        },
    )


@router.get("/patronage/allocation")
def read_allocation(
    pool: Decimal,
    year: int | None = None,
    opens: date | None = None,
    closes: date | None = None,
    location_code: str | None = None,
    minimum: Decimal = Decimal("5.00"),
    member: list[str] | None = Query(default=None),
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """What each member would get out of a pool, before the board votes.

    Shares under the minimum are dropped rather than rounded up, so the total
    coming back is usually a little under the pool. That difference is the
    unallocated remainder and it is meant to be visible.
    """
    built = _built(uow, year, opens, closes, location_code, member)
    return {
        code: str(share)
        for code, share in allocate(built, pool, minimum).items()
    }
