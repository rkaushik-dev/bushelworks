"""The daily position board, printed and as a spreadsheet.

Two renderings of the same numbers. The printed one goes on the wall by the
manager's desk and the CSV goes to whoever asks for it, which is usually the
lender. Neither computes anything: if a total is wrong it is wrong in
:mod:`bushelworks.domain.position` and this will show it faithfully.
"""

from __future__ import annotations

import csv
import io
from decimal import Decimal
from typing import Iterable, Sequence

from ..domain.position import PositionLine, PositionReport, hedge_requirement

WIDTH = 98

COLUMNS: tuple[tuple[str, str, int], ...] = (
    ("COMMODITY", "commodity_code", 12),
    ("SITE", "location_code", 8),
    ("ON HAND", "on_hand", 14),
    ("STORAGE", "storage_obligation", 14),
    ("DP", "deferred_price", 12),
    ("BOUGHT", "purchased_forward", 12),
    ("SOLD", "sold_forward", 12),
)


def bushels(value: Decimal) -> str:
    """Bushels on a report are whole. Nobody hedges a tenth of one."""
    rounded = Decimal(value).quantize(Decimal("1"))
    if rounded < 0:
        return f"({abs(rounded):,})"
    return f"{rounded:,}"


def header() -> str:
    parts = [f"{title:>{width}}" if index > 1 else f"{title:<{width}}"
             for index, (title, _, width) in enumerate(COLUMNS)]
    return "".join(parts) + f"{'NET LONG':>14}"


def row(line: PositionLine) -> str:
    parts: list[str] = []
    for index, (_, attribute, width) in enumerate(COLUMNS):
        value = getattr(line, attribute)
        if index > 1:
            parts.append(f"{bushels(value):>{width}}")
        else:
            parts.append(f"{value:<{width}}")
    return "".join(parts) + f"{bushels(line.net_long):>14}"


def render(report: PositionReport, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The board as it goes on the wall."""
    lines = [
        house_name.center(WIDTH).rstrip(),
        f"POSITION AS OF {report.as_of.isoformat()}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        header(),
        "-" * WIDTH,
    ]
    for line in sorted(report.lines, key=lambda item: (item.commodity_code, item.location_code)):
        lines.append(row(line))
    lines.append("-" * WIDTH)
    lines.append(f"{'TOTAL OWNED':<20}{bushels(report.total_owned()):>20}")

    shorts = report.shorts()
    if shorts:
        lines.append("")
        lines.append("SHORT:")
        for line in shorts:
            lines.append(
                f"  {line.commodity_code} at {line.location_code} "
                f"is short {bushels(abs(line.net_long))} bushels"
            )
    return "\n".join(lines)


def hedge_sheet(
    report: PositionReport, contract_size: Decimal = Decimal("5000")
) -> str:
    """What the desk would have to sell to be flat, in whole contracts."""
    lines = [
        f"HEDGE SHEET {report.as_of.isoformat()}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"{'COMMODITY':<12}{'NET LONG':>16}{'CONTRACTS':>14}{'RESIDUAL':>16}",
        "-" * WIDTH,
    ]
    for code in report.commodities():
        line = report.by_commodity(code)
        lots = hedge_requirement(line, contract_size)
        residual = line.net_long - (Decimal(lots) * contract_size)
        lines.append(
            f"{code:<12}{bushels(line.net_long):>16}{lots:>14}"
            f"{bushels(residual):>16}"
        )
    return "\n".join(lines)


def to_csv(report: PositionReport) -> str:
    """The same rows for a spreadsheet, quantities unrounded."""
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "as_of",
            "commodity_code",
            "location_code",
            "on_hand",
            "storage_obligation",
            "deferred_price",
            "purchased_forward",
            "sold_forward",
            "in_transit",
            "owned",
            "net_long",
        ]
    )
    for line in sorted(
        report.lines, key=lambda item: (item.commodity_code, item.location_code)
    ):
        writer.writerow(
            [
                line.as_of.isoformat(),
                line.commodity_code,
                line.location_code,
                line.on_hand,
                line.storage_obligation,
                line.deferred_price,
                line.purchased_forward,
                line.sold_forward,
                line.in_transit,
                line.owned,
                line.net_long,
            ]
        )
    return buffer.getvalue()


def compare(
    yesterday: Sequence[PositionLine], today: Sequence[PositionLine]
) -> list[tuple[str, Decimal]]:
    """The day's move, by commodity.

    What the manager actually looks at. A position that is right and a position
    that moved four hundred thousand bushels overnight are different kinds of
    right.
    """
    def totals(lines: Iterable[PositionLine]) -> dict[str, Decimal]:
        out: dict[str, Decimal] = {}
        for line in lines:
            out[line.commodity_code] = out.get(line.commodity_code, Decimal("0")) + line.net_long
        return out

    before = totals(yesterday)
    after = totals(today)
    codes = sorted(set(before) | set(after))
    return [
        (code, after.get(code, Decimal("0")) - before.get(code, Decimal("0")))
        for code in codes
    ]
