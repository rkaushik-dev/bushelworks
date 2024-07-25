"""Writing and reading the audit trail.

The trail is append only and this module is the only way in. There is no update
and no delete, and the read side is deliberately narrow: by document, by
producer, by day. Anything wider than that is a report, and a report should not
be able to reach the evidence table directly.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import func, select

from ..errors import ValidationError
from ..logging import current_request_id
from ..persistence.models.audit import AuditRow
from ..persistence.unitofwork import UnitOfWork

SYSTEM = "system"


class AuditError(ValidationError):
    """Raised when an audit entry cannot be written as asked."""

    code = "audit_error"


class Document:
    """The kinds of document the trail knows about."""

    TICKET = "ticket"
    CONTRACT = "contract"
    SETTLEMENT = "settlement"
    STORAGE_LOT = "storage_lot"
    RECEIPT = "warehouse_receipt"
    PAYMENT = "payment"


class Action:
    """The verbs. New ones are added here rather than typed at the call site."""

    RECORDED = "recorded"
    VOIDED = "voided"
    AMENDED = "amended"
    PRICED = "priced"
    APPLIED = "applied"
    REVERSED = "reversed"
    SETTLED = "settled"
    PAID = "paid"
    ISSUED = "issued"
    CANCELLED = "cancelled"
    ACCRUED = "accrued"


@dataclass(frozen=True, slots=True)
class Entry:
    """One audit row, read back."""

    recorded_at: str
    actor: str
    action: str
    document_kind: str
    document_number: str
    party_code: str | None
    amount: Decimal | None
    detail: str | None


def record(
    uow: UnitOfWork,
    action: str,
    document_kind: str,
    document_number: str,
    actor: str = SYSTEM,
    party_code: str | None = None,
    location_code: str | None = None,
    amount: Decimal | None = None,
    detail: str | None = None,
    effective_on: date | None = None,
) -> AuditRow:
    """Write one entry inside the caller's transaction.

    Inside the caller's transaction on purpose. An audit row that survives a
    rolled back settlement is worse than no audit row, because it says something
    happened that did not.
    """
    if not action:
        raise AuditError("an audit entry needs an action")
    if not document_number:
        raise AuditError(f"{action}: an audit entry needs a document number")
    row = AuditRow(
        action=action,
        document_kind=document_kind,
        document_number=document_number,
        actor=actor or SYSTEM,
        party_code=party_code,
        location_code=location_code,
        amount=amount,
        detail=detail[:400] if detail else None,
        effective_on=effective_on,
        request_id=current_request_id(),
    )
    uow.session.add(row)
    uow.flush()
    return row


def _to_entry(row: AuditRow) -> Entry:
    return Entry(
        recorded_at=row.recorded_at.isoformat(),
        actor=row.actor,
        action=row.action,
        document_kind=row.document_kind,
        document_number=row.document_number,
        party_code=row.party_code,
        amount=row.amount,
        detail=row.detail,
    )


def for_document(
    uow: UnitOfWork, document_kind: str, document_number: str
) -> list[Entry]:
    """Everything that ever happened to one document, oldest first."""
    rows = uow.session.scalars(
        select(AuditRow)
        .where(
            AuditRow.document_kind == document_kind,
            AuditRow.document_number == document_number,
        )
        .order_by(AuditRow.recorded_at, AuditRow.id)
    )
    return [_to_entry(row) for row in rows]


def for_party(uow: UnitOfWork, party_code: str, limit: int = 200) -> list[Entry]:
    rows = uow.session.scalars(
        select(AuditRow)
        .where(AuditRow.party_code == party_code)
        .order_by(AuditRow.recorded_at.desc(), AuditRow.id.desc())
        .limit(limit)
    )
    return [_to_entry(row) for row in rows]


def for_day(uow: UnitOfWork, day: date, action: str | None = None) -> list[Entry]:
    query = select(AuditRow).where(func.date(AuditRow.recorded_at) == day.isoformat())
    if action:
        query = query.where(AuditRow.action == action)
    rows = uow.session.scalars(query.order_by(AuditRow.recorded_at, AuditRow.id))
    return [_to_entry(row) for row in rows]


def count(uow: UnitOfWork) -> int:
    return int(uow.session.scalar(select(func.count()).select_from(AuditRow)) or 0)


def last_action(
    uow: UnitOfWork, document_kind: str, document_number: str
) -> Entry | None:
    """What most recently happened to a document, or nothing if it is new."""
    entries = for_document(uow, document_kind, document_number)
    return entries[-1] if entries else None
