"""Producer file routes.

Closing an account is a POST to a sub resource rather than a DELETE, because
nothing is deleted and the closure has a date on it. Filing a split agreement is
a POST to the farm, not to the party, because the agreement belongs to the
ground.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import Field, field_validator

from ...domain.tickets import SplitRole, SplitShare
from ...persistence.models import Party
from ...persistence.unitofwork import UnitOfWork
from ...services.parties import (
    agreement_for,
    close_account,
    farms_for,
    file_agreement,
    open_account,
    set_exempt,
)
from ..deps import get_uow, paging
from ..schemas import Money, Page, Quantity, _as_decimal
from ..security import office_access

router = APIRouter(
    prefix="/parties",
    tags=["parties"],
    dependencies=[Depends(office_access)],
)


class PartyIn(Money):
    code: str = Field(min_length=1, max_length=24)
    name: str = Field(min_length=1, max_length=120)
    sort_name: str | None = None
    state_code: str = "KS"
    tax_id: str | None = None
    opened_on: date | None = None


class ShareIn(Money):
    party_code: str = Field(min_length=1, max_length=24)
    percent: Quantity
    role: SplitRole = SplitRole.OWNER

    @field_validator("percent", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class AgreementIn(Money):
    effective_from: date
    shares: list[ShareIn] = Field(min_length=1)


class PartyOut(Money):
    code: str
    name: str
    sort_name: str
    state_code: str
    tax_id: str | None = None
    is_active: bool
    checkoff_exempt: bool
    opened_on: date | None = None
    closed_on: date | None = None


class AgreementOut(Money):
    farm_code: str
    effective_from: date
    shares: list[ShareIn]
    total_percent: Quantity
    is_balanced: bool


def _party_out(row: Party) -> PartyOut:
    return PartyOut(
        code=row.code,
        name=row.name,
        sort_name=row.sort_name,
        state_code=row.state_code,
        tax_id=row.tax_id,
        is_active=row.is_active,
        checkoff_exempt=row.checkoff_exempt,
        opened_on=row.opened_on,
        closed_on=row.closed_on,
    )


def _agreement_out(agreement) -> AgreementOut:
    return AgreementOut(
        farm_code=agreement.farm_code,
        effective_from=agreement.effective_from,
        shares=[
            ShareIn(party_code=share.party_id, percent=share.percent, role=share.role)
            for share in agreement.shares
        ],
        total_percent=agreement.total_percent,
        is_balanced=agreement.is_balanced(),
    )


@router.post("", response_model=PartyOut, status_code=status.HTTP_201_CREATED)
def create_party(body: PartyIn, uow: UnitOfWork = Depends(get_uow)) -> PartyOut:
    row = open_account(
        uow,
        code=body.code,
        name=body.name,
        state_code=body.state_code,
        sort_name=body.sort_name,
        tax_id=body.tax_id,
        opened_on=body.opened_on,
    )
    out = _party_out(row)
    uow.commit()
    return out


@router.get("/{code}", response_model=PartyOut)
def read_party(code: str, uow: UnitOfWork = Depends(get_uow)) -> PartyOut:
    return _party_out(uow.parties.get(code))


@router.get("", response_model=Page)
def list_parties(
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    rows = uow.parties.active()
    window = rows[offset : offset + limit]
    return Page(
        items=[_party_out(row) for row in window],
        total=len(rows),
        offset=offset,
        limit=limit,
    )


@router.post("/{code}/closure", response_model=PartyOut)
def close(
    code: str, on: date, uow: UnitOfWork = Depends(get_uow)
) -> PartyOut:
    row = close_account(uow, code, on)
    out = _party_out(row)
    uow.commit()
    return out


@router.post("/{code}/checkoff-exemption", response_model=PartyOut)
def exemption(
    code: str, exempt: bool = True, uow: UnitOfWork = Depends(get_uow)
) -> PartyOut:
    row = set_exempt(uow, code, exempt)
    out = _party_out(row)
    uow.commit()
    return out


class FarmsOut(Money):
    party_code: str
    farms: list[str]


@router.get("/{code}/farms", response_model=FarmsOut)
def read_farms(code: str, uow: UnitOfWork = Depends(get_uow)) -> FarmsOut:
    return FarmsOut(party_code=code, farms=list(farms_for(uow, code)))


@router.post(
    "/farms/{farm_code}/agreements",
    response_model=AgreementOut,
    status_code=status.HTTP_201_CREATED,
)
def create_agreement(
    farm_code: str, body: AgreementIn, uow: UnitOfWork = Depends(get_uow)
) -> AgreementOut:
    agreement = file_agreement(
        uow,
        farm_code,
        [
            SplitShare(party_id=share.party_code, percent=share.percent, role=share.role)
            for share in body.shares
        ],
        body.effective_from,
    )
    uow.commit()
    return _agreement_out(agreement)


@router.get("/farms/{farm_code}/agreement", response_model=AgreementOut)
def read_agreement(
    farm_code: str, on: date, uow: UnitOfWork = Depends(get_uow)
) -> AgreementOut:
    """The split in force on a day, which is what the window copies."""
    return _agreement_out(agreement_for(uow, farm_code, on))
