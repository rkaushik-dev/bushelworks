"""Rows in, value objects out.

The domain objects are frozen dataclasses with validation in ``__post_init__``,
so building one from a row is also a check that the row is still legal. That is
on purpose. A row written before a constraint existed will fail loudly here
rather than settling quietly at the wrong number.

Nothing in this module writes to the session. Persisting is the repository's
job; this only translates.
"""

from __future__ import annotations

from decimal import Decimal

from ..domain.commodity import Factor
from ..domain.contracts import (
    ContractKind,
    ContractStatus,
    DeliveryWindow,
    PurchaseContract,
)
from ..domain.pricing import MONTH_CODES, FuturesMonth
from ..domain.sales import (
    Carrier,
    Destination,
    SaleContract,
    SaleKind,
    Specification,
)
from ..domain.settlement import LineKind, Settlement, SettlementLine
from ..domain.tickets import Disposition, ScaleTicket, SplitRole, SplitShare
from ..errors import ValidationError
from .base import as_decimal
from .models import (
    ContractRow,
    SaleRow,
    SampleRow,
    ScaleTicketRow,
    SettlementLineRow,
    SettlementRow,
    TicketSplitRow,
)


class MappingError(ValidationError):
    """Raised when a stored row cannot be read back as a domain object."""

    code = "mapping_error"


def sample_from_rows(rows: list[SampleRow]) -> dict[Factor, Decimal]:
    sample: dict[Factor, Decimal] = {}
    for row in rows:
        try:
            factor = Factor(row.factor)
        except ValueError as exc:
            raise MappingError(f"{row.factor} is not a grade factor") from exc
        sample[factor] = as_decimal(row.value)
    return sample


def sample_to_rows(sample: dict[Factor, Decimal]) -> list[SampleRow]:
    return [
        SampleRow(factor=factor.value, value=value)
        for factor, value in sorted(sample.items(), key=lambda kv: kv[0].value)
    ]


def splits_from_rows(rows: list[TicketSplitRow]) -> list[SplitShare]:
    return [
        SplitShare(
            party_id=row.party_code,
            percent=as_decimal(row.percent),
            role=SplitRole(row.role),
        )
        for row in rows
    ]


def splits_to_rows(shares: list[SplitShare]) -> list[TicketSplitRow]:
    return [
        TicketSplitRow(
            party_code=share.party_id,
            percent=share.percent,
            role=share.role.value,
        )
        for share in shares
    ]


def ticket_from_row(row: ScaleTicketRow) -> ScaleTicket:
    return ScaleTicket(
        ticket_number=row.ticket_number,
        scale_date=row.scale_date,
        location_code=row.location_code,
        commodity_code=row.commodity_code,
        gross_pounds=as_decimal(row.gross_pounds),
        tare_pounds=as_decimal(row.tare_pounds),
        disposition=Disposition(row.disposition),
        splits=splits_from_rows(list(row.splits)),
        sample=sample_from_rows(list(row.samples)),
        dockage_percent=as_decimal(row.dockage_percent),
        truck_id=row.truck_id or "",
        driver=row.driver or "",
        farm=row.farm or "",
        field_name=row.field_name or "",
        contract_number=row.contract_number,
        remarks=row.remarks or "",
        voided=row.voided,
    )


def ticket_to_row(ticket: ScaleTicket, party_code: str) -> ScaleTicketRow:
    row = ScaleTicketRow(
        ticket_number=ticket.ticket_number,
        location_code=ticket.location_code,
        scale_date=ticket.scale_date,
        commodity_code=ticket.commodity_code,
        party_code=party_code,
        gross_pounds=ticket.gross_pounds,
        tare_pounds=ticket.tare_pounds,
        dockage_percent=ticket.dockage_percent,
        disposition=ticket.disposition.value,
        contract_number=ticket.contract_number,
        truck_id=ticket.truck_id or None,
        driver=ticket.driver or None,
        farm=ticket.farm or None,
        field_name=ticket.field_name or None,
        remarks=ticket.remarks or None,
        voided=ticket.voided,
    )
    row.samples = sample_to_rows(dict(ticket.sample))
    row.splits = splits_to_rows(list(ticket.splits))
    return row


def futures_month_from_symbol(symbol: str | None, commodity_code: str) -> FuturesMonth | None:
    """Read ``CORNZ24`` back into a month.

    The symbol is stored rather than three columns because it is what the hedge
    desk types and what the broker statement shows. Parsing it back needs the
    commodity because the code is a prefix of variable length.
    """
    if not symbol:
        return None
    if not symbol.startswith(commodity_code):
        raise MappingError(f"{symbol} is not a {commodity_code} contract")
    tail = symbol[len(commodity_code) :]
    if len(tail) != 3 or tail[0] not in MONTH_CODES or not tail[1:].isdigit():
        raise MappingError(f"cannot read a delivery month out of {symbol}")
    return FuturesMonth(commodity_code, tail[0], 2000 + int(tail[1:]))


def contract_from_row(row: ContractRow) -> PurchaseContract:
    return PurchaseContract(
        number=row.number,
        kind=ContractKind(row.kind),
        party_id=row.party_code,
        commodity_code=row.commodity_code,
        location_code=row.location_code,
        written_on=row.written_on,
        bushels=as_decimal(row.bushels),
        window=DeliveryWindow(row.window_opens, row.window_closes),
        futures_month=futures_month_from_symbol(row.futures_symbol, row.commodity_code),
        futures_price=None if row.futures_price is None else as_decimal(row.futures_price),
        basis=None if row.basis is None else as_decimal(row.basis),
        flat_price=None if row.flat_price is None else as_decimal(row.flat_price),
        tolerance_percent=as_decimal(row.tolerance_percent),
        service_rate_per_bushel=as_decimal(row.service_rate_per_bushel),
        status=ContractStatus(row.status),
    )


def contract_to_row(contract: PurchaseContract) -> ContractRow:
    return ContractRow(
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
        tolerance_percent=contract.tolerance_percent,
        service_rate_per_bushel=contract.service_rate_per_bushel,
        status=contract.status.value,
    )


def sale_from_row(row: SaleRow) -> SaleContract:
    return SaleContract(
        number=row.number,
        kind=SaleKind(row.kind),
        buyer=row.buyer,
        destination=Destination(row.destination),
        carrier=Carrier(row.carrier),
        commodity_code=row.commodity_code,
        origin_code=row.origin_code,
        written_on=row.written_on,
        bushels=as_decimal(row.bushels),
        window=DeliveryWindow(row.window_opens, row.window_closes),
        specification=Specification(
            max_moisture=as_decimal(row.spec_max_moisture),
            min_test_weight=as_decimal(row.spec_min_test_weight),
            max_damage=as_decimal(row.spec_max_damage),
            max_foreign_material=as_decimal(row.spec_max_foreign),
        ),
        futures_month=futures_month_from_symbol(row.futures_symbol, row.commodity_code),
        futures_price=None if row.futures_price is None else as_decimal(row.futures_price),
        basis=None if row.basis is None else as_decimal(row.basis),
        flat_price=None if row.flat_price is None else as_decimal(row.flat_price),
        tolerance_percent=as_decimal(row.tolerance_percent),
        freight_per_bushel=as_decimal(row.freight_per_bushel),
        shipped=as_decimal(row.shipped),
    )


def sale_to_row(sale: SaleContract) -> SaleRow:
    return SaleRow(
        number=sale.number,
        kind=sale.kind.value,
        buyer=sale.buyer,
        destination=sale.destination.value,
        carrier=sale.carrier.value,
        commodity_code=sale.commodity_code,
        origin_code=sale.origin_code,
        written_on=sale.written_on,
        bushels=sale.bushels,
        shipped=sale.shipped,
        window_opens=sale.window.opens,
        window_closes=sale.window.closes,
        futures_symbol=None if sale.futures_month is None else sale.futures_month.symbol,
        futures_price=sale.futures_price,
        basis=sale.basis,
        flat_price=sale.flat_price,
        tolerance_percent=sale.tolerance_percent,
        freight_per_bushel=sale.freight_per_bushel,
        spec_max_moisture=sale.specification.max_moisture,
        spec_min_test_weight=sale.specification.min_test_weight,
        spec_max_damage=sale.specification.max_damage,
        spec_max_foreign=sale.specification.max_foreign_material,
    )


def settlement_from_row(row: SettlementRow) -> Settlement:
    lines = [
        SettlementLine(
            kind=LineKind(line.kind),
            code=line.code,
            description=line.description,
            dollars=as_decimal(line.dollars),
            quantity=None if line.quantity is None else as_decimal(line.quantity),
            rate=None if line.rate is None else as_decimal(line.rate),
        )
        for line in row.lines
    ]
    return Settlement(
        number=row.number,
        settled_on=row.settled_on,
        party_id=row.party_code,
        location_code=row.location_code,
        lines=tuple(lines),
    )


def settlement_to_row(settlement: Settlement) -> SettlementRow:
    row = SettlementRow(
        number=settlement.number,
        settled_on=settlement.settled_on,
        party_code=settlement.party_id,
        location_code=settlement.location_code,
        gross=settlement.gross,
        deductions=settlement.deductions,
        net=settlement.net,
    )
    row.lines = [
        SettlementLineRow(
            sequence=index + 1,
            kind=line.kind.value,
            code=line.code,
            description=line.description,
            dollars=line.dollars,
            quantity=line.quantity,
            rate=line.rate,
        )
        for index, line in enumerate(settlement.lines)
    ]
    return row
