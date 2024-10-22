"""The daily close.

At the end of the day somebody has to say what happened, and the software has to
be able to prove it. The close does four things and refuses to finish if any of
them does not tie:

1. counts what came in and what went out
2. rebuilds the position and compares it with the previous close
3. checks that every settlement written today balances line by line
4. checks that the ticket numbers issued today have no holes in them

A close that fails is not an error to be shrugged at. It means one of the day's
documents is wrong and the office finds out now rather than at year end.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import func, select

from ..domain.position import PositionReport
from ..errors import ConflictError, ValidationError
from ..logging import event, failure, get_logger
from ..money import amount, total
from ..persistence.base import as_decimal
from ..persistence.models import ScaleTicketRow, SettlementRow
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, quantize
from .positions import build_report
from .shipping import shipped_bushels

_log = get_logger("dayend")


class CloseError(ValidationError):
    """Raised when the day will not close."""

    code = "close_error"


@dataclass(frozen=True, slots=True)
class Discrepancy:
    """One thing that did not tie."""

    kind: str
    detail: str

    def __str__(self) -> str:
        return f"{self.kind}: {self.detail}"


@dataclass
class Close:
    """What the day looked like."""

    day: date
    location_code: str
    tickets_written: int = 0
    tickets_voided: int = 0
    bushels_in: Decimal = Decimal("0")
    bushels_out: Decimal = Decimal("0")
    settlements_written: int = 0
    settled_value: Decimal = Decimal("0.00")
    position: PositionReport | None = None
    discrepancies: list[Discrepancy] = field(default_factory=list)

    @property
    def is_clean(self) -> bool:
        return not self.discrepancies

    @property
    def net_bushels(self) -> Decimal:
        return quantize(self.bushels_in - self.bushels_out, BUSHEL_PLACES)

    def note(self, kind: str, detail: str) -> None:
        self.discrepancies.append(Discrepancy(kind, detail))


def _tickets_on(uow: UnitOfWork, day: date, location_code: str | None) -> list[ScaleTicketRow]:
    query = select(ScaleTicketRow).where(ScaleTicketRow.scale_date == day)
    if location_code:
        query = query.where(ScaleTicketRow.location_code == location_code)
    return list(uow.session.scalars(query.order_by(ScaleTicketRow.ticket_number)))


def _settlements_on(
    uow: UnitOfWork, day: date, location_code: str | None
) -> list[SettlementRow]:
    return uow.settlements.for_day(day, location_code)


def _check_numbering(close: Close, tickets: list[ScaleTicketRow]) -> None:
    """Ticket stock is pre numbered. A hole means a ticket is in a drawer."""
    numeric: list[int] = []
    for row in tickets:
        tail = row.ticket_number.rsplit("-", 1)[-1]
        if tail.isdigit():
            numeric.append(int(tail))
    if len(numeric) < 2:
        return
    expected = set(range(min(numeric), max(numeric) + 1))
    missing = sorted(expected - set(numeric))
    if missing:
        listed = ", ".join(str(number) for number in missing[:10])
        close.note(
            "ticket numbering",
            f"{len(missing)} numbers unaccounted for: {listed}",
        )


def _check_settlements(close: Close, settlements: list[SettlementRow]) -> None:
    for row in settlements:
        lines = total(as_decimal(line.dollars) for line in row.lines if line.kind == "gross")
        if amount(lines) != amount(row.gross):
            close.note(
                "settlement",
                f"{row.number} gross {row.gross} does not match its lines {lines}",
            )
        expected = amount(as_decimal(row.gross) - as_decimal(row.deductions))
        if expected != amount(row.net):
            close.note(
                "settlement",
                f"{row.number} net {row.net} is not gross less deductions {expected}",
            )


def _check_position(
    close: Close, uow: UnitOfWork, day: date, location_code: str, tolerance: Decimal
) -> None:
    today = build_report(uow, day, location_code=location_code)
    yesterday = build_report(uow, day - timedelta(days=1), location_code=location_code)
    close.position = today

    for code in today.commodities():
        now = today.by_commodity(code).net_long
        try:
            before = yesterday.by_commodity(code).net_long
        except Exception:
            before = Decimal("0")
        move = abs(now - before)
        if move > tolerance:
            close.note(
                "position",
                f"{code} moved {move} bushels overnight, above the {tolerance} tolerance",
            )


def run_close(
    uow: UnitOfWork,
    day: date,
    location_code: str = "*",
    position_tolerance: Decimal = Decimal("250000"),
) -> Close:
    """Close one day at one site, or across the house."""
    site = None if location_code == "*" else location_code
    close = Close(day=day, location_code=location_code)

    tickets = _tickets_on(uow, day, site)
    live = [row for row in tickets if not row.voided]
    close.tickets_written = len(live)
    close.tickets_voided = len(tickets) - len(live)

    pounds = uow.session.scalar(
        select(func.sum(ScaleTicketRow.gross_pounds - ScaleTicketRow.tare_pounds)).where(
            ScaleTicketRow.scale_date == day,
            ScaleTicketRow.voided.is_(False),
        )
    )
    close.bushels_in = quantize(as_decimal(pounds) / Decimal("56"), BUSHEL_PLACES)

    settlements = _settlements_on(uow, day, site)
    close.settlements_written = len(settlements)
    close.settled_value = total(as_decimal(row.net) for row in settlements)

    for code in ("CORN", "SOYB", "HRW"):
        close.bushels_out = quantize(
            close.bushels_out + shipped_bushels(uow, code, day, site), BUSHEL_PLACES
        )

    _check_numbering(close, live)
    _check_settlements(close, settlements)
    _check_position(close, uow, day, location_code, position_tolerance)

    if close.is_clean:
        event(
            _log,
            "day.closed",
            day=day,
            location=location_code,
            tickets=close.tickets_written,
            settlements=close.settlements_written,
            bushels_in=close.bushels_in,
        )
    else:
        failure(
            _log,
            "day.will_not_close",
            day=day,
            location=location_code,
            problems=len(close.discrepancies),
        )
    return close


def require_clean(close: Close) -> None:
    """Refuse to let the day be signed off with something outstanding."""
    if close.is_clean:
        return
    listed = "; ".join(str(item) for item in close.discrepancies[:5])
    raise ConflictError(f"{close.day} will not close: {listed}")


def render(close: Close, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The close as it goes in the day book."""
    width = 72
    lines = [
        house_name.center(width).rstrip(),
        f"DAILY CLOSE {close.day.isoformat()}  {close.location_code}".center(width).rstrip(),
        "=" * width,
        f"{'tickets written':<28}{close.tickets_written:>12}",
        f"{'tickets voided':<28}{close.tickets_voided:>12}",
        f"{'bushels in':<28}{close.bushels_in:>12}",
        f"{'bushels out':<28}{close.bushels_out:>12}",
        f"{'net bushels':<28}{close.net_bushels:>12}",
        f"{'settlements written':<28}{close.settlements_written:>12}",
        f"{'settled value':<28}{close.settled_value:>12}",
        "-" * width,
    ]
    if close.position is not None:
        for code in close.position.commodities():
            line = close.position.by_commodity(code)
            lines.append(f"{code:<28}{line.net_long:>12} net long")
    lines.append("-" * width)
    if close.is_clean:
        lines.append("closed clean")
    else:
        lines.append(f"{len(close.discrepancies)} problems:")
        lines.extend(f"  {item}" for item in close.discrepancies)
    return "\n".join(lines)
