"""Position routes.

Read only. Nothing here changes anything, which is why there is no unit of work
commit in the file and why the manager's screen can poll it every thirty
seconds without anybody worrying.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Depends, Query, Response

from ...domain.position import hedge_requirement
from ...persistence.unitofwork import UnitOfWork
from ...reports.position import hedge_sheet, render, to_csv
from ...services.positions import build_report, line_for, unhedged
from ..deps import get_settings, get_uow
from ..schemas import Money, Quantity
from ..security import read_access

# Nothing here changes anything, so a read key is enough.
router = APIRouter(
    prefix="/positions",
    tags=["positions"],
    dependencies=[Depends(read_access)],
)


class PositionLineOut(Money):
    as_of: date
    commodity_code: str
    location_code: str
    on_hand: Quantity
    storage_obligation: Quantity
    deferred_price: Quantity
    purchased_forward: Quantity
    sold_forward: Quantity
    in_transit: Quantity
    owned: Quantity
    net_long: Quantity
    is_short: bool
    hedge_contracts: int


class PositionOut(Money):
    as_of: date
    total_owned: Quantity
    lines: list[PositionLineOut]


def _line_out(line, contract_size: Decimal) -> PositionLineOut:
    return PositionLineOut(
        as_of=line.as_of,
        commodity_code=line.commodity_code,
        location_code=line.location_code,
        on_hand=line.on_hand,
        storage_obligation=line.storage_obligation,
        deferred_price=line.deferred_price,
        purchased_forward=line.purchased_forward,
        sold_forward=line.sold_forward,
        in_transit=line.in_transit,
        owned=line.owned,
        net_long=line.net_long,
        is_short=line.is_short,
        hedge_contracts=hedge_requirement(line, contract_size),
    )


@router.get("/{as_of}", response_model=PositionOut)
def read_position(
    as_of: date,
    location_code: str = "*",
    contract_size: Decimal = Query(default=Decimal("5000")),
    uow: UnitOfWork = Depends(get_uow),
) -> PositionOut:
    report = build_report(uow, as_of, location_code=location_code)
    return PositionOut(
        as_of=report.as_of,
        total_owned=report.total_owned(),
        lines=[_line_out(line, contract_size) for line in report.lines],
    )


@router.get("/{as_of}/commodity/{commodity_code}", response_model=PositionLineOut)
def read_commodity_position(
    as_of: date,
    commodity_code: str,
    location_code: str = "*",
    sold_forward: str = "0",
    shipped: str = "0",
    contract_size: Decimal = Query(default=Decimal("5000")),
    uow: UnitOfWork = Depends(get_uow),
) -> PositionLineOut:
    """One commodity, with the sale side supplied by the caller.

    Sales still live on the terminal's paperwork, so the screen passes what the
    desk knows rather than the service inventing a zero.
    """
    line = line_for(
        uow,
        commodity_code,
        as_of,
        location_code=location_code,
        sold_forward=Decimal(sold_forward),
        shipped=Decimal(shipped),
    )
    return _line_out(line, contract_size)


@router.get(
    "/{as_of}/board",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
)
def print_board(
    as_of: date,
    location_code: str = "*",
    uow: UnitOfWork = Depends(get_uow),
    settings=Depends(get_settings),
) -> Response:
    report = build_report(uow, as_of, location_code=location_code)
    return Response(
        content=render(report, house_name=settings.house_name),
        media_type="text/plain; charset=utf-8",
    )


@router.get(
    "/{as_of}/hedge-sheet",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
)
def print_hedge_sheet(
    as_of: date,
    location_code: str = "*",
    contract_size: Decimal = Query(default=Decimal("5000")),
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    report = build_report(uow, as_of, location_code=location_code)
    return Response(
        content=hedge_sheet(report, contract_size),
        media_type="text/plain; charset=utf-8",
    )


@router.get(
    "/{as_of}/export",
    response_class=Response,
    responses={200: {"content": {"text/csv": {}}}},
)
def export_position(
    as_of: date,
    location_code: str = "*",
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    report = build_report(uow, as_of, location_code=location_code)
    return Response(
        content=to_csv(report),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="position-{as_of}.csv"'
        },
    )


@router.get("/{as_of}/unhedged")
def read_unhedged(
    as_of: date,
    location_code: str = "*",
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """The gap between the book and what the broker says is on.

    Hedged quantities are not on file here, so this reports the whole position
    as unhedged until somebody feeds the broker statement in. That is the honest
    answer and it is deliberately uncomfortable.
    """
    report = build_report(uow, as_of, location_code=location_code)
    return {code: str(gap) for code, gap in unhedged(report, {}).items()}
