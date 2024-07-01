"""Turn graded loads into a statement and a net.

Intake stops at bushels. This is where the price arrives and the order of
operations starts to matter. The sequence is fixed and enforced by the builder:

* gross is the settled bushels at the contract or spot price
* quality discounts come off that
* drying, storage and service charges come off next, and they are services
  rather than quality, which is why an assessment is not levied on them
* assessments sit on the value after discounts but before services
* advances are recovered last

The one decision this module refuses to make on its own is which bushels the
discounts extend over. That comes in as an argument because it is a filed
policy, not an arithmetic fact.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..domain.rulebook import RuleBook
from ..domain.settlement import LineKind, Settlement, SettlementBuilder
from ..errors import ValidationError
from ..money import amount, extend
from .intake import GradedLoad, drying_charge


class DiscountBasis(str, Enum):
    """Which bushel count quality discounts are extended over."""

    WET = "wet"
    DRY = "dry"


class SettlementRefused(ValidationError):
    """Raised when a settlement cannot be produced from what is on hand."""

    code = "settlement_refused"


@dataclass(frozen=True, slots=True)
class Advance:
    """Money already paid against grain not yet settled."""

    reference: str
    dollars: Decimal
    advanced_on: date

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise SettlementRefused(f"{self.reference}: an advance has to be positive")


@dataclass(frozen=True, slots=True)
class StorageCharge:
    """A storage bill already worked out by the storage service."""

    lot_id: str
    dollars: Decimal
    through: date


def settle_load(
    number: str,
    load: GradedLoad,
    unit_price: Decimal,
    rules: RuleBook,
    party_id: str,
    settled_on: date,
    discount_basis: DiscountBasis = DiscountBasis.DRY,
    advances: Sequence[Advance] = (),
    storage: Sequence[StorageCharge] = (),
    exempt_assessments: Sequence[str] = (),
    contract_number: str | None = None,
) -> Settlement:
    """Build the statement for one load at one price."""
    if unit_price < 0:
        raise SettlementRefused(f"{number}: cannot settle at a negative price")
    if load.settled_bushels <= 0:
        raise SettlementRefused(f"{number}: nothing to settle on {load.ticket_number}")

    builder = SettlementBuilder(number, settled_on, party_id, load.location_code)
    builder.reference_ticket(load.ticket_number)
    if contract_number:
        builder.reference_contract(contract_number)

    builder.add_gross(
        "GRAIN",
        f"{load.commodity_code} {load.settled_bushels} bu at {unit_price}",
        load.settled_bushels,
        unit_price,
    )

    discount_bushels = (
        load.dry_bushels if discount_basis is DiscountBasis.DRY else load.clean_bushels
    )
    for factor, rate in sorted(load.discount_detail.items(), key=lambda kv: kv[0].value):
        builder.add_charge(
            LineKind.DISCOUNT,
            factor.value.upper(),
            f"{factor.value.replace('_', ' ')} discount",
            extend(discount_bushels, rate),
            quantity=discount_bushels,
            rate=rate,
        )

    drying = drying_charge(load, rules, on_dry_bushels=discount_basis is DiscountBasis.DRY)
    if drying:
        builder.add_charge(
            LineKind.DRYING,
            "DRY",
            f"drying {load.moisture_points} points",
            drying,
            quantity=load.moisture_points,
        )

    for charge in storage:
        builder.add_charge(
            LineKind.STORAGE,
            f"STOR-{charge.lot_id}",
            f"storage on {charge.lot_id} through {charge.through}",
            charge.dollars,
        )

    for line in rules.assessments.withhold(
        commodity_code=load.commodity_code,
        state_code=rules.policy.state_code,
        bushels=load.settled_bushels,
        gross_value=builder.running_gross,
        net_value=amount(builder.running_gross - builder.running_discounts),
        exempt_codes=exempt_assessments,
    ):
        builder.add_charge(LineKind.ASSESSMENT, line.code, line.name, line.dollars)

    for advance in advances:
        builder.add_charge(
            LineKind.ADVANCE,
            f"ADV-{advance.reference}",
            f"advance of {advance.advanced_on}",
            advance.dollars,
        )

    settlement = builder.build()
    if settlement.net < rules.policy.minimum_settlement:
        raise SettlementRefused(
            f"{number}: net {settlement.net} is below the house minimum "
            f"{rules.policy.minimum_settlement}"
        )
    return settlement
