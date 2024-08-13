"""The checkoff return the house files with each commission.

Quarterly, one return per commission per commodity, listing the bushels the levy
was collected on and the money withheld. The commission reconciles the two
against its own rate, so the bushels have to be the bushels the levy was
actually computed on rather than everything that crossed the scale.

Refundable levies are listed separately from non refundable ones because the
commission owes the refund and the house does not.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..domain.assessments import WithheldLine
from ..errors import ValidationError
from ..money import amount, total

QUARTER_ENDS = ((3, 31), (6, 30), (9, 30), (12, 31))


class RemittanceError(ValidationError):
    """Raised when a return cannot be assembled."""

    code = "remittance_error"


def quarter_of(day: date) -> int:
    return (day.month - 1) // 3 + 1


def quarter_end(year: int, quarter: int) -> date:
    if quarter not in (1, 2, 3, 4):
        raise RemittanceError(f"{quarter} is not a quarter")
    month, day = QUARTER_ENDS[quarter - 1]
    return date(year, month, day)


def quarter_start(year: int, quarter: int) -> date:
    if quarter not in (1, 2, 3, 4):
        raise RemittanceError(f"{quarter} is not a quarter")
    return date(year, (quarter - 1) * 3 + 1, 1)


@dataclass(frozen=True, slots=True)
class Collected:
    """One levy collected on one settlement, as it goes onto the return."""

    settlement_number: str
    settled_on: date
    party_code: str
    commodity_code: str
    bushels: Decimal
    line: WithheldLine


@dataclass
class Return:
    """One commission, one commodity, one quarter."""

    authority: str
    code: str
    commodity_code: str
    year: int
    quarter: int
    entries: list[Collected] = field(default_factory=list)

    def add(self, entry: Collected) -> None:
        if entry.line.code != self.code:
            raise RemittanceError(
                f"{entry.settlement_number}: {entry.line.code} does not belong on a "
                f"{self.code} return"
            )
        if entry.commodity_code != self.commodity_code:
            raise RemittanceError(
                f"{entry.settlement_number}: {entry.commodity_code} does not belong on "
                f"a {self.commodity_code} return"
            )
        if not self.covers(entry.settled_on):
            raise RemittanceError(
                f"{entry.settlement_number}: {entry.settled_on} is outside "
                f"{self.year} Q{self.quarter}"
            )
        self.entries.append(entry)

    def covers(self, day: date) -> bool:
        return quarter_start(self.year, self.quarter) <= day <= quarter_end(
            self.year, self.quarter
        )

    @property
    def bushels(self) -> Decimal:
        return sum((entry.bushels for entry in self.entries), Decimal("0"))

    @property
    def withheld(self) -> Decimal:
        return total(entry.line.dollars for entry in self.entries)

    @property
    def refundable(self) -> Decimal:
        return total(
            entry.line.dollars for entry in self.entries if entry.line.refundable
        )

    @property
    def producers(self) -> tuple[str, ...]:
        return tuple(sorted({entry.party_code for entry in self.entries}))

    @property
    def is_empty(self) -> bool:
        return not self.entries

    @property
    def period(self) -> str:
        return f"{self.year} Q{self.quarter}"


def group(entries: Iterable[Collected], year: int, quarter: int) -> list[Return]:
    """Split a quarter's collections into one return per commission."""
    returns: dict[tuple[str, str], Return] = {}
    for entry in entries:
        key = (entry.line.code, entry.commodity_code)
        if key not in returns:
            returns[key] = Return(
                authority=entry.line.authority,
                code=entry.line.code,
                commodity_code=entry.commodity_code,
                year=year,
                quarter=quarter,
            )
        returns[key].add(entry)
    return [returns[key] for key in sorted(returns)]


def render(one: Return, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The return as the commission wants it on paper."""
    width = 76
    lines = [
        house_name.center(width).rstrip(),
        f"CHECKOFF RETURN {one.period}".center(width).rstrip(),
        "=" * width,
        f"Commission   {one.authority}",
        f"Levy         {one.code}",
        f"Commodity    {one.commodity_code}",
        f"Period       {quarter_start(one.year, one.quarter)} to "
        f"{quarter_end(one.year, one.quarter)}",
        "-" * width,
        f"{'SETTLEMENT':<16}{'DATE':<12}{'PRODUCER':<18}{'BUSHELS':>14}{'WITHHELD':>14}",
        "-" * width,
    ]
    for entry in sorted(one.entries, key=lambda e: (e.settled_on, e.settlement_number)):
        lines.append(
            f"{entry.settlement_number:<16}{entry.settled_on.isoformat():<12}"
            f"{entry.party_code[:16]:<18}{entry.bushels:>14}"
            f"{entry.line.dollars:>14}"
        )
    lines.append("-" * width)
    lines.append(
        f"{len(one.entries)} settlements, {len(one.producers)} producers"
        f"{one.bushels:>{width - 40}}{one.withheld:>14}"
    )
    if one.refundable:
        lines.append(f"of which refundable{one.refundable:>{width - 19}}")
    return "\n".join(lines)


def to_csv(returns: Sequence[Return]) -> str:
    """Every return in the quarter, one row per settlement."""
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "authority",
            "levy",
            "commodity",
            "period",
            "settlement",
            "settled_on",
            "party_code",
            "bushels",
            "withheld",
            "refundable",
        ]
    )
    for one in returns:
        for entry in sorted(one.entries, key=lambda e: e.settlement_number):
            writer.writerow(
                [
                    one.authority,
                    one.code,
                    one.commodity_code,
                    one.period,
                    entry.settlement_number,
                    entry.settled_on.isoformat(),
                    entry.party_code,
                    entry.bushels,
                    entry.line.dollars,
                    "yes" if entry.line.refundable else "no",
                ]
            )
    return buffer.getvalue()


def totals(returns: Sequence[Return]) -> dict[str, Decimal]:
    """What the house owes each commission this quarter."""
    out: dict[str, Decimal] = {}
    for one in returns:
        out[one.code] = amount(out.get(one.code, Decimal("0")) + one.withheld)
    return out
