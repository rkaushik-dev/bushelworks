"""Settling stored tickets, one producer at a time.

The office settles by producer rather than by ticket. A man who hauled nine
loads on Tuesday gets one statement and one cheque, and the statement shows the
loads. That is why this takes a list of tickets and produces one settlement
rather than looping over :func:`settle_load`.

Each load is still graded and priced on its own, because each has its own
moisture, its own discounts and possibly its own contract price. Only the
document is shared.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..domain.rulebook import RuleBook
from ..domain.settlement import LineKind, Settlement, SettlementBuilder
from ..errors import ConflictError, ValidationError
from ..money import amount, extend
from ..persistence.base import as_decimal
from ..persistence.mappers import contract_from_row, ticket_from_row
from ..persistence.models import ApplicationRow, ScaleTicketRow
from ..persistence.unitofwork import UnitOfWork
from .intake import GradedLoad, drying_charge, receive
from .numbering import Numbering
from .settling import Advance, DiscountBasis, StorageCharge

SETTLEMENT_SERIES = "S"


class NothingToSettle(ValidationError):
    """Raised when a settlement run finds no tickets it can pay for."""

    code = "nothing_to_settle"


@dataclass(frozen=True, slots=True)
class SettledTicket:
    """One load as it appears inside a producer settlement."""

    ticket_id: int
    ticket_number: str
    load: GradedLoad
    unit_price: Decimal

    @property
    def gross(self) -> Decimal:
        return extend(self.load.settled_bushels, self.unit_price)


def price_of(
    row: ScaleTicketRow,
    uow: UnitOfWork,
    spot: Decimal,
    settled_bushels: Decimal,
) -> Decimal:
    """The price for one stored ticket, blended across whatever it landed on.

    A ticket with no live applications is a spot purchase and takes the posted
    bid. One that half filled a forward is paid the weighted average of the
    contract price on the contracted bushels and spot on the rest, so the
    producer sees one number rather than two statements for one truck.

    The bushels that fell off the end of the contract are the ones this used to
    get wrong. They carry no application row, so blending only over the
    applications paid the whole load at the contract price and overpaid the
    overage by the width of the basis.
    """
    from ..domain.pricing import PricedQuantity, blend

    applications = list(
        uow.session.scalars(
            select(ApplicationRow).where(
                ApplicationRow.ticket_id == row.id,
                ApplicationRow.reversed_on.is_(None),
            )
        )
    )
    if not applications:
        return spot

    lots = [
        PricedQuantity(
            as_decimal(application.bushels),
            contract_from_row(application.contract).unit_price,
        )
        for application in applications
    ]
    contracted = sum((lot.bushels for lot in lots), Decimal("0"))
    remainder = Decimal(settled_bushels) - contracted
    if remainder > 0:
        lots.append(PricedQuantity(remainder, spot))
    return blend(lots)


def settle_producer(
    uow: UnitOfWork,
    party_code: str,
    location_code: str,
    rules: RuleBook,
    settled_on: date,
    spot_price: Decimal,
    discount_basis: DiscountBasis = DiscountBasis.DRY,
    advances: Sequence[Advance] = (),
    storage: Sequence[StorageCharge] = (),
    ticket_numbers: Sequence[str] | None = None,
) -> Settlement:
    """Settle every unsettled ticket a producer has at one site."""
    if uow.tickets is None:
        raise ConflictError("settle_producer needs an open unit of work")

    rows = [
        row
        for row in uow.tickets.awaiting_settlement(location_code)
        if row.party_code == party_code
    ]
    if ticket_numbers is not None:
        wanted = set(ticket_numbers)
        rows = [row for row in rows if row.ticket_number in wanted]
        missing = wanted - {row.ticket_number for row in rows}
        if missing:
            listed = ", ".join(sorted(missing))
            raise NothingToSettle(
                f"{party_code}: {listed} is not waiting to be settled at {location_code}"
            )
    if not rows:
        raise NothingToSettle(
            f"{party_code} has nothing waiting to be settled at {location_code}"
        )

    settled = []
    for row in rows:
        load = receive(ticket_from_row(row), rules)
        settled.append(
            SettledTicket(
                ticket_id=row.id,
                ticket_number=row.ticket_number,
                load=load,
                unit_price=price_of(row, uow, spot_price, load.settled_bushels),
            )
        )

    number = str(Numbering(uow.session).allocate(SETTLEMENT_SERIES, settled_on))
    builder = SettlementBuilder(number, settled_on, party_code, location_code)

    for item in settled:
        builder.reference_ticket(item.ticket_number)
        builder.add_gross(
            f"GRAIN-{item.ticket_number}",
            f"{item.load.commodity_code} {item.load.settled_bushels} bu at {item.unit_price}",
            item.load.settled_bushels,
            item.unit_price,
        )

    _add_discounts(builder, settled, discount_basis)
    _add_drying(builder, settled, rules, discount_basis)

    for charge in storage:
        builder.add_charge(
            LineKind.STORAGE,
            f"STOR-{charge.lot_id}",
            f"storage on {charge.lot_id} through {charge.through}",
            charge.dollars,
        )

    _add_assessments(builder, settled, rules)

    for advance in advances:
        builder.add_charge(
            LineKind.ADVANCE,
            f"ADV-{advance.reference}",
            f"advance of {advance.advanced_on}",
            advance.dollars,
        )

    statement = builder.build()

    uow.settlements.add(statement, ticket_ids=[item.ticket_id for item in settled])
    for row in rows:
        row.settled = True
    uow.flush()
    return statement


def _add_discounts(
    builder: SettlementBuilder,
    settled: Sequence[SettledTicket],
    basis: DiscountBasis,
) -> None:
    for item in settled:
        bushels = (
            item.load.dry_bushels
            if basis is DiscountBasis.DRY
            else item.load.clean_bushels
        )
        for factor, rate in sorted(
            item.load.discount_detail.items(), key=lambda kv: kv[0].value
        ):
            builder.add_charge(
                LineKind.DISCOUNT,
                f"{factor.value.upper()}-{item.ticket_number}",
                f"{factor.value.replace('_', ' ')} on {item.ticket_number}",
                extend(bushels, rate),
                quantity=bushels,
                rate=rate,
            )


def _add_drying(
    builder: SettlementBuilder,
    settled: Sequence[SettledTicket],
    rules: RuleBook,
    basis: DiscountBasis,
) -> None:
    for item in settled:
        charge = drying_charge(
            item.load, rules, on_dry_bushels=basis is DiscountBasis.DRY
        )
        if charge:
            builder.add_charge(
                LineKind.DRYING,
                f"DRY-{item.ticket_number}",
                f"drying {item.load.moisture_points} points on {item.ticket_number}",
                charge,
                quantity=item.load.moisture_points,
            )


def _add_assessments(
    builder: SettlementBuilder,
    settled: Sequence[SettledTicket],
    rules: RuleBook,
) -> None:
    """Levy per commodity, on the whole statement rather than per ticket.

    A percentage checkoff rounded per ticket and a percentage checkoff rounded
    per statement differ by a few cents on a nine load day, and the commission
    reconciles against the statement.
    """
    by_commodity: dict[str, Decimal] = {}
    for item in settled:
        by_commodity.setdefault(item.load.commodity_code, Decimal("0"))
        by_commodity[item.load.commodity_code] += item.load.settled_bushels

    gross = builder.running_gross
    net = amount(gross - builder.running_discounts)

    for commodity_code, bushels in sorted(by_commodity.items()):
        for line in rules.assessments.withhold(
            commodity_code=commodity_code,
            state_code=rules.policy.state_code,
            bushels=bushels,
            gross_value=gross,
            net_value=net,
        ):
            builder.add_charge(
                LineKind.ASSESSMENT, line.code, line.name, line.dollars
            )
