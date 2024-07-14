"""Building the daily position out of what is on file.

The position report is the one number the manager looks at before he answers the
telephone to the terminal, and it is assembled from five places that each know
part of it:

* tickets received, less what has been shipped, is what is physically on hand
* open storage lots are what the house owes producers in grain
* open deferred price lots are what the house owns but has not paid for
* open purchase contracts are bushels bought and not yet delivered
* open sale contracts are bushels sold and not yet shipped

Getting the sign of the third one wrong is the classic mistake and the reason
the domain object splits ``owned`` from ``net_long``.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import func, select

from ..domain import commodity as cm
from ..domain.contracts import ContractStatus
from ..domain.position import PositionLine, PositionReport
from ..persistence.base import as_decimal
from ..persistence.models import (
    ApplicationRow,
    ContractRow,
    ScaleTicketRow,
    StorageLotRow,
)
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, pounds_to_bushels, quantize


def received_bushels(
    uow: UnitOfWork,
    commodity_code: str,
    through: date,
    location_code: str | None = None,
) -> Decimal:
    """Everything across the scale, in bushels, less voids."""
    query = select(
        func.sum(ScaleTicketRow.gross_pounds - ScaleTicketRow.tare_pounds)
    ).where(
        ScaleTicketRow.commodity_code == commodity_code,
        ScaleTicketRow.scale_date <= through,
        ScaleTicketRow.voided.is_(False),
    )
    if location_code:
        query = query.where(ScaleTicketRow.location_code == location_code)
    pounds = as_decimal(uow.session.scalar(query))
    if pounds == 0:
        return Decimal("0.0000")
    commodity = cm.get(commodity_code)
    return pounds_to_bushels(pounds, commodity.standard_test_weight)


def _open_lot_bushels(
    uow: UnitOfWork,
    commodity_code: str,
    through: date,
    deferred: bool,
    location_code: str | None = None,
) -> Decimal:
    query = select(StorageLotRow).where(
        StorageLotRow.commodity_code == commodity_code,
        StorageLotRow.stored_on <= through,
        StorageLotRow.is_deferred_price.is_(deferred),
    )
    if location_code:
        query = query.where(StorageLotRow.location_code == location_code)
    rows = uow.session.scalars(query)
    return quantize(
        sum((row.open_bushels for row in rows), Decimal("0")), BUSHEL_PLACES
    )


def contracted_bushels(
    uow: UnitOfWork,
    commodity_code: str,
    through: date,
    location_code: str | None = None,
) -> Decimal:
    """Bushels bought forward that have not arrived yet.

    Contract quantity less what has been applied to it, floored at zero. A
    contract delivered past its face amount does not turn into a short.
    """
    query = select(ContractRow).where(
        ContractRow.commodity_code == commodity_code,
        ContractRow.status == ContractStatus.OPEN.value,
        ContractRow.written_on <= through,
        ContractRow.window_closes >= through,
    )
    if location_code:
        query = query.where(ContractRow.location_code == location_code)

    outstanding = Decimal("0")
    for row in uow.session.scalars(query):
        applied = as_decimal(
            uow.session.scalar(
                select(func.sum(ApplicationRow.bushels)).where(
                    ApplicationRow.contract_id == row.id,
                    ApplicationRow.reversed_on.is_(None),
                )
            )
        )
        remaining = as_decimal(row.bushels) - applied
        if remaining > 0:
            outstanding += remaining
    return quantize(outstanding, BUSHEL_PLACES)


def line_for(
    uow: UnitOfWork,
    commodity_code: str,
    as_of: date,
    location_code: str = "*",
    sold_forward: Decimal = Decimal("0"),
    in_transit: Decimal = Decimal("0"),
    shipped: Decimal = Decimal("0"),
) -> PositionLine:
    """One commodity at one site on one day.

    ``sold_forward`` and ``shipped`` come in from outside because the sale side
    lives on the terminal's paperwork rather than in this database yet. They are
    arguments rather than zeroes so the report is honest about what it does not
    know.
    """
    site = None if location_code == "*" else location_code
    received = received_bushels(uow, commodity_code, as_of, site)
    on_hand = quantize(received - Decimal(shipped), BUSHEL_PLACES)
    return PositionLine(
        as_of=as_of,
        commodity_code=commodity_code,
        location_code=location_code,
        on_hand=on_hand if on_hand > 0 else Decimal("0"),
        storage_obligation=_open_lot_bushels(uow, commodity_code, as_of, False, site),
        deferred_price=_open_lot_bushels(uow, commodity_code, as_of, True, site),
        purchased_forward=contracted_bushels(uow, commodity_code, as_of, site),
        sold_forward=Decimal(sold_forward),
        in_transit=Decimal(in_transit),
    )


def build_report(
    uow: UnitOfWork,
    as_of: date,
    commodities: tuple[str, ...] | None = None,
    location_code: str = "*",
) -> PositionReport:
    """The whole board for one day."""
    report = PositionReport(as_of=as_of)
    for code in commodities or cm.codes():
        line = line_for(uow, code, as_of, location_code)
        if line.on_hand == 0 and line.purchased_forward == 0:
            continue
        report.add(line)
    return report


def unhedged(report: PositionReport, hedged: dict[str, Decimal]) -> dict[str, Decimal]:
    """The gap between the position and what the broker says is on."""
    gaps: dict[str, Decimal] = {}
    for code in report.commodities():
        line = report.by_commodity(code)
        gaps[code] = quantize(
            line.net_long - hedged.get(code, Decimal("0")), BUSHEL_PLACES
        )
    return gaps
