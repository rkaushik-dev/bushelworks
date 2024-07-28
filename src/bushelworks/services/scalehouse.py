"""What happens between the driver pulling off the scale and the ticket printing.

Recording a load is four writes and one decision, and the decision is which
contracts it lands on. The order matters because the application ledger has to
be built from what is already on file before this ticket is added to it, or a
load applies against itself.

Everything here takes a unit of work and writes inside it. Nothing commits.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..domain import commodity as cm
from ..domain.application import (
    Application,
    ApplicationLedger,
    apply_delivery,
    eligible,
)
from ..domain.rulebook import RuleBook
from ..domain.tickets import Disposition, ScaleTicket
from ..errors import ConflictError, ValidationError
from ..logging import event, get_logger, warn
from ..persistence.models import ApplicationRow
from ..persistence.unitofwork import UnitOfWork
from . import audit
from .intake import GradedLoad, receive

_log = get_logger("scalehouse")


class ScaleHouseError(ValidationError):
    """Raised when a load cannot be taken in as presented."""

    code = "scale_house_error"


@dataclass(frozen=True, slots=True)
class RecordedLoad:
    """The result of taking one load in."""

    ticket_id: int
    ticket_number: str
    load: GradedLoad
    applications: tuple[Application, ...]

    @property
    def contracted_bushels(self) -> Decimal:
        return sum(
            (a.bushels for a in self.applications if a.contract_number is not None),
            Decimal("0"),
        )

    @property
    def open_bushels(self) -> Decimal:
        return sum(
            (a.bushels for a in self.applications if a.contract_number is None),
            Decimal("0"),
        )

    @property
    def contract_numbers(self) -> tuple[str, ...]:
        return tuple(
            a.contract_number for a in self.applications if a.contract_number is not None
        )


def record_load(
    uow: UnitOfWork,
    ticket: ScaleTicket,
    party_code: str,
    rules: RuleBook,
    actor: str = audit.SYSTEM,
) -> RecordedLoad:
    """Write the ticket, grade it, and apply it to whatever it fills."""
    if uow.tickets is None or uow.contracts is None:
        raise ConflictError("record_load needs an open unit of work")

    load = receive(ticket, rules)
    row = uow.tickets.add(ticket, party_code)

    applications: list[Application] = []
    if ticket.disposition in (Disposition.CONTRACT, Disposition.CASH_SALE):
        applications = _apply(uow, ticket, load, party_code, rules)

    for application in applications:
        if application.contract_number is None:
            continue
        contract_row = uow.contracts.row(application.contract_number)
        uow.session.add(
            ApplicationRow(
                ticket_id=row.id,
                contract_id=contract_row.id,
                bushels=application.bushels,
                applied_on=application.applied_on,
            )
        )
    uow.flush()

    recorded = RecordedLoad(
        ticket_id=row.id,
        ticket_number=ticket.ticket_number,
        load=load,
        applications=tuple(applications),
    )
    audit.record(
        uow,
        audit.Action.RECORDED,
        audit.Document.TICKET,
        ticket.ticket_number,
        actor=actor,
        party_code=party_code,
        location_code=ticket.location_code,
        effective_on=ticket.scale_date,
        detail=(
            f"{ticket.commodity_code} {load.settled_bushels} bu net, "
            f"grade {load.grade.grade.value}"
        ),
    )
    for application in recorded.applications:
        if application.contract_number is None:
            continue
        audit.record(
            uow,
            audit.Action.APPLIED,
            audit.Document.CONTRACT,
            application.contract_number,
            actor=actor,
            party_code=party_code,
            location_code=ticket.location_code,
            effective_on=ticket.scale_date,
            detail=f"{application.bushels} bu off {ticket.ticket_number}",
        )
    event(
        _log,
        "ticket.recorded",
        ticket=ticket.ticket_number,
        location=ticket.location_code,
        party=party_code,
        commodity=ticket.commodity_code,
        net_pounds=ticket.net_pounds,
        dry_bushels=load.dry_bushels,
        grade=load.grade.grade.value,
        contracts=",".join(recorded.contract_numbers) or None,
        open_bushels=recorded.open_bushels,
    )
    if load.is_sample_grade:
        warn(
            _log,
            "ticket.sample_grade",
            ticket=ticket.ticket_number,
            factors=",".join(f.value for f in load.grade.limiting_factors),
        )
    return recorded


def _apply(
    uow: UnitOfWork,
    ticket: ScaleTicket,
    load: GradedLoad,
    party_code: str,
    rules: RuleBook,
) -> list[Application]:
    contracts = uow.contracts.open_for(party_code, ticket.commodity_code, ticket.scale_date)

    if ticket.contract_number:
        named = [c for c in contracts if c.number == ticket.contract_number]
        if not named:
            raise ScaleHouseError(
                f"{ticket.ticket_number}: contract {ticket.contract_number} is not "
                f"open for {party_code} on {ticket.scale_date}"
            )
        contracts = named

    ledger = ApplicationLedger()
    for contract in contracts:
        applied = uow.contracts.applied_bushels(contract.number)
        if applied:
            ledger.record(
                Application(
                    ticket_number=f"prior:{contract.number}",
                    contract_number=contract.number,
                    bushels=applied,
                    applied_on=ticket.scale_date,
                )
            )

    available = eligible(
        contracts, ticket.commodity_code, party_code, ticket.scale_date, ledger
    )
    if not available:
        return []

    return apply_delivery(
        ticket.ticket_number,
        load.settled_bushels,
        available,
        ledger,
        ticket.scale_date,
        rule=rules.policy.selection_rule,
        over_delivery=rules.policy.over_delivery,
    )


def price_for(
    recorded: RecordedLoad,
    uow: UnitOfWork,
    spot_price: Decimal | None = None,
) -> Decimal:
    """The blended price across whatever this load landed on.

    A load that half fills a forward at 4.62 and half goes to spot at 4.35 is
    paid at neither of those, and the producer is entitled to see the weighted
    number rather than two statements.
    """
    from ..domain.pricing import PricedQuantity, blend

    lots: list[PricedQuantity] = []
    for application in recorded.applications:
        if application.contract_number is None:
            if spot_price is None:
                raise ScaleHouseError(
                    f"{recorded.ticket_number}: {application.bushels} bushels are "
                    "off contract and there is no spot price"
                )
            lots.append(PricedQuantity(application.bushels, spot_price))
            continue
        contract = uow.contracts.get(application.contract_number)
        lots.append(PricedQuantity(application.bushels, contract.unit_price))

    if not lots:
        if spot_price is None:
            raise ScaleHouseError(f"{recorded.ticket_number}: nothing to price")
        return spot_price
    return blend(lots)


def commodities_handled(rules: RuleBook, day: date, location_code: str) -> Sequence[str]:
    """Which commodities this site can settle today, for the touch screen."""
    return tuple(code for code in cm.codes() if rules.covers(code, day, location_code))
