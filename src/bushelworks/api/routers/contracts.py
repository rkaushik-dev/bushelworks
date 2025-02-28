"""Contract routes.

Pricing an unpriced half is a POST to a sub resource rather than a PATCH on the
contract, because setting a basis is an event with a date and a person behind
it, not a field edit. The contract that comes back is the amended one.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import Field, field_validator

from ...domain.contracts import (
    ContractKind,
    ContractStatus,
    DeliveryWindow,
    PurchaseContract,
)
from ...domain.pricing import FuturesMonth
from ...persistence.mappers import contract_from_row
from ...persistence.unitofwork import UnitOfWork
from ..deps import get_uow, paging
from ..schemas import Money, Page, Quantity, _as_decimal
from ..security import office_access

# Writing a contract commits the house to a price. That is an office job.
router = APIRouter(
    prefix="/contracts",
    tags=["contracts"],
    dependencies=[Depends(office_access)],
)


class ContractIn(Money):
    number: str = Field(min_length=1, max_length=24)
    kind: ContractKind
    party_code: str = Field(min_length=1, max_length=24)
    commodity_code: str = Field(min_length=1, max_length=8)
    location_code: str = Field(min_length=1, max_length=12)
    written_on: date
    bushels: Quantity
    window_opens: date
    window_closes: date
    futures_month: str | None = None
    futures_year: int | None = None
    futures_price: Quantity | None = None
    basis: Quantity | None = None
    flat_price: Quantity | None = None
    tolerance_percent: Quantity = Decimal("2")
    service_rate_per_bushel: Quantity = Decimal("0")

    @field_validator(
        "bushels",
        "futures_price",
        "basis",
        "flat_price",
        "tolerance_percent",
        "service_rate_per_bushel",
        mode="before",
    )
    @classmethod
    def _decimal(cls, value: Any) -> Any:
        return None if value is None else _as_decimal(value)


class PriceIn(Money):
    value: Quantity
    on: date

    @field_validator("value", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class RollIn(Money):
    month_code: str = Field(min_length=1, max_length=1)
    year: int
    spread: Quantity

    @field_validator("spread", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class ContractOut(Money):
    number: str
    kind: str
    party_code: str
    commodity_code: str
    location_code: str
    written_on: date
    bushels: Quantity
    window_opens: date
    window_closes: date
    futures_symbol: str | None = None
    futures_price: Quantity | None = None
    basis: Quantity | None = None
    flat_price: Quantity | None = None
    unit_price: Quantity | None = None
    is_fully_priced: bool
    minimum_bushels: Quantity
    maximum_bushels: Quantity
    applied_bushels: Quantity
    status: str


def _to_domain(body: ContractIn) -> PurchaseContract:
    month = None
    if body.futures_month and body.futures_year:
        month = FuturesMonth(body.commodity_code, body.futures_month, body.futures_year)
    return PurchaseContract(
        number=body.number,
        kind=body.kind,
        party_id=body.party_code,
        commodity_code=body.commodity_code,
        location_code=body.location_code,
        written_on=body.written_on,
        bushels=body.bushels,
        window=DeliveryWindow(body.window_opens, body.window_closes),
        futures_month=month,
        futures_price=body.futures_price,
        basis=body.basis,
        flat_price=body.flat_price,
        tolerance_percent=body.tolerance_percent,
        service_rate_per_bushel=body.service_rate_per_bushel,
    )


def _to_out(contract: PurchaseContract, applied: Decimal) -> ContractOut:
    try:
        unit_price = contract.unit_price
    except Exception:
        unit_price = None
    return ContractOut(
        number=contract.number,
        kind=contract.kind.value,
        party_code=contract.party_id,
        commodity_code=contract.commodity_code,
        location_code=contract.location_code,
        written_on=contract.written_on,
        bushels=contract.bushels,
        window_opens=contract.window.opens,
        window_closes=contract.window.closes,
        futures_symbol=None if contract.futures_month is None else contract.futures_month.symbol,
        futures_price=contract.futures_price,
        basis=contract.basis,
        flat_price=contract.flat_price,
        unit_price=unit_price,
        is_fully_priced=contract.is_fully_priced,
        minimum_bushels=contract.minimum_bushels,
        maximum_bushels=contract.maximum_bushels,
        applied_bushels=applied,
        status=contract.status.value,
    )


@router.post("", response_model=ContractOut, status_code=status.HTTP_201_CREATED)
def write_contract(
    body: ContractIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    contract = _to_domain(body)
    uow.contracts.add(contract)
    uow.commit()
    return _to_out(contract, Decimal("0"))


@router.get("/{number}", response_model=ContractOut)
def read_contract(
    number: str,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    contract = uow.contracts.get(number)
    return _to_out(contract, uow.contracts.applied_bushels(number))


@router.post("/{number}/basis", response_model=ContractOut)
def set_basis(
    number: str,
    body: PriceIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    amended = uow.contracts.get(number).set_basis(body.value, body.on)
    uow.contracts.replace(amended)
    uow.commit()
    return _to_out(amended, uow.contracts.applied_bushels(number))


@router.post("/{number}/futures", response_model=ContractOut)
def set_futures(
    number: str,
    body: PriceIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    amended = uow.contracts.get(number).set_futures(body.value, body.on)
    uow.contracts.replace(amended)
    uow.commit()
    return _to_out(amended, uow.contracts.applied_bushels(number))


@router.post("/{number}/roll", response_model=ContractOut)
def roll_contract(
    number: str,
    body: RollIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    contract = uow.contracts.get(number)
    month = FuturesMonth(contract.commodity_code, body.month_code, body.year)
    amended = contract.roll_to(month, body.spread)
    uow.contracts.replace(amended)
    uow.commit()
    return _to_out(amended, uow.contracts.applied_bushels(number))


@router.post("/{number}/cancel", response_model=ContractOut)
def cancel_contract(
    number: str,
    uow: UnitOfWork = Depends(get_uow),
) -> ContractOut:
    amended = uow.contracts.get(number).cancel()
    uow.contracts.replace(amended)
    uow.commit()
    return _to_out(amended, uow.contracts.applied_bushels(number))


@router.get("", response_model=Page)
def list_contracts(
    party_code: str,
    commodity_code: str,
    on: date | None = None,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    """Open contracts for a producer, defaulting to those open today."""
    offset, limit = page
    day = on or date.today()
    contracts = uow.contracts.open_for(party_code, commodity_code, day)
    window = contracts[offset : offset + limit]
    return Page(
        items=[
            _to_out(contract, uow.contracts.applied_bushels(contract.number))
            for contract in window
        ],
        total=len(contracts),
        offset=offset,
        limit=limit,
    )


__all__ = ["ContractIn", "ContractOut", "ContractStatus", "contract_from_row", "router"]
