"""Hedge desk routes.

The broker statement is not on file, so the sheet is a POST even though it
changes nothing. The fills go up in the body, the position comes off the
database, and the answer comes back. Putting the fills in a query string was
tried and the URL ran past what the office proxy would log.

Everything here needs an office key. The gap between the book and the hedge is
the one number that says what the house is actually exposed to, and it is not
something the scale terminal or a producer portal has any business reading.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import Field, field_validator

from ...domain.hedging import Account, Lot, Side
from ...domain.pricing import FuturesMonth
from ...persistence.unitofwork import UnitOfWork
from ...services.desk import basis, carry, month_exposure, sheet
from ..deps import get_uow
from ..schemas import Money, Quantity, _as_decimal
from ..security import office_access

router = APIRouter(
    prefix="/desk",
    tags=["desk"],
    dependencies=[Depends(office_access)],
)


class FillIn(Money):
    """One line off the broker statement."""

    reference: str = Field(min_length=1, max_length=24)
    commodity_code: str = Field(min_length=1, max_length=8)
    month_code: str = Field(min_length=1, max_length=1)
    year: int = Field(ge=2000, le=2100)
    side: Side
    contracts: int = Field(gt=0, le=2000)
    price: Quantity
    filled_on: date

    @field_validator("price", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)

    def month(self) -> FuturesMonth:
        return FuturesMonth(
            commodity_code=self.commodity_code,
            month_code=self.month_code,
            year=self.year,
        )

    def lot(self) -> Lot:
        return Lot(
            reference=self.reference,
            month=self.month(),
            side=self.side,
            contracts=self.contracts,
            price=self.price,
            filled_on=self.filled_on,
        )


class SheetIn(Money):
    as_of: date
    location_code: str = "*"
    commodities: list[str] | None = None
    fills: list[FillIn] = Field(default_factory=list)


class GapOut(Money):
    commodity_code: str
    net_long: Quantity
    hedged_bushels: Quantity
    want_contracts: int
    on_contracts: int
    gap_contracts: int
    gap_bushels: Quantity
    side: Side
    is_actionable: bool
    is_over_hedged: bool


class SheetOut(Money):
    as_of: date
    location_code: str
    total_gap_contracts: int
    lines: list[GapOut]


class ExposureIn(Money):
    fills: list[FillIn] = Field(default_factory=list)
    settlements: dict[str, Quantity] = Field(default_factory=dict)

    @field_validator("settlements", mode="before")
    @classmethod
    def _decimals(cls, value: Any) -> Any:
        if isinstance(value, dict):
            return {key: _as_decimal(item) for key, item in value.items()}
        return value


def _accounts(fills: list[FillIn]) -> dict[str, Account]:
    """One account per commodity.

    A corn short does not offset a bean long and putting every fill in one
    account would let it look as though it did.
    """
    accounts: dict[str, Account] = {}
    for fill in fills:
        account = accounts.setdefault(fill.commodity_code, Account())
        account.add(fill.lot())
    return accounts


def _gap_out(gap: Any) -> GapOut:
    return GapOut(
        commodity_code=gap.commodity_code,
        net_long=gap.net_long,
        hedged_bushels=gap.hedged_bushels,
        want_contracts=gap.want_contracts,
        on_contracts=gap.on_contracts,
        gap_contracts=gap.gap_contracts,
        gap_bushels=gap.gap_bushels,
        side=gap.side,
        is_actionable=gap.is_actionable,
        is_over_hedged=gap.is_over_hedged,
    )


@router.post("/sheet", response_model=SheetOut)
def build_sheet(
    body: SheetIn,
    uow: UnitOfWork = Depends(get_uow),
) -> SheetOut:
    """The morning sheet: position against broker, one line per commodity."""
    built = sheet(
        uow,
        body.as_of,
        _accounts(body.fills),
        body.commodities,
        body.location_code,
    )
    return SheetOut(
        as_of=built.as_of,
        location_code=built.location_code,
        total_gap_contracts=built.total_gap_contracts(),
        lines=[_gap_out(gap) for gap in built.gaps],
    )


@router.post("/sheet/actionable", response_model=list[GapOut])
def build_actionable(
    body: SheetIn,
    uow: UnitOfWork = Depends(get_uow),
) -> list[GapOut]:
    """Only the lines worth a telephone call, for the screen in the office."""
    built = sheet(
        uow,
        body.as_of,
        _accounts(body.fills),
        body.commodities,
        body.location_code,
    )
    return [_gap_out(gap) for gap in built.actionable()]


@router.post("/exposure")
def read_exposure(body: ExposureIn) -> dict[str, str]:
    """Open gain or loss per month against the settlements handed in.

    Nothing is read off the database here. It is the futures side on its own,
    which is what gets reconciled against the broker statement.
    """
    accounts = _accounts(body.fills)
    prices: dict[FuturesMonth, Decimal] = {}
    for fill in body.fills:
        month = fill.month()
        settle = body.settlements.get(month.symbol)
        if settle is not None:
            prices[month] = settle
    out: dict[str, str] = {}
    for account in accounts.values():
        for symbol, value in month_exposure(account, prices).items():
            out[symbol] = str(value)
    return out


@router.get("/basis")
def read_basis(cash_bid: Decimal, futures: Decimal) -> dict[str, str]:
    """Cash less futures, for the board out front."""
    return {
        "cash_bid": str(cash_bid),
        "futures": str(futures),
        "basis": str(basis(cash_bid, futures)),
    }


@router.get("/carry")
def read_carry(
    near: Decimal,
    deferred: Decimal,
    months: int,
    storage_per_month: Decimal,
) -> dict[str, str]:
    """Whether the spread pays to hold the grain rather than sell it."""
    per_bushel = carry(near, deferred, months, storage_per_month)
    return {
        "spread": str(deferred - near),
        "months": str(months),
        "carry_per_bushel": str(per_bushel),
        "pays_to_store": "yes" if per_bushel > 0 else "no",
    }
