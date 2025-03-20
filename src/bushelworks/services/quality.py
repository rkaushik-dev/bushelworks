"""Taking a complaint, working it, and closing it.

The domain register is an in memory list that answers questions about a whole
season. The table is the season. So this maps one to the other in both
directions, and does the two things the table cannot do on its own: refuse a
complaint that contradicts the record, and say whether the house can defend it.

Closing is where the rules are. A rejected complaint carries no credit, a
complaint cannot close before it was raised, and a complaint that is already
closed stays closed. All three of those are in the domain object, so closing
goes through it rather than writing the columns directly.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import date
from decimal import Decimal

from ..domain.complaints import (
    NORMAL_WINDOW,
    Complaint,
    ComplaintError,
    Outcome,
    Register,
    Source,
    by_source,
)
from ..money import amount
from ..persistence.base import as_decimal
from ..persistence.models import ComplaintRow
from ..persistence.unitofwork import UnitOfWork

# How long a retained sample stays on the shelf before it is thrown out.
RETENTION_DAYS = 45


def to_domain(row: ComplaintRow) -> Complaint:
    return Complaint(
        reference=row.reference,
        source=Source(row.source),
        raised_on=row.raised_on,
        delivered_on=row.delivered_on,
        subject=row.subject,
        party_code=row.party_code,
        bin_id=row.bin_id,
        ticket_number=row.ticket_number,
        sale_number=row.sale_number,
        probe_id=row.probe_id,
        outcome=Outcome(row.outcome),
        credit=as_decimal(row.credit),
        closed_on=row.closed_on,
        note=row.note,
    )


def to_row(complaint: Complaint) -> ComplaintRow:
    return ComplaintRow(
        reference=complaint.reference,
        source=complaint.source.value,
        subject=complaint.subject,
        raised_on=complaint.raised_on,
        delivered_on=complaint.delivered_on,
        party_code=complaint.party_code,
        bin_id=complaint.bin_id,
        ticket_number=complaint.ticket_number,
        sale_number=complaint.sale_number,
        probe_id=complaint.probe_id,
        outcome=complaint.outcome.value,
        credit=amount(complaint.credit),
        closed_on=complaint.closed_on,
        note=complaint.note,
    )


def file_complaint(
    uow: UnitOfWork,
    reference: str,
    source: Source | str,
    subject: str,
    raised_on: date,
    delivered_on: date,
    party_code: str | None = None,
    bin_id: str | None = None,
    ticket_number: str | None = None,
    sale_number: str | None = None,
    probe_id: str | None = None,
) -> ComplaintRow:
    """Take a complaint.

    A named ticket is checked against the ticket file. Getting that wrong is
    common because the number is read out over a bad telephone line, and a
    complaint filed against a ticket that does not exist is one nobody can work.
    """
    complaint = Complaint(
        reference=reference,
        source=Source(source),
        raised_on=raised_on,
        delivered_on=delivered_on,
        subject=subject,
        party_code=party_code,
        bin_id=bin_id,
        ticket_number=ticket_number,
        sale_number=sale_number,
        probe_id=probe_id,
    )
    if ticket_number is not None:
        _check_ticket(uow, ticket_number, delivered_on)
    return uow.complaints.add(to_row(complaint))


def _check_ticket(uow: UnitOfWork, ticket_number: str, delivered_on: date) -> None:
    rows = [
        row
        for row in uow.tickets.by_number(ticket_number)
        if not row.voided
    ]
    if not rows:
        raise ComplaintError(f"no live ticket {ticket_number}")
    if all(row.scale_date > delivered_on for row in rows):
        raise ComplaintError(
            f"ticket {ticket_number} was weighed after {delivered_on}"
        )


def act(
    uow: UnitOfWork, reference: str, taken_on: date, taken_by: str, what: str
) -> None:
    """Record what was done, so the file reads as a sequence and not a note."""
    uow.complaints.act(reference, taken_on, taken_by, what)


def close(
    uow: UnitOfWork,
    reference: str,
    outcome: Outcome | str,
    on: date,
    credit: Decimal = Decimal("0.00"),
    note: str = "",
) -> ComplaintRow:
    """Close a complaint through the domain object so the rules apply."""
    row = uow.complaints.row(reference)
    closed = to_domain(row).close(Outcome(outcome), on, credit, note)
    row.outcome = closed.outcome.value
    row.closed_on = closed.closed_on
    row.credit = closed.credit
    row.note = closed.note
    uow.flush()
    return row


def register_between(uow: UnitOfWork, opens: date, closes: date) -> Register:
    """Build the whole season in memory, for the questions that need it."""
    register = Register()
    for row in uow.complaints.between(opens, closes):
        register.add(to_domain(row))
    return register


def open_complaints(uow: UnitOfWork, bin_id: str | None = None) -> list[Complaint]:
    return [to_domain(row) for row in uow.complaints.open_complaints(bin_id)]


def indefensible(uow: UnitOfWork, opens: date, closes: date) -> list[Complaint]:
    """Open complaints with no sample left to argue from.

    These are the ones to settle rather than fight, and knowing which they are
    in the first week saves a fortnight of correspondence that ends the same way.
    """
    register = register_between(uow, opens, closes)
    return list(register.indefensible(RETENTION_DAYS))


def repeat_bins(
    uow: UnitOfWork, opens: date, closes: date, threshold: int = 3
) -> list[str]:
    """Bins that have attracted more complaints than the house is happy with.

    Counted in the database rather than by building the register, because this
    is the one question that gets asked over a five year span.
    """
    counts = uow.complaints.bin_counts(opens, closes)
    return sorted(bin_id for bin_id, count in counts.items() if count >= threshold)


def summary(uow: UnitOfWork, opens: date, closes: date) -> dict[str, object]:
    """The page the manager looks at once a month."""
    register = register_between(uow, opens, closes)
    return {
        "opens": opens,
        "closes": closes,
        "count": len(register.complaints),
        "open": len(register.open_complaints()),
        "late": len([item for item in register.complaints if item.is_late]),
        "upheld_rate": register.upheld_rate(),
        "credits": register.credits(),
        "by_source": by_source(register.complaints),
        "indefensible": [item.reference for item in register.indefensible(RETENTION_DAYS)],
        "repeat_bins": register.repeat_bins(),
        "window_days": NORMAL_WINDOW.days,
    }


def credits_between(uow: UnitOfWork, opens: date, closes: date) -> Decimal:
    return uow.complaints.credits_between(opens, closes)


def references(complaints: Iterable[Complaint]) -> tuple[str, ...]:
    return tuple(item.reference for item in complaints)
