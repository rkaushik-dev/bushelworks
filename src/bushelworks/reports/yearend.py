"""The producer's year in one page.

Every January the office prints one of these for each producer and posts it with
the 1099. It is the only document that shows a whole crop year at once, and it
is the one producers actually read, so the arithmetic on it has to agree with
the statements they already have in a drawer.

Nothing here recomputes anything. It reads the settlements as written and adds
them up, because a year end summary that disagrees with the statements it
summarises is worse than no summary at all.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..domain.settlement import LineKind, Settlement
from ..errors import ValidationError
from ..money import amount, format_amount, total

WIDTH = 80


class YearEndError(ValidationError):
    """Raised when a year cannot be summarised."""

    code = "year_end_error"


@dataclass
class Summary:
    """One producer, one crop year."""

    party_code: str
    year: int
    settlements: list[Settlement] = field(default_factory=list)

    def add(self, statement: Settlement) -> None:
        if statement.party_id != self.party_code:
            raise YearEndError(
                f"{statement.number} belongs to {statement.party_id}, "
                f"not {self.party_code}"
            )
        if statement.settled_on.year != self.year:
            raise YearEndError(
                f"{statement.number} was settled in {statement.settled_on.year}, "
                f"not {self.year}"
            )
        self.settlements.append(statement)

    @property
    def count(self) -> int:
        return len(self.settlements)

    @property
    def gross(self) -> Decimal:
        return total(statement.gross for statement in self.settlements)

    @property
    def net(self) -> Decimal:
        return total(statement.net for statement in self.settlements)

    @property
    def tickets(self) -> tuple[str, ...]:
        seen: list[str] = []
        for statement in self.settlements:
            for number in statement.ticket_numbers:
                if number not in seen:
                    seen.append(number)
        return tuple(seen)

    def by_kind(self) -> dict[LineKind, Decimal]:
        """Every charge kind across the year, in canonical order."""
        totals: dict[LineKind, Decimal] = {}
        for statement in self.settlements:
            for line in statement.lines:
                totals[line.kind] = amount(
                    totals.get(line.kind, Decimal("0")) + line.dollars
                )
        return {kind: totals[kind] for kind in LineKind if kind in totals}

    def check_balances(self) -> None:
        """The summary has to add back to the statements it summarises."""
        kinds = self.by_kind()
        gross = kinds.get(LineKind.GROSS, Decimal("0.00"))
        reductions = amount(
            sum(
                (value for kind, value in kinds.items() if kind is not LineKind.GROSS),
                Decimal("0"),
            )
        )
        if amount(gross - reductions) != self.net:
            raise YearEndError(
                f"{self.party_code} {self.year}: the summary nets to "
                f"{amount(gross - reductions)} and the statements net to {self.net}"
            )


def build(party_code: str, year: int, settlements: Sequence[Settlement]) -> Summary:
    """Gather one producer's year out of a run of statements."""
    summary = Summary(party_code=party_code, year=year)
    for statement in settlements:
        if statement.party_id != party_code:
            continue
        if statement.settled_on.year != year:
            continue
        summary.add(statement)
    return summary


def render(summary: Summary, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The page that goes in the envelope."""
    lines = [
        house_name.center(WIDTH).rstrip(),
        f"YEAR END SUMMARY {summary.year}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"Producer     {summary.party_code}",
        f"Settlements  {summary.count}",
        f"Tickets      {len(summary.tickets)}",
        "-" * WIDTH,
    ]
    for kind, value in summary.by_kind().items():
        label = kind.value.replace("_", " ").upper()
        signed = value if kind is LineKind.GROSS else -value
        lines.append(f"  {label:<40}{format_amount(signed):>20}")
    lines.append("-" * WIDTH)
    lines.append(f"  {'NET PAID':<40}{format_amount(summary.net):>20}")
    if not summary.settlements:
        lines.append("")
        lines.append("Nothing settled in this year.")
    return "\n".join(lines)


def statement_index(summary: Summary) -> str:
    """The list of statements the summary is built from, for the second page."""
    lines = [
        f"{'STATEMENT':<16}{'DATE':<12}{'TICKETS':<28}{'NET':>14}",
        "-" * 72,
    ]
    for statement in sorted(summary.settlements, key=lambda s: s.settled_on):
        tickets = ", ".join(statement.ticket_numbers)
        lines.append(
            f"{statement.number:<16}{statement.settled_on.isoformat():<12}"
            f"{tickets[:26]:<28}{format_amount(statement.net):>14}"
        )
    lines.append("-" * 72)
    lines.append(f"{summary.count} statements{format_amount(summary.net):>60}")
    return "\n".join(lines)


def busiest_month(summary: Summary) -> tuple[int, Decimal] | None:
    """The month the producer was paid most in, which he will ask about."""
    if not summary.settlements:
        return None
    by_month: dict[int, Decimal] = {}
    for statement in summary.settlements:
        month = statement.settled_on.month
        by_month[month] = amount(by_month.get(month, Decimal("0")) + statement.net)
    month = max(by_month, key=lambda key: by_month[key])
    return month, by_month[month]


def crop_year_span(year: int) -> tuple[date, date]:
    """A crop year runs from the first of September to the last of August."""
    return date(year - 1, 9, 1), date(year, 8, 31)
