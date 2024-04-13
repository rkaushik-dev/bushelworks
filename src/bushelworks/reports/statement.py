"""The producer statement, rendered for an eighty column dot matrix.

The width is not nostalgia. These print on carbonless three part stock in the
scale house and the form has been the same since before the software was, so the
columns are fixed and the amounts right align on the same character in every
row. A producer who has been reading these for thirty years should not have to
learn a new one.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Iterable, Sequence

from ..domain.settlement import LineKind, Settlement
from ..money import format_amount

WIDTH = 80
DESCRIPTION_WIDTH = 44
CODE_WIDTH = 12
AMOUNT_WIDTH = 14

KIND_HEADINGS: dict[LineKind, str] = {
    LineKind.GROSS: "GRAIN PURCHASED",
    LineKind.DISCOUNT: "QUALITY DISCOUNTS",
    LineKind.DRYING: "DRYING",
    LineKind.STORAGE: "STORAGE",
    LineKind.SERVICE: "SERVICE CHARGES",
    LineKind.ASSESSMENT: "CHECKOFF WITHHELD",
    LineKind.ADVANCE: "ADVANCES RECOVERED",
    LineKind.DEDUCTION: "OTHER DEDUCTIONS",
    LineKind.ADJUSTMENT: "ADJUSTMENTS",
}


def rule(char: str = "-") -> str:
    return char * WIDTH


def centre(text: str) -> str:
    return text.center(WIDTH).rstrip()


def field_row(label: str, value: str) -> str:
    return f"{label:<18}{value}"


def amount_row(code: str, description: str, dollars: Decimal) -> str:
    return (
        f"  {code:<{CODE_WIDTH}}"
        f"{description[:DESCRIPTION_WIDTH]:<{DESCRIPTION_WIDTH}}"
        f"{format_amount(dollars):>{AMOUNT_WIDTH - 2}}"
    )


def total_row(label: str, dollars: Decimal) -> str:
    left = f"  {label}"
    return f"{left:<{WIDTH - AMOUNT_WIDTH + 2}}{format_amount(dollars):>{AMOUNT_WIDTH - 2}}"


def render(settlement: Settlement, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The whole statement as one string, newline separated, no trailing blank."""
    lines: list[str] = []
    lines.append(centre(house_name))
    lines.append(centre("SETTLEMENT STATEMENT"))
    lines.append(rule("="))
    lines.append(field_row("Statement", settlement.number))
    lines.append(field_row("Date", settlement.settled_on.isoformat()))
    lines.append(field_row("Producer", settlement.party_id))
    lines.append(field_row("Location", settlement.location_code))
    if settlement.ticket_numbers:
        lines.append(field_row("Tickets", ", ".join(settlement.ticket_numbers)))
    if settlement.contract_numbers:
        lines.append(field_row("Contracts", ", ".join(settlement.contract_numbers)))
    lines.append(rule())

    for kind in LineKind:
        of_kind = settlement.of_kind(kind)
        if not of_kind:
            continue
        lines.append(KIND_HEADINGS[kind])
        for line in of_kind:
            lines.append(amount_row(line.code, line.description, line.signed))
        if len(of_kind) > 1:
            lines.append(total_row(f"{KIND_HEADINGS[kind]} TOTAL", settlement.subtotal(kind)))
        lines.append("")

    lines.append(rule())
    lines.append(total_row("GROSS", settlement.gross))
    lines.append(total_row("LESS DEDUCTIONS", settlement.deductions))
    lines.append(rule("="))
    lines.append(total_row("NET PAYABLE", settlement.net))
    return "\n".join(lines)


def render_many(
    settlements: Sequence[Settlement], house_name: str = "BUSHELWORKS ELEVATOR"
) -> str:
    """Several statements separated by a form feed, as the printer expects."""
    return "\f".join(render(s, house_name) for s in settlements)


def summary_line(settlement: Settlement) -> str:
    """One row for the daily settlement register."""
    return (
        f"{settlement.number:<14}"
        f"{settlement.settled_on.isoformat():<12}"
        f"{settlement.party_id[:16]:<18}"
        f"{settlement.location_code:<8}"
        f"{format_amount(settlement.gross):>12}"
        f"{format_amount(settlement.net):>14}"
    )


def register(settlements: Iterable[Settlement], as_of: str) -> str:
    """The register the office reconciles against the cheque run."""
    rows = list(settlements)
    lines = [
        centre("SETTLEMENT REGISTER"),
        centre(as_of),
        rule("="),
        (
            f"{'STATEMENT':<14}{'DATE':<12}{'PRODUCER':<18}"
            f"{'LOC':<8}{'GROSS':>12}{'NET':>14}"
        ),
        rule(),
    ]
    lines.extend(summary_line(s) for s in rows)
    lines.append(rule())
    gross = sum((s.gross for s in rows), Decimal("0.00"))
    net = sum((s.net for s in rows), Decimal("0.00"))
    lines.append(
        f"{'':<14}{'':<12}{f'{len(rows)} statements':<18}"
        f"{'':<8}{format_amount(gross):>12}{format_amount(net):>14}"
    )
    return "\n".join(lines)
