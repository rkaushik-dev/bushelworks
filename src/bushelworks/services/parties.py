"""The producer file.

A party is whoever the house writes a cheque to or takes grain from. The same
row serves as producer, landlord, tenant and lienholder, because the same person
is often three of those on different farms.

Split agreements hang off a farm rather than off a party, and they are effective
dated. The scale house copies the split onto the ticket at the window, so a
change to the agreement in December cannot move money on an October load. That
copy is the whole point of the arrangement and the reason the agreement is not
read at settlement time.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..domain.tickets import SplitRole, SplitShare
from ..errors import ConflictError, LookupFailure, ValidationError
from ..persistence.base import as_decimal
from ..persistence.models import Party, SplitAgreement
from ..persistence.unitofwork import UnitOfWork


class PartyError(ValidationError):
    """Raised when the producer file cannot be changed as asked."""

    code = "party_error"


class NoSplitAgreement(LookupFailure):
    code = "no_split_agreement"


@dataclass(frozen=True, slots=True)
class Agreement:
    """One farm's split, as it stood on a date."""

    farm_code: str
    effective_from: date
    shares: tuple[SplitShare, ...]

    @property
    def total_percent(self) -> Decimal:
        return sum((share.percent for share in self.shares), Decimal("0"))

    def is_balanced(self) -> bool:
        return self.total_percent == Decimal("100")


def open_account(
    uow: UnitOfWork,
    code: str,
    name: str,
    state_code: str = "KS",
    sort_name: str | None = None,
    tax_id: str | None = None,
    opened_on: date | None = None,
) -> Party:
    """Put a new producer on the file."""
    if not code:
        raise PartyError("a party needs a code")
    if not name:
        raise PartyError(f"{code}: a party needs a name")
    existing = uow.session.scalar(select(Party).where(Party.code == code))
    if existing is not None:
        raise ConflictError(f"{code} is already on the file")

    row = Party(
        code=code,
        name=name,
        sort_name=(sort_name or name).upper(),
        state_code=state_code.upper(),
        tax_id=tax_id,
        opened_on=opened_on or date.today(),
    )
    uow.session.add(row)
    uow.flush()
    return row


def close_account(uow: UnitOfWork, code: str, closed_on: date) -> Party:
    """Close a producer's account. Nothing is deleted.

    A closed account keeps its history and stops appearing on the pick list. It
    is refused if anything is still unsettled, because the office would then
    have no way to reach the row that owes the money.
    """
    row = uow.parties.get(code)
    if not row.is_active:
        raise ConflictError(f"{code} is already closed")

    waiting = [
        ticket
        for ticket in uow.tickets.for_party(code)
        if not ticket.settled and not ticket.voided
    ]
    if waiting:
        listed = ", ".join(ticket.ticket_number for ticket in waiting[:5])
        raise ConflictError(
            f"{code} still has {len(waiting)} unsettled tickets: {listed}"
        )

    row.is_active = False
    row.closed_on = closed_on
    uow.flush()
    return row


def set_exempt(uow: UnitOfWork, code: str, exempt: bool) -> Party:
    """Mark a producer as exempt from the checkoff, or take it off."""
    row = uow.parties.get(code)
    row.checkoff_exempt = exempt
    uow.flush()
    return row


def file_agreement(
    uow: UnitOfWork,
    farm_code: str,
    shares: list[SplitShare],
    effective_from: date,
) -> Agreement:
    """File a farm's split. It has to add up before it goes on."""
    if not farm_code:
        raise PartyError("an agreement needs a farm")
    if not shares:
        raise PartyError(f"{farm_code}: an agreement needs at least one share")

    agreement = Agreement(farm_code, effective_from, tuple(shares))
    if not agreement.is_balanced():
        raise PartyError(
            f"{farm_code}: shares total {agreement.total_percent}%, "
            "they have to make 100%"
        )

    seen: set[str] = set()
    for share in shares:
        if share.party_id in seen:
            raise PartyError(f"{farm_code}: {share.party_id} appears twice")
        seen.add(share.party_id)
        uow.parties.get(share.party_id)

    existing = uow.session.scalars(
        select(SplitAgreement).where(
            SplitAgreement.farm_code == farm_code,
            SplitAgreement.effective_to.is_(None),
        )
    )
    for row in existing:
        if row.effective_from >= effective_from:
            raise ConflictError(
                f"{farm_code}: an agreement already runs from {row.effective_from}"
            )
        row.effective_to = effective_from

    for share in shares:
        party = uow.parties.get(share.party_id)
        uow.session.add(
            SplitAgreement(
                farm_code=farm_code,
                party_id=party.id,
                percent=share.percent,
                role=share.role.value,
                effective_from=effective_from,
            )
        )
    uow.flush()
    return agreement


def agreement_for(uow: UnitOfWork, farm_code: str, on: date) -> Agreement:
    """The split in force on a day, for copying onto a ticket."""
    rows = list(
        uow.session.scalars(
            select(SplitAgreement).where(
                SplitAgreement.farm_code == farm_code,
                SplitAgreement.effective_from <= on,
            )
        )
    )
    live = [
        row
        for row in rows
        if row.effective_to is None or row.effective_to > on
    ]
    if not live:
        raise NoSplitAgreement(f"no split on file for {farm_code} on {on}")

    codes: dict[int, str] = {}
    for row in live:
        party = uow.session.get(Party, row.party_id)
        if party is None:
            raise NoSplitAgreement(
                f"{farm_code}: the agreement names a party that is no longer on file"
            )
        codes[row.party_id] = party.code
    shares = tuple(
        SplitShare(
            party_id=codes[row.party_id],
            percent=as_decimal(row.percent),
            role=SplitRole(row.role),
        )
        for row in sorted(live, key=lambda r: codes[r.party_id])
    )
    return Agreement(
        farm_code=farm_code,
        effective_from=max(row.effective_from for row in live),
        shares=shares,
    )


def farms_for(uow: UnitOfWork, party_code: str) -> tuple[str, ...]:
    """Every farm a producer has a share of."""
    party = uow.parties.get(party_code)
    rows = uow.session.scalars(
        select(SplitAgreement.farm_code)
        .where(SplitAgreement.party_id == party.id)
        .distinct()
    )
    return tuple(sorted(set(rows)))
