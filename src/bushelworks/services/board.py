"""The price board.

What the sign by the driveway says, and what the telephone answers. A bid is
posted for a commodity, a delivery slot and a site, and it stands until it is
replaced or the day ends. Nothing here prices a ticket: a bid is an offer and a
contract is what a ticket settles on.

Bids are kept, not overwritten. The producer who says he was told four seventy
two at ten past nine is usually right, and the only way to find out is to have
the nine o'clock board still on file.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from decimal import Decimal

from ..domain.pricing import CashBid, FuturesMonth, PricingError
from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event, get_logger
from ..money import PRICE_PLACES, price
from ..units import quantize

_log = get_logger("board")

# How far a new bid may move from the last one before the desk has to confirm
# it. A fat finger on the futures line is the common cause and it is expensive.
MOVE_GUARD = Decimal("0.50")


class BoardError(ValidationError):
    """Raised when a bid cannot be posted."""

    code = "board_error"


class NoBid(LookupFailure):
    code = "no_bid"


@dataclass(frozen=True, slots=True)
class Slot:
    """What a bid is for: a commodity, a delivery period and a site."""

    commodity_code: str
    period: str
    location_code: str = "*"

    def __post_init__(self) -> None:
        if not self.commodity_code:
            raise BoardError("a slot needs a commodity")
        if not self.period:
            raise BoardError("a slot needs a delivery period")

    @property
    def label(self) -> str:
        return f"{self.commodity_code} {self.period} {self.location_code}"


@dataclass(frozen=True, slots=True)
class Posting:
    """One bid as it went up, with when and by whom."""

    slot: Slot
    bid: CashBid
    posted_at: datetime
    posted_by: str

    @property
    def cash(self) -> Decimal:
        return self.bid.cash

    @property
    def basis(self) -> Decimal:
        return price(self.bid.basis)


class Board:
    """Every bid the house has posted, newest last."""

    def __init__(self) -> None:
        self._postings: list[Posting] = []

    def post(
        self,
        slot: Slot,
        month: FuturesMonth,
        futures: Decimal,
        basis: Decimal,
        posted_by: str,
        quoted_on: date | None = None,
        posted_at: datetime | None = None,
        confirm_move: bool = False,
    ) -> Posting:
        """Put a bid up, refusing a wild move unless it is confirmed."""
        if not posted_by:
            raise BoardError(f"{slot.label}: a bid needs somebody's name on it")
        if month.commodity_code != slot.commodity_code:
            raise PricingError(
                f"{month.symbol} is not a {slot.commodity_code} contract"
            )

        bid = CashBid(
            commodity_code=slot.commodity_code,
            month=month,
            futures=price(futures),
            basis=price(basis),
            quoted_on=quoted_on or date.today(),
            location_code=slot.location_code,
        )
        previous = self.latest(slot)
        if previous is not None and not confirm_move:
            move = abs(bid.cash - previous.cash)
            if move > MOVE_GUARD:
                raise ConflictError(
                    f"{slot.label}: {bid.cash} is {move} away from the standing "
                    f"{previous.cash}, confirm the move to post it"
                )

        posting = Posting(
            slot=slot,
            bid=bid,
            posted_at=posted_at or datetime.now(timezone.utc),
            posted_by=posted_by,
        )
        self._postings.append(posting)
        event(
            _log,
            "bid.posted",
            slot=slot.label,
            month=month.symbol,
            futures=bid.futures,
            basis=bid.basis,
            cash=bid.cash,
            by=posted_by,
        )
        return posting

    def latest(self, slot: Slot) -> Posting | None:
        for posting in reversed(self._postings):
            if posting.slot == slot:
                return posting
        return None

    def require(self, slot: Slot) -> Posting:
        posting = self.latest(slot)
        if posting is None:
            raise NoBid(f"nothing posted for {slot.label}")
        return posting

    def as_at(self, slot: Slot, moment: datetime) -> Posting | None:
        """What the board said at a moment, which is what the producer asks."""
        found = [
            posting
            for posting in self._postings
            if posting.slot == slot and posting.posted_at <= moment
        ]
        return found[-1] if found else None

    def history(self, slot: Slot) -> tuple[Posting, ...]:
        return tuple(posting for posting in self._postings if posting.slot == slot)

    def slots(self) -> tuple[Slot, ...]:
        seen: list[Slot] = []
        for posting in self._postings:
            if posting.slot not in seen:
                seen.append(posting.slot)
        return tuple(sorted(seen, key=lambda s: (s.commodity_code, s.period, s.location_code)))

    def current(self) -> dict[Slot, Posting]:
        """The board as it stands, one row per slot."""
        return {slot: self.require(slot) for slot in self.slots()}

    def move_since(self, slot: Slot, moment: datetime) -> Decimal:
        """How far a bid has moved since a moment, for the day sheet."""
        then = self.as_at(slot, moment)
        now = self.latest(slot)
        if then is None or now is None:
            raise NoBid(f"nothing posted for {slot.label}")
        return quantize(now.cash - then.cash, PRICE_PLACES)


@dataclass
class DaySheet:
    """The board at open and at close, for one day."""

    day: date
    opened: dict[Slot, Decimal] = field(default_factory=dict)
    closed: dict[Slot, Decimal] = field(default_factory=dict)

    def record_open(self, slot: Slot, cash: Decimal) -> None:
        self.opened[slot] = price(cash)

    def record_close(self, slot: Slot, cash: Decimal) -> None:
        if slot not in self.opened:
            raise BoardError(f"{slot.label} closed without opening")
        self.closed[slot] = price(cash)

    def change(self, slot: Slot) -> Decimal:
        if slot not in self.closed:
            raise NoBid(f"{slot.label} has not closed")
        return quantize(self.closed[slot] - self.opened[slot], PRICE_PLACES)

    def movers(self, threshold: Decimal = Decimal("0.10")) -> list[tuple[Slot, Decimal]]:
        """Slots that moved more than the threshold, biggest first."""
        found = [
            (slot, self.change(slot))
            for slot in self.closed
            if abs(self.change(slot)) >= threshold
        ]
        return sorted(found, key=lambda pair: -abs(pair[1]))
