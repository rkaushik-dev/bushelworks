"""What the house has to tell the revenue service about each producer.

Grain purchases go on a 1099-MISC as crop insurance and other income, and the
number that goes in the box is not the settlement net. Three things move it:

* storage, drying and quality discounts are part of the purchase price and stay
  in the reported figure, because they reduce what was paid rather than being a
  separate service billed to the producer
* checkoff withheld is reported, because the producer paid it
* an advance is not income when it is advanced and is not income again when it
  is recovered, so it nets out and must not be double counted

The threshold is applied per payee per year, and a payee under it still gets a
row in the working file so the office can see the ones that nearly made it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Sequence

from sqlalchemy import select

from ..domain.settlement import LineKind
from ..errors import ValidationError
from ..money import amount, total
from ..persistence.base import as_decimal
from ..persistence.models import SettlementLineRow, SettlementRow
from ..persistence.unitofwork import UnitOfWork

REPORTING_THRESHOLD = Decimal("600.00")

# The kinds that are part of what the producer was paid for his grain. Advances
# are excluded on purpose: they are a timing arrangement, not income.
REPORTABLE_REDUCTIONS = frozenset(
    {
        LineKind.DISCOUNT.value,
        LineKind.DRYING.value,
        LineKind.STORAGE.value,
        LineKind.SERVICE.value,
        LineKind.ASSESSMENT.value,
    }
)


class TaxError(ValidationError):
    """Raised when a reporting figure cannot be worked out."""

    code = "tax_error"


@dataclass(frozen=True, slots=True)
class PayeeYear:
    """One producer's reportable total for one calendar year."""

    party_code: str
    year: int
    gross: Decimal
    reductions: Decimal
    advances_recovered: Decimal
    settlements: int

    @property
    def reportable(self) -> Decimal:
        """Gross less the reductions that are part of the purchase price."""
        return amount(self.gross - self.reductions)

    @property
    def is_reportable(self) -> bool:
        return self.reportable >= REPORTING_THRESHOLD

    @property
    def shortfall(self) -> Decimal:
        gap = REPORTING_THRESHOLD - self.reportable
        return gap if gap > 0 else Decimal("0.00")


def _line_totals(uow: UnitOfWork, settlement_ids: Sequence[int]) -> dict[str, Decimal]:
    if not settlement_ids:
        return {}
    rows = uow.session.execute(
        select(SettlementLineRow.kind, SettlementLineRow.dollars).where(
            SettlementLineRow.settlement_id.in_(settlement_ids)
        )
    )
    totals: dict[str, Decimal] = {}
    for kind, dollars in rows:
        totals[kind] = totals.get(kind, Decimal("0")) + as_decimal(dollars)
    return totals


def payee_year(uow: UnitOfWork, party_code: str, year: int) -> PayeeYear:
    """One producer, one year, from the settlements as written."""
    if year < 2000 or year > 2100:
        raise TaxError(f"implausible reporting year {year}")

    rows = list(
        uow.session.scalars(
            select(SettlementRow).where(
                SettlementRow.party_code == party_code,
                SettlementRow.settled_on >= date(year, 1, 1),
                SettlementRow.settled_on <= date(year, 12, 31),
                SettlementRow.voided.is_(False),
            )
        )
    )
    totals = _line_totals(uow, [row.id for row in rows])
    gross = totals.get(LineKind.GROSS.value, Decimal("0"))
    reductions = total(
        value for kind, value in totals.items() if kind in REPORTABLE_REDUCTIONS
    )
    advances = totals.get(LineKind.ADVANCE.value, Decimal("0"))
    return PayeeYear(
        party_code=party_code,
        year=year,
        gross=amount(gross),
        reductions=amount(reductions),
        advances_recovered=amount(advances),
        settlements=len(rows),
    )


def payees(uow: UnitOfWork, year: int) -> list[PayeeYear]:
    """Everybody the house settled with in a year, biggest first."""
    codes = uow.session.scalars(
        select(SettlementRow.party_code)
        .where(
            SettlementRow.settled_on >= date(year, 1, 1),
            SettlementRow.settled_on <= date(year, 12, 31),
            SettlementRow.voided.is_(False),
        )
        .distinct()
    )
    found = [payee_year(uow, code, year) for code in sorted(set(codes))]
    return sorted(found, key=lambda p: (-p.reportable, p.party_code))


def reportable(uow: UnitOfWork, year: int) -> list[PayeeYear]:
    return [payee for payee in payees(uow, year) if payee.is_reportable]


def near_threshold(
    uow: UnitOfWork, year: int, within: Decimal = Decimal("100.00")
) -> list[PayeeYear]:
    """Payees that fell just short.

    The office looks at these because a late settlement keyed in January often
    belongs to the previous year and pushes one of them over.
    """
    return [
        payee
        for payee in payees(uow, year)
        if not payee.is_reportable and payee.shortfall <= within
    ]


def working_file(uow: UnitOfWork, year: int) -> str:
    """A CSV of every payee, whether or not they cross the threshold."""
    import csv
    import io

    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "party_code",
            "year",
            "settlements",
            "gross",
            "reductions",
            "reportable",
            "advances_recovered",
            "is_reportable",
        ]
    )
    for payee in payees(uow, year):
        writer.writerow(
            [
                payee.party_code,
                payee.year,
                payee.settlements,
                payee.gross,
                payee.reductions,
                payee.reportable,
                payee.advances_recovered,
                "yes" if payee.is_reportable else "no",
            ]
        )
    return buffer.getvalue()
