"""Putting grain into storage, taking it out, and billing for the time between.

The accrual run is the piece with teeth. It walks every open lot, works out what
is owed to the date it is asked about, and records how far it has billed. Run it
twice for the same day and the second run must charge nothing, because the
office does run it twice: once on the last of the month and again when somebody
notices the first run went out before the last load was keyed.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..domain.rulebook import RuleBook
from ..domain.storage import StorageRate
from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event, get_logger
from ..money import amount
from ..persistence.base import as_decimal
from ..persistence.models import StorageLotRow, WarehouseReceiptRow, WithdrawalRow
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, quantize

_log = get_logger("warehousing")


class StorageServiceError(ValidationError):
    """Raised when a storage operation cannot be carried out."""

    code = "storage_service_error"


class LotNotFound(LookupFailure):
    code = "lot_not_found"


@dataclass(frozen=True, slots=True)
class AccrualLine:
    """What one lot accrued between two dates."""

    lot_id: str
    party_code: str
    commodity_code: str
    open_bushels: Decimal
    from_day: date | None
    through: date
    dollars: Decimal


@dataclass(frozen=True, slots=True)
class AccrualRun:
    """The result of one pass over the open lots."""

    through: date
    lines: tuple[AccrualLine, ...]

    @property
    def total(self) -> Decimal:
        return amount(sum((line.dollars for line in self.lines), Decimal("0")))

    def for_party(self, party_code: str) -> tuple[AccrualLine, ...]:
        return tuple(line for line in self.lines if line.party_code == party_code)


def open_lots(
    uow: UnitOfWork,
    party_code: str | None = None,
    commodity_code: str | None = None,
    location_code: str | None = None,
) -> list[StorageLotRow]:
    query = select(StorageLotRow).where(StorageLotRow.closed_on.is_(None))
    if party_code:
        query = query.where(StorageLotRow.party_code == party_code)
    if commodity_code:
        query = query.where(StorageLotRow.commodity_code == commodity_code)
    if location_code:
        query = query.where(StorageLotRow.location_code == location_code)
    rows = list(uow.session.scalars(query.order_by(StorageLotRow.stored_on, StorageLotRow.id)))
    return [row for row in rows if row.open_bushels > 0]


def lot(uow: UnitOfWork, lot_id: str) -> StorageLotRow:
    row = uow.session.scalar(select(StorageLotRow).where(StorageLotRow.lot_id == lot_id))
    if row is None:
        raise LotNotFound(f"no storage lot {lot_id}")
    return row


def store(
    uow: UnitOfWork,
    lot_id: str,
    party_code: str,
    commodity_code: str,
    location_code: str,
    bushels: Decimal,
    stored_on: date,
    ticket_id: int | None = None,
    deferred_price: bool = False,
) -> StorageLotRow:
    """Open a storage lot."""
    if bushels <= 0:
        raise StorageServiceError(f"{lot_id}: a lot needs bushels")
    existing = uow.session.scalar(
        select(StorageLotRow).where(StorageLotRow.lot_id == lot_id)
    )
    if existing is not None:
        raise ConflictError(f"storage lot {lot_id} already exists")
    row = StorageLotRow(
        lot_id=lot_id,
        party_code=party_code,
        commodity_code=commodity_code,
        location_code=location_code,
        bushels=quantize(Decimal(bushels), BUSHEL_PLACES),
        stored_on=stored_on,
        ticket_id=ticket_id,
        is_deferred_price=deferred_price,
    )
    uow.session.add(row)
    uow.flush()
    return row


def withdraw(
    uow: UnitOfWork,
    party_code: str,
    commodity_code: str,
    bushels: Decimal,
    withdrawn_on: date,
    reason: str = "settled",
    settlement_number: str | None = None,
    location_code: str | None = None,
) -> list[WithdrawalRow]:
    """Take bushels out of a producer's lots, oldest lot first.

    First in first out, which is what the licence assumes. Drawing the newest
    lot first would leave the oldest bushels accruing storage after they have
    physically gone, and the inspector calls that what it is.
    """
    wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
    if wanted <= 0:
        raise StorageServiceError("nothing to withdraw")

    lots = open_lots(uow, party_code, commodity_code, location_code)
    available = sum((row.open_bushels for row in lots), Decimal("0"))
    if wanted > available:
        raise StorageServiceError(
            f"{party_code} has {available} bushels of {commodity_code} on hand, "
            f"asked for {wanted}"
        )

    written: list[WithdrawalRow] = []
    for row in lots:
        if wanted <= 0:
            break
        take = min(row.open_bushels, wanted)
        if take <= 0:
            continue
        withdrawal = WithdrawalRow(
            bushels=take,
            withdrawn_on=withdrawn_on,
            reason=reason,
            settlement_number=settlement_number,
        )
        # Appended through the relationship rather than written with a foreign
        # key, so the lot's open balance reflects it immediately. Setting the
        # key alone leaves the loaded collection stale and the lot never closes.
        row.withdrawals.append(withdrawal)
        written.append(withdrawal)
        wanted = quantize(wanted - take, BUSHEL_PLACES)
    uow.flush()

    for row in lots:
        if row.open_bushels <= 0 and row.closed_on is None:
            row.closed_on = withdrawn_on
    uow.flush()
    return written


def accrue(
    uow: UnitOfWork,
    rules: RuleBook,
    through: date,
    location_code: str | None = None,
    record: bool = True,
) -> AccrualRun:
    """Charge storage on every open lot up to ``through``.

    Idempotent by ``billed_through``. A lot already billed to that day accrues
    nothing on a second pass, and a lot billed past it is left alone rather than
    credited, because a credit needs somebody to approve it.
    """
    lines: list[AccrualLine] = []
    for row in open_lots(uow, location_code=location_code):
        rate = rules.storage_for(row.commodity_code, row.stored_on, row.location_code)
        if rate is None:
            continue
        if row.billed_through is not None and row.billed_through >= through:
            continue
        dollars = _accrual_between(rate, row, through)
        if dollars <= 0 and row.billed_through is not None:
            continue
        lines.append(
            AccrualLine(
                lot_id=row.lot_id,
                party_code=row.party_code,
                commodity_code=row.commodity_code,
                open_bushels=row.open_bushels,
                from_day=row.billed_through,
                through=through,
                dollars=dollars,
            )
        )
        if record:
            row.billed_through = through
            row.accrued_to_date = amount(as_decimal(row.accrued_to_date) + dollars)
    if record:
        uow.flush()
    run = AccrualRun(through=through, lines=tuple(lines))
    event(
        _log,
        "storage.accrued",
        through=through,
        location=location_code,
        lots=len(run.lines),
        total=run.total,
        recorded=record,
    )
    return run


def _accrual_between(rate: StorageRate, row: StorageLotRow, through: date) -> Decimal:
    """Storage for the period since the lot was last billed.

    Was the difference between two charges measured from receipt, which under
    recovered whenever bushels left the lot between runs. The tariff charges a
    balance for a span of days and that is what this asks for now.
    """
    return rate.charge_between(
        row.open_bushels, row.stored_on, row.billed_through, through
    )


def issue_receipt(
    uow: UnitOfWork,
    receipt_number: str,
    lot_id: str,
    grade: str,
    issued_on: date,
    lienholder: str | None = None,
) -> WarehouseReceiptRow:
    """Write a negotiable receipt against an open lot."""
    row = lot(uow, lot_id)
    if row.open_bushels <= 0:
        raise StorageServiceError(f"{lot_id} is empty and cannot carry a receipt")
    existing = uow.session.scalar(
        select(WarehouseReceiptRow).where(
            WarehouseReceiptRow.lot_row_id == row.id,
            WarehouseReceiptRow.cancelled_on.is_(None),
        )
    )
    if existing is not None:
        raise ConflictError(
            f"{lot_id} already carries live receipt {existing.receipt_number}"
        )
    receipt = WarehouseReceiptRow(
        receipt_number=receipt_number,
        lot_row_id=row.id,
        party_code=row.party_code,
        commodity_code=row.commodity_code,
        location_code=row.location_code,
        bushels=row.open_bushels,
        grade=grade,
        issued_on=issued_on,
        lienholder=lienholder,
    )
    uow.session.add(receipt)
    uow.flush()
    return receipt


def cancel_receipt(
    uow: UnitOfWork, receipt_number: str, cancelled_on: date
) -> WarehouseReceiptRow:
    receipt = uow.session.scalar(
        select(WarehouseReceiptRow).where(
            WarehouseReceiptRow.receipt_number == receipt_number
        )
    )
    if receipt is None:
        raise LookupFailure(f"no warehouse receipt {receipt_number}")
    if not receipt.is_live:
        raise ConflictError(
            f"receipt {receipt_number} was cancelled on {receipt.cancelled_on}"
        )
    receipt.cancelled_on = cancelled_on
    uow.flush()
    return receipt


def on_hand(
    uow: UnitOfWork, commodity_code: str, location_code: str | None = None
) -> Decimal:
    """Total bushels the house is holding for producers."""
    lots: Sequence[StorageLotRow] = open_lots(
        uow, commodity_code=commodity_code, location_code=location_code
    )
    return quantize(
        sum((row.open_bushels for row in lots), Decimal("0")), BUSHEL_PLACES
    )
