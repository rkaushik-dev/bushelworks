"""Turning a settlement into a journal entry.

The statement and the journal entry are the same event written down for two
different readers, and this is the only place in the package that knows both
shapes. Everything above it works in bushels and line kinds; everything below it
works in accounts and sides.

The mapping is data rather than a chain of ``if`` statements because houses
disagree about two of the lines. Most of them treat a quality discount as a
reduction in what the grain cost rather than as income, which is the default
here, but a house that books discounts to margin only has to file a different
map. Nothing else in the package has to know which one it is.

The one rule that is not configurable: the entry balances because the net was
worked out from the same lines it is posted from. If it ever does not, the
settlement and the statement disagree, and posting a plug to make it balance
would hide exactly the thing worth finding.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..domain.ledger import (
    ChartOfAccounts,
    JournalEntry,
    Leg,
    Side,
)
from ..domain.settlement import REDUCING, LineKind, Settlement
from ..errors import ConflictError, ValidationError
from ..logging import event, get_logger
from ..money import ZERO_AMOUNT
from ..persistence.unitofwork import UnitOfWork

_log = get_logger("posting")

SOURCE = "settlement"


class PostingError(ValidationError):
    """Raised when a settlement cannot be turned into an entry."""

    code = "posting_error"


class PeriodClosed(ConflictError):
    """Raised when something tries to post into a month that has been filed."""

    code = "period_closed"


class AlreadyPosted(ConflictError):
    code = "already_posted"


@dataclass(frozen=True, slots=True)
class PostingMap:
    """Which account each kind of settlement line lands in.

    ``discount`` defaulting to the inventory account is the deliberate one. A
    discount is money the house did not pay for grain it did buy, so it belongs
    against the cost of that grain. Booking it to margin makes the year end show
    a bushel cost the house never paid.
    """

    inventory: str = "1210"
    payable: str = "2100"
    discount: str = "1210"
    drying: str = "4200"
    storage: str = "4100"
    service: str = "4300"
    assessment: str = "2200"
    advance: str = "1300"
    deduction: str = "1400"
    adjustment: str = "4400"

    def account_for(self, kind: LineKind) -> str:
        try:
            return {
                LineKind.GROSS: self.inventory,
                LineKind.DISCOUNT: self.discount,
                LineKind.DRYING: self.drying,
                LineKind.STORAGE: self.storage,
                LineKind.SERVICE: self.service,
                LineKind.ASSESSMENT: self.assessment,
                LineKind.ADVANCE: self.advance,
                LineKind.DEDUCTION: self.deduction,
                LineKind.ADJUSTMENT: self.adjustment,
            }[kind]
        except KeyError:
            raise PostingError(f"no account filed for a {kind.value} line") from None

    def side_for(self, kind: LineKind) -> Side:
        """A line that reduces the producer's net is money the house keeps."""
        if kind in REDUCING:
            return Side.CREDIT
        return Side.DEBIT

    def accounts(self) -> tuple[str, ...]:
        codes = {self.account_for(kind) for kind in LineKind}
        codes.add(self.payable)
        return tuple(sorted(codes))

    def check_against(self, chart: ChartOfAccounts) -> None:
        """Every account this map names has to be one a leg can name."""
        for code in self.accounts():
            chart.postable(code)


DEFAULT_MAP = PostingMap()


@dataclass(frozen=True, slots=True)
class PostingPlan:
    """What an entry would be, before anything is written."""

    entry: JournalEntry
    settlement_number: str
    net: Decimal
    warnings: tuple[str, ...] = field(default_factory=tuple)

    @property
    def balances(self) -> bool:
        return self.entry.balances


def entry_for_settlement(
    settlement: Settlement,
    number: str,
    posted_on: date | None = None,
    mapping: PostingMap = DEFAULT_MAP,
    memo: str = "",
) -> JournalEntry:
    """One journal entry for one statement.

    Lines are grouped by kind rather than posted one to a leg. A statement with
    four discount factors on it is one credit to the discount account, because
    the ledger is not the place to look up which factor it was: the statement is,
    and the entry points at it.
    """
    settlement.check_balances()
    by_kind: dict[LineKind, Decimal] = {}
    for kind in LineKind:
        subtotal = settlement.subtotal(kind)
        if subtotal != ZERO_AMOUNT:
            by_kind[kind] = subtotal

    legs: list[Leg] = []
    for kind in LineKind:
        if kind not in by_kind:
            continue
        dollars = by_kind[kind]
        legs.append(
            Leg(
                account_code=mapping.account_for(kind),
                side=mapping.side_for(kind),
                dollars=dollars,
                memo=kind.value,
            )
        )

    net = settlement.net
    if net > ZERO_AMOUNT:
        legs.append(Leg(mapping.payable, Side.CREDIT, net, "due the producer"))
    elif net < ZERO_AMOUNT:
        legs.append(Leg(mapping.payable, Side.DEBIT, -net, "owed by the producer"))

    if len(legs) < 2:
        raise PostingError(
            f"{settlement.number}: nothing to post, the statement is empty"
        )

    entry = JournalEntry(
        number=number,
        posted_on=posted_on or settlement.settled_on,
        memo=memo or f"settlement {settlement.number} {settlement.party_id}",
        legs=tuple(legs),
        source=SOURCE,
        source_reference=settlement.number,
    )
    if not entry.balances:
        raise PostingError(
            f"{settlement.number}: the statement nets to {settlement.net} but the "
            f"entry is out by {entry.debits - entry.credits}"
        )
    return entry


def plan_settlement(
    uow: UnitOfWork,
    settlement: Settlement,
    number: str,
    posted_on: date | None = None,
    mapping: PostingMap = DEFAULT_MAP,
) -> PostingPlan:
    """Build the entry and check it, without writing anything.

    The day close runs this over everything it is about to post so a refusal
    comes back as a list rather than as the fourth of nine settlements failing
    halfway through.
    """
    entry = entry_for_settlement(settlement, number, posted_on, mapping)
    chart = uow.ledger.chart()
    entry.check_against(chart)
    warnings: list[str] = []
    if uow.ledger.is_posted(SOURCE, settlement.number):
        warnings.append(f"{settlement.number} is already in the journal")
    return PostingPlan(
        entry=entry,
        settlement_number=settlement.number,
        net=settlement.net,
        warnings=tuple(warnings),
    )


def require_open_period(uow: UnitOfWork, day: date) -> None:
    """Refuse a posting into a month whose return has already gone in."""
    period = uow.ledger.period_covering(day)
    if period.closed:
        raise PeriodClosed(
            f"{period.code} was closed on {period.closed_on}, so {day} cannot "
            "take a posting; put it in the open month instead"
        )


def post_settlement(
    uow: UnitOfWork,
    settlement: Settlement,
    number: str,
    posted_on: date | None = None,
    mapping: PostingMap = DEFAULT_MAP,
) -> JournalEntry:
    """Write the entry for a statement. Once, and into an open month."""
    day = posted_on or settlement.settled_on
    require_open_period(uow, day)
    if uow.ledger.is_posted(SOURCE, settlement.number):
        raise AlreadyPosted(
            f"{settlement.number} is already in the journal; reverse it rather "
            "than posting it twice"
        )
    entry = entry_for_settlement(settlement, number, day, mapping)
    entry.check_against(uow.ledger.chart())
    uow.ledger.post(entry)
    event(
        _log,
        "settlement.posted",
        settlement=settlement.number,
        entry=entry.number,
        party=settlement.party_id,
        net=str(settlement.net),
        posted_on=str(day),
    )
    return entry


def reverse_settlement(
    uow: UnitOfWork,
    settlement_number: str,
    number: str,
    posted_on: date,
    reason: str = "",
) -> JournalEntry:
    """Post the opposite of what a settlement posted.

    Takes the settlement number rather than the entry number because that is
    what the office has in front of it. A settlement posted and then reversed
    and then posted again has three entries, and this reverses the last one that
    is still standing.
    """
    require_open_period(uow, posted_on)
    posted = uow.ledger.for_source(SOURCE, settlement_number)
    if not posted:
        raise PostingError(
            f"{settlement_number} was never posted, so it cannot be reversed"
        )
    already_undone = {row.reverses for row in posted if row.reverses is not None}
    standing = [
        row
        for row in posted
        if row.reverses is None and row.number not in already_undone
    ]
    if not standing:
        raise ConflictError(
            f"every entry for {settlement_number} has already been reversed"
        )
    original = uow.ledger.entry(standing[-1].number)
    back = original.reverse(
        number, posted_on, reason or f"reversal of {original.number}"
    )
    back.check_against(uow.ledger.chart())
    uow.ledger.post(back, reverses=original.number)
    event(
        _log,
        "settlement.reversed",
        settlement=settlement_number,
        entry=back.number,
        reverses=original.number,
        posted_on=str(posted_on),
    )
    return back


def unposted(
    uow: UnitOfWork, settlements: Mapping[str, Settlement]
) -> tuple[str, ...]:
    """Which of these statements have never reached the journal."""
    return tuple(
        number
        for number in sorted(settlements)
        if not uow.ledger.is_posted(SOURCE, number)
    )
