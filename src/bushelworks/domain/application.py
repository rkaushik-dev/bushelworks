"""Applying delivered bushels against open contracts.

A load arrives and something has to decide which contract it fills. When the
driver names a contract number there is nothing to decide. When he does not,
and most of them do not, the house applies a policy, and the policy it picks
changes what the producer is paid rather than merely which row is written.

Three selection rules are in use and all three are defensible:

* ``WINDOW`` fills the contract whose delivery window closes first, which keeps
  the house out of trouble with the terminal.
* ``OLDEST`` fills the contract written first.
* ``BEST_PRICE`` fills the highest priced contract, which is the rule a producer
  assumes is in force whether or not anyone told him it is.

What happens past the last contract is a second, separate decision, and the two
are deliberately not folded together: a house can be strict about which contract
fills first and generous about what happens to the overage.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum
from typing import Iterable, Sequence

from ..units import BUSHEL_PLACES, quantize
from .contracts import ContractError, ContractStatus, PurchaseContract


class SelectionRule(str, Enum):
    WINDOW = "window"
    OLDEST = "oldest"
    BEST_PRICE = "best_price"


class OverDelivery(str, Enum):
    """What to do with bushels past the last open contract."""

    REFUSE = "refuse"
    SPOT = "spot"
    STORE = "store"


class ApplicationError(ValueError):
    """Raised when a delivery cannot be applied as asked."""


@dataclass(frozen=True, slots=True)
class Application:
    """One slice of a ticket landing on one contract."""

    ticket_number: str
    contract_number: str | None
    bushels: Decimal
    applied_on: date
    disposition: str = "contract"

    def __post_init__(self) -> None:
        if self.bushels <= 0:
            raise ApplicationError(
                f"{self.ticket_number}: cannot apply {self.bushels} bushels"
            )


class ApplicationLedger:
    """How much has landed on each contract so far."""

    def __init__(self) -> None:
        self._applied: dict[str, Decimal] = {}

    def applied(self, contract_number: str) -> Decimal:
        return self._applied.get(contract_number, Decimal("0.0000"))

    def record(self, application: Application) -> None:
        if application.contract_number is None:
            return
        current = self.applied(application.contract_number)
        self._applied[application.contract_number] = quantize(
            current + application.bushels, BUSHEL_PLACES
        )

    def remaining(self, contract: PurchaseContract) -> Decimal:
        """Bushels the contract can still take, up to its tolerance ceiling."""
        room = contract.maximum_bushels - self.applied(contract.number)
        return room if room > 0 else Decimal("0.0000")

    def is_filled(self, contract: PurchaseContract) -> bool:
        """Filled means inside tolerance, not exactly on the number.

        A twenty thousand bushel contract with a two percent tolerance is filled
        at 19,600. The house does not chase the last four hundred bushels and
        the producer is not in default for them.
        """
        return self.applied(contract.number) >= contract.minimum_bushels

    def shortfall(self, contract: PurchaseContract) -> Decimal:
        gap = contract.minimum_bushels - self.applied(contract.number)
        return gap if gap > 0 else Decimal("0.0000")

    def contracts_touched(self) -> tuple[str, ...]:
        return tuple(sorted(self._applied))


def eligible(
    contracts: Iterable[PurchaseContract],
    commodity_code: str,
    party_id: str,
    day: date,
    ledger: ApplicationLedger,
) -> list[PurchaseContract]:
    """Open contracts this delivery could legally land on."""
    out = []
    for contract in contracts:
        if contract.status is not ContractStatus.OPEN:
            continue
        if contract.commodity_code != commodity_code:
            continue
        if contract.party_id != party_id:
            continue
        if not contract.window.contains(day):
            continue
        if ledger.remaining(contract) <= 0:
            continue
        out.append(contract)
    return out


def order_for(rule: SelectionRule, contracts: Sequence[PurchaseContract]) -> list[PurchaseContract]:
    """Put candidate contracts in the order the rule wants them filled."""
    if rule is SelectionRule.WINDOW:
        return sorted(contracts, key=lambda c: (c.window.closes, c.number))
    if rule is SelectionRule.OLDEST:
        return sorted(contracts, key=lambda c: (c.written_on, c.number))
    if rule is SelectionRule.BEST_PRICE:
        return sorted(
            contracts,
            key=lambda c: (-_price_or_zero(c), c.number),
        )
    raise ApplicationError(f"no ordering for selection rule {rule}")


def _price_or_zero(contract: PurchaseContract) -> Decimal:
    """An unpriced contract sorts last under BEST_PRICE.

    It has no price to compare, and filling it early would hand the producer a
    number nobody has agreed to yet.
    """
    try:
        return contract.unit_price
    except ContractError:
        return Decimal("0")


def apply_delivery(
    ticket_number: str,
    bushels: Decimal,
    contracts: Sequence[PurchaseContract],
    ledger: ApplicationLedger,
    day: date,
    rule: SelectionRule = SelectionRule.WINDOW,
    over_delivery: OverDelivery = OverDelivery.STORE,
) -> list[Application]:
    """Split a delivery across contracts and say what happened to the rest."""
    remaining = quantize(Decimal(bushels), BUSHEL_PLACES)
    if remaining <= 0:
        raise ApplicationError(f"{ticket_number}: nothing to apply")

    applications: list[Application] = []
    for contract in order_for(rule, contracts):
        if remaining <= 0:
            break
        room = ledger.remaining(contract)
        if room <= 0:
            continue
        take = min(room, remaining)
        application = Application(
            ticket_number=ticket_number,
            contract_number=contract.number,
            bushels=quantize(take, BUSHEL_PLACES),
            applied_on=day,
        )
        ledger.record(application)
        applications.append(application)
        remaining = quantize(remaining - take, BUSHEL_PLACES)

    if remaining > 0:
        applications.append(_handle_overage(ticket_number, remaining, day, over_delivery))
    return applications


def _handle_overage(
    ticket_number: str, bushels: Decimal, day: date, policy: OverDelivery
) -> Application:
    if policy is OverDelivery.REFUSE:
        raise ApplicationError(
            f"{ticket_number}: {bushels} bushels over contract and the house refuses it"
        )
    return Application(
        ticket_number=ticket_number,
        contract_number=None,
        bushels=bushels,
        applied_on=day,
        disposition="spot" if policy is OverDelivery.SPOT else "store",
    )
