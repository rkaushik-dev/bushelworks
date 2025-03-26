"""Who the house actually does business with.

At the end of a fiscal year the board wants to know two things that sound like
the same thing and are not: who brought the most bushels, and who the house made
the most money on. The man with four hundred thousand bushels of contracted corn
that moved straight through on rail is worth less than the man with sixty
thousand bushels that sat in a bin for nine months and went through the dryer.

So this ranks on bushels and on margin, side by side, and prints both. The
column that surprises people is the third one, which is margin per bushel.

A co-op also has to allocate patronage on the members share of the qualifying
business. That is the same table with the non members struck out, so it is here
rather than in a second report that drifts out of step with this one.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..money import amount, format_amount
from ..units import BUSHEL_PLACES, quantize
from .exports import cell, text_field

WIDTH = 80

# Below this a producer is listed as "others" rather than by name.
FLOOR_BUSHELS = Decimal("1000")


class PatronageError(ValidationError):
    """Raised when a year of business cannot be ranked."""

    code = "patronage_error"


@dataclass(frozen=True, slots=True)
class Line:
    """One producer, one year."""

    party_code: str
    name: str
    bushels: Decimal
    gross: Decimal
    charges: Decimal
    is_member: bool = True
    loads: int = 0

    def __post_init__(self) -> None:
        if self.bushels < 0:
            raise PatronageError(f"{self.party_code}: negative bushels")
        if self.loads < 0:
            raise PatronageError(f"{self.party_code}: negative loads")

    @property
    def margin(self) -> Decimal:
        """What the house kept. Charges are the discounts, drying and storage."""
        return amount(self.charges)

    @property
    def margin_per_bushel(self) -> Decimal:
        if self.bushels <= 0:
            return Decimal("0.0000")
        return quantize(self.charges / self.bushels, Decimal("0.0001"))

    @property
    def average_load(self) -> Decimal:
        if self.loads <= 0:
            return Decimal("0.00")
        return quantize(self.bushels / Decimal(self.loads), BUSHEL_PLACES)


@dataclass(frozen=True, slots=True)
class Year:
    """Everybody, one year, one house."""

    location_code: str
    opens: date
    closes: date
    lines: tuple[Line, ...] = ()

    def __post_init__(self) -> None:
        if self.closes < self.opens:
            raise PatronageError(
                f"{self.location_code}: the year closes before it opens"
            )
        seen = {line.party_code for line in self.lines}
        if len(seen) != len(self.lines):
            raise PatronageError(f"{self.location_code}: a producer appears twice")

    @property
    def bushels(self) -> Decimal:
        return quantize(
            sum((line.bushels for line in self.lines), Decimal("0")), BUSHEL_PLACES
        )

    @property
    def gross(self) -> Decimal:
        return amount(sum((line.gross for line in self.lines), Decimal("0")))

    @property
    def margin(self) -> Decimal:
        return amount(sum((line.margin for line in self.lines), Decimal("0")))

    def by_bushels(self) -> tuple[Line, ...]:
        return tuple(
            sorted(self.lines, key=lambda line: (-line.bushels, line.party_code))
        )

    def by_margin(self) -> tuple[Line, ...]:
        return tuple(
            sorted(self.lines, key=lambda line: (-line.margin, line.party_code))
        )

    def members(self) -> tuple[Line, ...]:
        return tuple(line for line in self.lines if line.is_member)

    def share_of_bushels(self, party_code: str) -> Decimal:
        """One producer as a percentage of the year."""
        total = self.bushels
        for line in self.lines:
            if line.party_code == party_code:
                if total <= 0:
                    return Decimal("0.0000")
                return quantize(
                    line.bushels / total * Decimal("100"), Decimal("0.0001")
                )
        raise PatronageError(f"{party_code} did not deliver in this year")

    def concentration(self, count: int = 10) -> Decimal:
        """What share of the year the biggest handful account for.

        A house where ten producers are eighty percent of the bushels has a
        different risk than one where they are thirty, and the board should be
        told which one it is running.
        """
        total = self.bushels
        if total <= 0:
            return Decimal("0.0000")
        top = sum((line.bushels for line in self.by_bushels()[:count]), Decimal("0"))
        return quantize(top / total * Decimal("100"), Decimal("0.0001"))

    def allocations(self, pool: Decimal) -> dict[str, Decimal]:
        """Split a patronage pool across the members by their bushels.

        The remainder from rounding goes to the largest member, because it has
        to go somewhere and that is where it is least visible.
        """
        pool = amount(pool)
        members = self.members()
        base = sum((line.bushels for line in members), Decimal("0"))
        if base <= 0:
            return {}
        split: dict[str, Decimal] = {}
        for line in members:
            split[line.party_code] = amount(pool * line.bushels / base)
        drift = pool - sum(split.values(), Decimal("0"))
        if drift:
            biggest = max(members, key=lambda line: (line.bushels, line.party_code))
            split[biggest.party_code] = amount(split[biggest.party_code] + drift)
        return split


def _row(line: Line, rank: int) -> str:
    name = line.name[:22].ljust(22)
    flag = " " if line.is_member else "*"
    return (
        f"{rank:>3} {line.party_code[:10]:<10}{flag} {name}"
        f"{format_amount(line.bushels):>13}"
        f"{format_amount(line.margin):>14}"
        f"{line.margin_per_bushel!s:>9}"
    )


def render(year: Year, order: str = "bushels", limit: int = 25) -> str:
    """The board page, eighty columns like everything else here."""
    if order not in ("bushels", "margin"):
        raise PatronageError(f"cannot rank on {order}")
    lines = year.by_bushels() if order == "bushels" else year.by_margin()
    out = [
        "-" * WIDTH,
        f"PATRONAGE BY {order.upper()}".center(WIDTH).rstrip(),
        f"{year.location_code}  {year.opens} to {year.closes}".center(WIDTH).rstrip(),
        "-" * WIDTH,
        "RNK PRODUCER     NAME                        BUSHELS"
        "         MARGIN   PER BU",
        "-" * WIDTH,
    ]
    shown = [line for line in lines if line.bushels >= FLOOR_BUSHELS][:limit]
    for rank, line in enumerate(shown, start=1):
        out.append(_row(line, rank))
    rest = [line for line in lines if line not in shown]
    if rest:
        bushels = sum((line.bushels for line in rest), Decimal("0"))
        margin = sum((line.margin for line in rest), Decimal("0"))
        out.append(
            f"    {len(rest)} others".ljust(38)
            + f"{format_amount(bushels):>13}{format_amount(margin):>14}"
        )
    out.append("-" * WIDTH)
    out.append(
        "    TOTAL".ljust(38)
        + f"{format_amount(year.bushels):>13}{format_amount(year.margin):>14}"
    )
    out.append(
        f"    top ten hold {format_amount(year.concentration())} percent of bushels"
    )
    out.append("    * not a member")
    return "\n".join(out)


PATRONAGE_COLUMNS = (
    "party_code",
    "name",
    "member",
    "loads",
    "bushels",
    "gross",
    "margin",
    "margin_per_bushel",
    "share_percent",
)


def to_csv(year: Year) -> str:
    """The same table for the accountant, who wants a spreadsheet.

    The cell rules are the export ones rather than a set of its own: plain
    decimals as text, no thousand separators, and a party code written so a
    spreadsheet does not eat its leading zeroes. This formatted its own cells
    to start with and had already drifted from the ticket export on that last
    rule, which is how a producer coded 0041 came out as 41.
    """
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(list(PATRONAGE_COLUMNS))
    for line in year.by_bushels():
        writer.writerow(
            [
                text_field(line.party_code),
                cell(line.name),
                cell(line.is_member),
                cell(line.loads),
                cell(line.bushels),
                cell(line.gross),
                cell(line.margin),
                cell(line.margin_per_bushel),
                cell(year.share_of_bushels(line.party_code)),
            ]
        )
    return buffer.getvalue()


def combine(years: Sequence[Year], location_code: str = "ALL") -> Year:
    """Roll several houses into one table for the board meeting."""
    if not years:
        raise PatronageError("nothing to combine")
    merged: dict[str, Line] = {}
    for year in years:
        for line in year.lines:
            found = merged.get(line.party_code)
            if found is None:
                merged[line.party_code] = line
                continue
            merged[line.party_code] = Line(
                party_code=line.party_code,
                name=found.name,
                bushels=found.bushels + line.bushels,
                gross=amount(found.gross + line.gross),
                charges=amount(found.charges + line.charges),
                is_member=found.is_member and line.is_member,
                loads=found.loads + line.loads,
            )
    return Year(
        location_code=location_code,
        opens=min(year.opens for year in years),
        closes=max(year.closes for year in years),
        lines=tuple(merged.values()),
    )
