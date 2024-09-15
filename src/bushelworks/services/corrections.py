"""Putting right what was written down wrong.

Nothing in this package deletes anything. A correction is a second document that
says what the first one got wrong, and both stay on file. That is not
bookkeeping fussiness: the state inspector reads the pair, and a system that can
make a ticket disappear cannot prove anything about the ones that are left.

Three kinds of correction, in the order they are usually needed:

* **unapply** takes bushels back off a contract, because the driver named the
  wrong one. The ticket does not move.
* **void and reissue** replaces a ticket whose weights were wrong. The void
  keeps the original weights and the reissue carries a new number.
* **reverse a settlement** writes a settlement with the opposite sign and
  releases its tickets. It never edits the original.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..domain.settlement import LineKind, Settlement, SettlementBuilder
from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event, get_logger, warn
from ..persistence.base import as_decimal
from ..persistence.models import (
    ApplicationRow,
    ScaleTicketRow,
    SettlementRow,
    SettlementTicketRow,
)
from ..persistence.unitofwork import UnitOfWork
from . import audit
from .numbering import Numbering

_log = get_logger("corrections")

REVERSAL_SERIES = "SR"


class CorrectionError(ValidationError):
    """Raised when a correction cannot be made as asked."""

    code = "correction_error"


@dataclass(frozen=True, slots=True)
class Unapplied:
    """Bushels taken back off a contract."""

    ticket_number: str
    contract_number: str
    bushels: Decimal
    reversed_on: date


def unapply(
    uow: UnitOfWork,
    location_code: str,
    ticket_number: str,
    contract_number: str,
    reversed_on: date,
    reason: str,
    actor: str = audit.SYSTEM,
) -> Unapplied:
    """Take a ticket back off a contract without touching the ticket."""
    if not reason:
        raise CorrectionError(f"{ticket_number}: an unapply needs a reason")

    ticket = uow.tickets.row(location_code, ticket_number)
    if ticket.settled:
        raise ConflictError(
            f"{ticket_number} has been settled and cannot be unapplied"
        )
    contract = uow.contracts.row(contract_number)
    application = uow.session.scalar(
        select(ApplicationRow).where(
            ApplicationRow.ticket_id == ticket.id,
            ApplicationRow.contract_id == contract.id,
            ApplicationRow.reversed_on.is_(None),
        )
    )
    if application is None:
        raise LookupFailure(
            f"{ticket_number} is not applied to {contract_number}"
        )

    application.reversed_on = reversed_on
    if contract.status == "filled":
        contract.status = "open"
    uow.flush()

    audit.record(
        uow,
        audit.Action.REVERSED,
        audit.Document.CONTRACT,
        contract_number,
        actor=actor,
        party_code=ticket.party_code,
        location_code=location_code,
        effective_on=reversed_on,
        detail=f"{ticket_number} unapplied: {reason}",
    )
    bushels = as_decimal(application.bushels)
    event(
        _log,
        "application.reversed",
        ticket=ticket_number,
        contract=contract_number,
        bushels=bushels,
    )
    return Unapplied(
        ticket_number=ticket_number,
        contract_number=contract_number,
        bushels=bushels,
        reversed_on=reversed_on,
    )


def void_and_reissue(
    uow: UnitOfWork,
    location_code: str,
    ticket_number: str,
    replacement_number: str,
    gross_pounds: Decimal,
    tare_pounds: Decimal,
    reason: str,
    actor: str = audit.SYSTEM,
) -> ScaleTicketRow:
    """Void a ticket and write its replacement with corrected weights."""
    if not reason:
        raise CorrectionError(f"{ticket_number}: a reissue needs a reason")
    if gross_pounds < tare_pounds:
        raise CorrectionError(
            f"{replacement_number}: tare {tare_pounds} exceeds gross {gross_pounds}"
        )

    original = uow.tickets.row(location_code, ticket_number)
    if original.settled:
        raise ConflictError(f"{ticket_number} has been settled and cannot be reissued")
    if uow.tickets.exists(location_code, replacement_number):
        raise ConflictError(
            f"{replacement_number} is already written at {location_code}"
        )

    replacement = ScaleTicketRow(
        ticket_number=replacement_number,
        location_code=original.location_code,
        scale_date=original.scale_date,
        commodity_code=original.commodity_code,
        party_code=original.party_code,
        gross_pounds=gross_pounds,
        tare_pounds=tare_pounds,
        dockage_percent=original.dockage_percent,
        disposition=original.disposition,
        contract_number=original.contract_number,
        truck_id=original.truck_id,
        driver=original.driver,
        farm=original.farm,
        field_name=original.field_name,
        remarks=f"reissue of {ticket_number}: {reason}",
    )
    replacement.samples = [
        type(sample)(factor=sample.factor, value=sample.value, instrument=sample.instrument)
        for sample in original.samples
    ]
    replacement.splits = [
        type(split)(
            party_code=split.party_code, percent=split.percent, role=split.role
        )
        for split in original.splits
    ]
    uow.session.add(replacement)
    uow.tickets.void(location_code, ticket_number, f"reissued as {replacement_number}")
    uow.flush()

    audit.record(
        uow,
        audit.Action.VOIDED,
        audit.Document.TICKET,
        ticket_number,
        actor=actor,
        party_code=original.party_code,
        location_code=location_code,
        effective_on=original.scale_date,
        detail=f"reissued as {replacement_number}: {reason}",
    )
    warn(
        _log,
        "ticket.reissued",
        ticket=ticket_number,
        replacement=replacement_number,
        reason=reason,
    )
    return replacement


def reverse_settlement(
    uow: UnitOfWork,
    number: str,
    reversed_on: date,
    reason: str,
    actor: str = audit.SYSTEM,
) -> Settlement:
    """Write the opposite of a settlement and free its tickets."""
    if not reason:
        raise CorrectionError(f"{number}: a reversal needs a reason")

    original_row = uow.settlements.row(number)
    if original_row.voided:
        raise ConflictError(f"{number} has already been reversed")
    if original_row.paid_on is not None:
        raise ConflictError(
            f"{number} was paid on {original_row.paid_on} by cheque "
            f"{original_row.cheque_number} and has to be voided at the bank first"
        )

    original = uow.settlements.get(number)
    reversal_number = str(Numbering(uow.session).allocate(REVERSAL_SERIES, reversed_on))
    builder = SettlementBuilder(
        reversal_number, reversed_on, original.party_id, original.location_code
    )
    for line in original.lines:
        # A gross line becomes a deduction and a deduction becomes gross, so
        # the reversal's net is the original's with the sign turned over.
        kind = LineKind.DEDUCTION if line.kind is LineKind.GROSS else LineKind.GROSS
        builder.add_charge(
            kind,
            line.code,
            f"reversal of {number}: {line.description}",
            line.dollars,
            quantity=line.quantity,
            rate=line.rate,
        )
    for ticket_number in original.ticket_numbers:
        builder.reference_ticket(ticket_number)

    reversal = builder.build()
    uow.settlements.add(reversal)
    original_row.voided = True

    ticket_ids = uow.session.scalars(
        select(SettlementTicketRow.ticket_id).where(
            SettlementTicketRow.settlement_id == original_row.id
        )
    )
    for ticket_id in ticket_ids:
        ticket = uow.session.get(ScaleTicketRow, ticket_id)
        if ticket is not None:
            ticket.settled = False
    uow.flush()

    audit.record(
        uow,
        audit.Action.REVERSED,
        audit.Document.SETTLEMENT,
        number,
        actor=actor,
        party_code=original.party_id,
        amount=reversal.net,
        effective_on=reversed_on,
        detail=f"{reversal_number}: {reason}",
    )
    event(
        _log,
        "settlement.reversed",
        original=number,
        reversal=reversal_number,
        net=reversal.net,
    )
    return reversal


def reversals_for(uow: UnitOfWork, party_code: str) -> list[SettlementRow]:
    """Every reversal written against one producer, newest first."""
    return list(
        uow.session.scalars(
            select(SettlementRow)
            .where(
                SettlementRow.party_code == party_code,
                SettlementRow.number.like(f"{REVERSAL_SERIES}-%"),
            )
            .order_by(SettlementRow.settled_on.desc(), SettlementRow.number.desc())
        )
    )
