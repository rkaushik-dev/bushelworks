"""What the manager does at eight in the morning.

He looks at the position, looks at what the broker says is on, and works out how
many contracts he is short of where he wants to be. Then he rings the broker.

The two halves of that come from different places and neither of them can be
made to produce the other. The position is on file here, assembled out of
tickets and lots and contracts. The futures account is not: fills arrive on a
statement in the afternoon post and somebody keys them in, so the account comes
in as an argument rather than out of a table.

Nothing here decides to hedge. It says what the gap is and what closing it would
cost at the prices it was handed, and a person decides.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..domain.hedging import (
    CONTRACT_BUSHELS,
    Account,
    HedgeError,
    Side,
    contracts_for,
)
from ..domain.position import PositionReport
from ..domain.pricing import FuturesMonth
from ..money import amount, price
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, quantize
from .positions import build_report

# A gap smaller than this is not worth a telephone call. One contract of corn
# is five thousand bushels and the house carries that much slack most days.
TOLERANCE_CONTRACTS = 1


@dataclass(frozen=True, slots=True)
class Gap:
    """One commodity, and how far the hedge is from the position."""

    commodity_code: str
    net_long: Decimal
    hedged_bushels: Decimal
    want_contracts: int
    on_contracts: int

    @property
    def gap_contracts(self) -> int:
        """Positive means sell more. Negative means the house is over hedged."""
        return self.want_contracts - self.on_contracts

    @property
    def gap_bushels(self) -> Decimal:
        return quantize(
            Decimal(self.gap_contracts) * CONTRACT_BUSHELS, BUSHEL_PLACES
        )

    @property
    def side(self) -> Side:
        return Side.SHORT if self.gap_contracts > 0 else Side.LONG

    @property
    def is_actionable(self) -> bool:
        return abs(self.gap_contracts) >= TOLERANCE_CONTRACTS

    @property
    def is_over_hedged(self) -> bool:
        """Short more bushels than the house owns, which is a speculation."""
        return self.gap_contracts < 0

    def cost(self, settle: Decimal) -> Decimal:
        """What the fills would be worth at a price, ignoring commission."""
        return amount(abs(self.gap_bushels) * Decimal(settle))


@dataclass(frozen=True, slots=True)
class Sheet:
    """The whole desk on one morning."""

    as_of: date
    location_code: str
    gaps: tuple[Gap, ...] = ()

    def actionable(self) -> tuple[Gap, ...]:
        return tuple(gap for gap in self.gaps if gap.is_actionable)

    def over_hedged(self) -> tuple[Gap, ...]:
        return tuple(gap for gap in self.gaps if gap.is_over_hedged)

    def for_commodity(self, commodity_code: str) -> Gap:
        for gap in self.gaps:
            if gap.commodity_code == commodity_code:
                return gap
        raise HedgeError(f"{commodity_code} is not on this sheet")

    def total_gap_contracts(self) -> int:
        """Net across the board. Two commodities can offset on the ticket
        and never offset in the bin, so this is a headline and not a plan."""
        return sum(gap.gap_contracts for gap in self.gaps)


def gap_for(
    commodity_code: str,
    net_long: Decimal,
    account: Account,
) -> Gap:
    """One line of the sheet.

    ``want`` rounds toward zero on purpose. A house that rounds up is short more
    grain than it has the moment a load is voided.
    """
    hedged = -account.net_bushels()
    return Gap(
        commodity_code=commodity_code,
        net_long=quantize(Decimal(net_long), BUSHEL_PLACES),
        hedged_bushels=quantize(hedged, BUSHEL_PLACES),
        want_contracts=contracts_for(net_long),
        on_contracts=-account.net_contracts(),
    )


def sheet_for(
    report: PositionReport,
    accounts: Mapping[str, Account],
    location_code: str = "*",
) -> Sheet:
    """Match the position against the broker, commodity by commodity.

    A commodity with no account is not skipped. An unhedged position is the
    thing the manager most needs to see, and leaving it off the sheet because
    there is no matching account is how it stays unseen.
    """
    gaps = []
    for code in report.commodities():
        line = report.by_commodity(code)
        account = accounts.get(code, Account())
        gaps.append(gap_for(code, line.net_long, account))
    return Sheet(as_of=report.as_of, location_code=location_code, gaps=tuple(gaps))


def sheet(
    uow: UnitOfWork,
    as_of: date,
    accounts: Mapping[str, Account],
    commodities: Sequence[str] | None = None,
    location_code: str = "*",
) -> Sheet:
    """Build the position off the database and match it against the broker."""
    report = build_report(
        uow,
        as_of,
        tuple(commodities) if commodities else None,
        location_code,
    )
    return sheet_for(report, accounts, location_code)


def basis(cash_bid: Decimal, futures: Decimal) -> Decimal:
    """Cash less futures. Negative is the usual state at a country house."""
    return price(Decimal(cash_bid) - Decimal(futures))


def carry(
    near: Decimal,
    deferred: Decimal,
    months: int,
    storage_per_month: Decimal,
) -> Decimal:
    """Whether the market is paying to hold grain, per bushel.

    Positive means the spread more than covers the storage the house would
    charge itself. That is the number that decides whether to sell now or roll,
    and it is the one that gets worked out on the back of an envelope and then
    argued about.
    """
    if months <= 0:
        raise HedgeError("a carry needs at least one month")
    spread = Decimal(deferred) - Decimal(near)
    return price(spread - Decimal(storage_per_month) * Decimal(months))


def month_exposure(
    account: Account,
    settlements: Mapping[FuturesMonth, Decimal],
) -> dict[str, Decimal]:
    """Open gain or loss per month, keyed by symbol for printing."""
    out: dict[str, Decimal] = {}
    for month, net in account.by_month().items():
        settle = settlements.get(month)
        if settle is None:
            raise HedgeError(f"no settlement price for {month.symbol}")
        move = Decimal(settle) - account.average_price(month)
        out[month.symbol] = amount(move * Decimal(net) * CONTRACT_BUSHELS)
    return out
