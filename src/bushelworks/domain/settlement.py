"""The settlement statement: what the producer is actually paid.

A settlement is an ordered list of lines and a net. The order is not cosmetic.
Assessments are levied on the value after discounts, so the discount lines have
to be settled before the assessment lines can be computed, and an advance is
recovered after everything else because it is a repayment rather than a charge
against the grain.

The order this module enforces, and the reason each step is where it is:

1. gross value of the priced bushels
2. quality discounts, which reduce the value
3. drying, which is a service and not a quality discount
4. storage and service charges, which are also services
5. assessments, levied on the value left after step 2
6. advances and other deductions, recovered last
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import allocate, amount, extend, total
from .tickets import SplitShare


class LineKind(str, Enum):
    GROSS = "gross"
    DISCOUNT = "discount"
    DRYING = "drying"
    STORAGE = "storage"
    SERVICE = "service"
    ASSESSMENT = "assessment"
    ADVANCE = "advance"
    DEDUCTION = "deduction"
    ADJUSTMENT = "adjustment"


LINE_ORDER: dict[LineKind, int] = {
    LineKind.GROSS: 0,
    LineKind.DISCOUNT: 1,
    LineKind.DRYING: 2,
    LineKind.STORAGE: 3,
    LineKind.SERVICE: 4,
    LineKind.ASSESSMENT: 5,
    LineKind.ADVANCE: 6,
    LineKind.DEDUCTION: 7,
    LineKind.ADJUSTMENT: 8,
}

# Kinds that reduce the net. Everything else adds to it.
REDUCING = frozenset(
    {
        LineKind.DISCOUNT,
        LineKind.DRYING,
        LineKind.STORAGE,
        LineKind.SERVICE,
        LineKind.ASSESSMENT,
        LineKind.ADVANCE,
        LineKind.DEDUCTION,
    }
)


class SettlementError(ValidationError):
    """Raised when a settlement cannot be assembled or does not balance."""


@dataclass(frozen=True, slots=True)
class SettlementLine:
    """One row on the statement, stored as a positive magnitude and a kind."""

    kind: LineKind
    code: str
    description: str
    dollars: Decimal
    quantity: Decimal | None = None
    rate: Decimal | None = None

    def __post_init__(self) -> None:
        if self.dollars < 0:
            raise SettlementError(
                f"{self.code}: store a line as a positive amount and let the kind "
                "decide the sign"
            )

    @property
    def signed(self) -> Decimal:
        """The amount as it contributes to the net."""
        if self.kind in REDUCING:
            return amount(-self.dollars)
        return amount(self.dollars)


@dataclass(frozen=True, slots=True)
class Settlement:
    """A finished statement for one party."""

    number: str
    settled_on: date
    party_id: str
    location_code: str
    lines: Sequence[SettlementLine]
    ticket_numbers: tuple[str, ...] = ()
    contract_numbers: tuple[str, ...] = ()

    @property
    def gross(self) -> Decimal:
        return total(
            line.dollars for line in self.lines if line.kind is LineKind.GROSS
        )

    @property
    def deductions(self) -> Decimal:
        return total(line.dollars for line in self.lines if line.kind in REDUCING)

    @property
    def net(self) -> Decimal:
        return total(line.signed for line in self.lines)

    def of_kind(self, kind: LineKind) -> tuple[SettlementLine, ...]:
        return tuple(line for line in self.lines if line.kind is kind)

    def subtotal(self, kind: LineKind) -> Decimal:
        return total(line.dollars for line in self.of_kind(kind))

    @property
    def value_after_discounts(self) -> Decimal:
        """Gross less quality discounts. What a percentage assessment sits on."""
        return amount(self.gross - self.subtotal(LineKind.DISCOUNT))

    def check_balances(self) -> None:
        """Assert the net is the gross less everything, to the cent."""
        expected = amount(self.gross - self.deductions)
        if expected != self.net:
            raise SettlementError(
                f"{self.number}: net {self.net} does not equal gross less "
                f"deductions {expected}"
            )


class SettlementBuilder:
    """Collects lines and hands back a statement in canonical order."""

    def __init__(
        self,
        number: str,
        settled_on: date,
        party_id: str,
        location_code: str,
    ) -> None:
        self.number = number
        self.settled_on = settled_on
        self.party_id = party_id
        self.location_code = location_code
        self._lines: list[SettlementLine] = []
        self._tickets: list[str] = []
        self._contracts: list[str] = []

    def add(self, line: SettlementLine) -> SettlementBuilder:
        if line.dollars != 0:
            self._lines.append(line)
        return self

    def add_gross(
        self, code: str, description: str, bushels: Decimal, unit_price: Decimal
    ) -> SettlementBuilder:
        return self.add(
            SettlementLine(
                kind=LineKind.GROSS,
                code=code,
                description=description,
                dollars=extend(bushels, unit_price),
                quantity=bushels,
                rate=unit_price,
            )
        )

    def add_charge(
        self,
        kind: LineKind,
        code: str,
        description: str,
        dollars: Decimal,
        quantity: Decimal | None = None,
        rate: Decimal | None = None,
    ) -> SettlementBuilder:
        return self.add(
            SettlementLine(
                kind=kind,
                code=code,
                description=description,
                dollars=amount(dollars),
                quantity=quantity,
                rate=rate,
            )
        )

    def reference_ticket(self, ticket_number: str) -> SettlementBuilder:
        if ticket_number not in self._tickets:
            self._tickets.append(ticket_number)
        return self

    def reference_contract(self, contract_number: str) -> SettlementBuilder:
        if contract_number not in self._contracts:
            self._contracts.append(contract_number)
        return self

    @property
    def running_gross(self) -> Decimal:
        return total(
            line.dollars for line in self._lines if line.kind is LineKind.GROSS
        )

    @property
    def running_discounts(self) -> Decimal:
        return total(
            line.dollars for line in self._lines if line.kind is LineKind.DISCOUNT
        )

    def build(self) -> Settlement:
        ordered = sorted(
            self._lines,
            key=lambda line: (LINE_ORDER[line.kind], line.code),
        )
        settlement = Settlement(
            number=self.number,
            settled_on=self.settled_on,
            party_id=self.party_id,
            location_code=self.location_code,
            lines=tuple(ordered),
            ticket_numbers=tuple(self._tickets),
            contract_numbers=tuple(self._contracts),
        )
        settlement.check_balances()
        return settlement


def split_settlement(
    settlement: Settlement, shares: Sequence[SplitShare]
) -> dict[str, Decimal]:
    """Divide a finished net across the parties on the ticket.

    Every line is split rather than only the net, so a rounding cent lands on the
    same party for the gross and for the discounts and the two do not drift
    apart on the printed statements.
    """
    if not shares:
        raise SettlementError(f"{settlement.number}: nothing to split across")
    weights = [share.percent for share in shares]
    party_totals = {share.party_id: Decimal("0.00") for share in shares}
    for line in settlement.lines:
        parts = allocate(line.signed, weights)
        for share, part in zip(shares, parts, strict=False):
            party_totals[share.party_id] = amount(party_totals[share.party_id] + part)
    return party_totals


def combine(settlements: Iterable[Settlement]) -> Decimal:
    """Net of several statements, for a cheque that covers more than one."""
    return total(s.net for s in settlements)
