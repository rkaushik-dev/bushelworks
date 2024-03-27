"""The scale ticket.

This is the document everything else refers back to. It records what came off
the truck, who it belongs to and what the producer wants done with it. It is
immutable once written: a scale house that lets a ticket be edited after the
fact cannot answer the only question an auditor ever asks, which is what the
scale said at the time.

Corrections are made by voiding and reissuing, never by amendment.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum
from typing import Mapping, Sequence

from ..units import net_pounds, percent_of, pounds_to_bushels, quantize
from ..units import BUSHEL_PLACES, POUND_PLACES
from .commodity import Commodity, Factor
from ..errors import ValidationError


class Disposition(str, Enum):
    """What the producer asked for at the window."""

    CASH_SALE = "cash_sale"
    STORE = "store"
    PRICE_LATER = "price_later"
    CONTRACT = "contract"
    FEED = "feed"
    TRANSFER_IN = "transfer_in"


class SplitRole(str, Enum):
    OWNER = "owner"
    LANDLORD = "landlord"
    TENANT = "tenant"
    LIENHOLDER = "lienholder"


class TicketError(ValidationError):
    """Raised when a ticket cannot stand as written."""


@dataclass(frozen=True, slots=True)
class SplitShare:
    """One party's share of a load, as a percentage of the whole."""

    party_id: str
    percent: Decimal
    role: SplitRole = SplitRole.OWNER

    def __post_init__(self) -> None:
        if not self.party_id:
            raise TicketError("a split share needs a party")
        if self.percent <= 0:
            raise TicketError(f"{self.party_id}: a share must be more than zero")
        if self.percent > Decimal("100"):
            raise TicketError(f"{self.party_id}: a share cannot exceed the whole load")


@dataclass(frozen=True, slots=True)
class ScaleTicket:
    """One load across the scale."""

    ticket_number: str
    scale_date: date
    location_code: str
    commodity_code: str
    gross_pounds: Decimal
    tare_pounds: Decimal
    disposition: Disposition
    splits: Sequence[SplitShare]
    sample: Mapping[Factor, Decimal] = field(default_factory=dict)
    dockage_percent: Decimal = Decimal("0")
    truck_id: str = ""
    driver: str = ""
    farm: str = ""
    field_name: str = ""
    contract_number: str | None = None
    remarks: str = ""
    voided: bool = False

    def __post_init__(self) -> None:
        if not self.ticket_number:
            raise TicketError("a ticket needs a number")
        if not self.splits:
            raise TicketError(f"{self.ticket_number}: a ticket needs at least one share")
        if self.dockage_percent < 0 or self.dockage_percent >= Decimal("100"):
            raise TicketError(f"{self.ticket_number}: dockage out of range")

        seen: set[str] = set()
        for share in self.splits:
            if share.party_id in seen:
                raise TicketError(
                    f"{self.ticket_number}: {share.party_id} appears twice on the split"
                )
            seen.add(share.party_id)

        total = sum((s.percent for s in self.splits), Decimal("0"))
        if total != Decimal("100"):
            raise TicketError(
                f"{self.ticket_number}: shares total {total}%, they have to make 100%"
            )

        if self.disposition is Disposition.CONTRACT and not self.contract_number:
            raise TicketError(
                f"{self.ticket_number}: a contract delivery needs a contract number"
            )

    @property
    def net_pounds(self) -> Decimal:
        """Gross less tare, before any dockage comes off."""
        return net_pounds(self.gross_pounds, self.tare_pounds)

    @property
    def dockage_pounds(self) -> Decimal:
        """The material the cleaner takes out, which the producer is not paid for."""
        if self.dockage_percent == 0:
            return Decimal("0")
        return percent_of(self.net_pounds, self.dockage_percent, POUND_PLACES)

    @property
    def clean_pounds(self) -> Decimal:
        """What is left after dockage. This is what becomes bushels."""
        return quantize(self.net_pounds - self.dockage_pounds, POUND_PLACES)

    def gross_bushels(self, commodity: Commodity) -> Decimal:
        """Bushels before dockage, which is what the driver watches on the board."""
        return pounds_to_bushels(self.net_pounds, commodity.standard_test_weight)

    def clean_bushels(self, commodity: Commodity) -> Decimal:
        """Bushels after dockage and before moisture shrink."""
        return pounds_to_bushels(self.clean_pounds, commodity.standard_test_weight)

    def share_of(self, party_id: str) -> Decimal:
        for share in self.splits:
            if share.party_id == party_id:
                return share.percent
        raise TicketError(f"{self.ticket_number}: {party_id} has no share of this load")

    def parties(self) -> tuple[str, ...]:
        return tuple(share.party_id for share in self.splits)

    def factor(self, factor: Factor) -> Decimal | None:
        value = self.sample.get(factor)
        return None if value is None else Decimal(value)

    def require_factor(self, factor: Factor) -> Decimal:
        value = self.factor(factor)
        if value is None:
            raise TicketError(
                f"{self.ticket_number}: sample has no reading for {factor.value}"
            )
        return value

    def void(self, reason: str) -> "ScaleTicket":
        """Return the voided twin of this ticket.

        Voiding does not erase anything. The original stays in the book and the
        void carries the reason, because the state inspector reads both.
        """
        if self.voided:
            raise TicketError(f"{self.ticket_number}: already voided")
        if not reason:
            raise TicketError(f"{self.ticket_number}: a void needs a reason")
        return ScaleTicket(
            ticket_number=self.ticket_number,
            scale_date=self.scale_date,
            location_code=self.location_code,
            commodity_code=self.commodity_code,
            gross_pounds=self.gross_pounds,
            tare_pounds=self.tare_pounds,
            disposition=self.disposition,
            splits=self.splits,
            sample=self.sample,
            dockage_percent=self.dockage_percent,
            truck_id=self.truck_id,
            driver=self.driver,
            farm=self.farm,
            field_name=self.field_name,
            contract_number=self.contract_number,
            remarks=f"VOID: {reason}",
            voided=True,
        )


def split_bushels(bushels: Decimal, splits: Sequence[SplitShare]) -> dict[str, Decimal]:
    """Divide a bushel count across the shares.

    Bushels are split at four decimals and the residue from rounding is given to
    the largest share, which keeps the parts adding to the whole without ever
    giving a minority owner more than the split says.
    """
    if not splits:
        raise TicketError("cannot split across no shares")
    whole = quantize(Decimal(bushels), BUSHEL_PLACES)
    parts: dict[str, Decimal] = {}
    for share in splits:
        parts[share.party_id] = percent_of(whole, share.percent, BUSHEL_PLACES)
    residue = whole - sum(parts.values(), Decimal("0"))
    if residue != 0:
        biggest = max(splits, key=lambda s: (s.percent, s.party_id))
        parts[biggest.party_id] = quantize(
            parts[biggest.party_id] + residue, BUSHEL_PLACES
        )
    return parts
