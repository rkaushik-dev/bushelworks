"""The cheque run.

Settlements pile up unpaid until somebody runs the cheques, usually on a
Thursday. The run gathers every unpaid statement for a producer, adds them,
takes one cheque number off the stock and marks all of them paid against it.

Two rules that look like details and are not:

* a producer with a zero or negative net does not get a cheque. A negative net
  is a debit balance, which the office carries forward and collects against the
  next load rather than sending a bill.
* a cheque number, once allocated, is used or voided. It is never quietly
  skipped, because the bank reconciliation counts them.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..logging import event, get_logger
from ..money import amount, total
from ..persistence.base import as_decimal
from ..persistence.models import SettlementRow
from ..persistence.unitofwork import UnitOfWork
from . import audit

_log = get_logger("payments")


class PaymentError(ValidationError):
    """Raised when a cheque cannot be written as asked."""

    code = "payment_error"


@dataclass(frozen=True, slots=True)
class Cheque:
    """One cheque covering one or more settlements."""

    cheque_number: str
    party_code: str
    paid_on: date
    dollars: Decimal
    settlement_numbers: tuple[str, ...]

    @property
    def is_multiple(self) -> bool:
        return len(self.settlement_numbers) > 1


@dataclass(frozen=True, slots=True)
class ChequeRun:
    """What one pass over the unpaid statements produced."""

    paid_on: date
    cheques: tuple[Cheque, ...]
    held: tuple[tuple[str, Decimal], ...]

    @property
    def total(self) -> Decimal:
        return total(cheque.dollars for cheque in self.cheques)

    @property
    def count(self) -> int:
        return len(self.cheques)

    def for_party(self, party_code: str) -> Cheque | None:
        for cheque in self.cheques:
            if cheque.party_code == party_code:
                return cheque
        return None


def unpaid_by_party(uow: UnitOfWork) -> dict[str, list[SettlementRow]]:
    """Every unpaid statement, grouped by who it belongs to."""
    grouped: dict[str, list[SettlementRow]] = {}
    for row in uow.settlements.unpaid():
        grouped.setdefault(row.party_code, []).append(row)
    return grouped


def next_cheque_number(start: str, offset: int) -> str:
    """Step a cheque number, keeping its width.

    Cheque stock is numbered with leading zeroes and the bank cares. ``010441``
    plus one is ``010442`` and not ``10442``.
    """
    if not start.isdigit():
        raise PaymentError(f"{start!r} is not a cheque number")
    width = len(start)
    stepped = int(start) + offset
    text = str(stepped)
    if len(text) > width:
        raise PaymentError(
            f"cheque numbers ran past the width of the stock at {text}"
        )
    return text.zfill(width)


def run_cheques(
    uow: UnitOfWork,
    paid_on: date,
    first_cheque: str,
    party_codes: Sequence[str] | None = None,
    minimum: Decimal = Decimal("0.01"),
    actor: str = audit.SYSTEM,
) -> ChequeRun:
    """Pay everything unpaid, one cheque per producer."""
    grouped = unpaid_by_party(uow)
    if party_codes is not None:
        wanted = set(party_codes)
        missing = wanted - set(grouped)
        if missing:
            listed = ", ".join(sorted(missing))
            raise PaymentError(f"nothing unpaid for {listed}")
        grouped = {code: rows for code, rows in grouped.items() if code in wanted}
    if not grouped:
        raise PaymentError("nothing is waiting to be paid")

    cheques: list[Cheque] = []
    held: list[tuple[str, Decimal]] = []
    issued = 0

    for party_code in sorted(grouped):
        rows = grouped[party_code]
        owed = total(as_decimal(row.net) for row in rows)
        if owed < minimum:
            held.append((party_code, owed))
            continue

        number = next_cheque_number(first_cheque, issued)
        issued += 1
        for row in rows:
            uow.settlements.mark_paid(row.number, number, paid_on)
        cheque = Cheque(
            cheque_number=number,
            party_code=party_code,
            paid_on=paid_on,
            dollars=amount(owed),
            settlement_numbers=tuple(row.number for row in rows),
        )
        cheques.append(cheque)
        audit.record(
            uow,
            audit.Action.PAID,
            audit.Document.PAYMENT,
            number,
            actor=actor,
            party_code=party_code,
            amount=cheque.dollars,
            effective_on=paid_on,
            detail="settles " + ", ".join(cheque.settlement_numbers),
        )

    run = ChequeRun(paid_on=paid_on, cheques=tuple(cheques), held=tuple(held))
    event(
        _log,
        "cheque.run",
        paid_on=paid_on,
        cheques=run.count,
        total=run.total,
        held=len(run.held),
    )
    return run


def void_cheque(
    uow: UnitOfWork,
    cheque_number: str,
    reason: str,
    actor: str = audit.SYSTEM,
) -> list[SettlementRow]:
    """Void a cheque and put its settlements back in the unpaid pile."""
    from sqlalchemy import select

    rows = list(
        uow.session.scalars(
            select(SettlementRow).where(SettlementRow.cheque_number == cheque_number)
        )
    )
    if not rows:
        raise PaymentError(f"no cheque {cheque_number} on file")
    if not reason:
        raise PaymentError(f"{cheque_number}: a void needs a reason")

    for row in rows:
        row.paid_on = None
        row.cheque_number = None
    uow.flush()
    audit.record(
        uow,
        audit.Action.VOIDED,
        audit.Document.PAYMENT,
        cheque_number,
        actor=actor,
        detail=reason,
    )
    return rows


def debit_balances(uow: UnitOfWork) -> list[tuple[str, Decimal]]:
    """Producers whose unpaid statements add to nothing or less.

    Carried forward rather than billed. The office rings them; the software does
    not send a demand.
    """
    out: list[tuple[str, Decimal]] = []
    for party_code, rows in sorted(unpaid_by_party(uow).items()):
        owed = total(as_decimal(row.net) for row in rows)
        if owed <= 0:
            out.append((party_code, owed))
    return out


def reconcile(uow: UnitOfWork, cleared: dict[str, Decimal]) -> list[str]:
    """Compare the bank's cleared list against what the house wrote.

    Returns the cheque numbers that disagree, which is what the bookkeeper works
    from. A cheque the bank cleared for a different amount is the one that
    matters and it is the one this finds.
    """
    from sqlalchemy import select

    problems: list[str] = []
    for number, cleared_amount in sorted(cleared.items()):
        rows = list(
            uow.session.scalars(
                select(SettlementRow).where(SettlementRow.cheque_number == number)
            )
        )
        if not rows:
            problems.append(number)
            continue
        written = total(as_decimal(row.net) for row in rows)
        if amount(written) != amount(cleared_amount):
            problems.append(number)
    return problems
