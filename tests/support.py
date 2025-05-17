"""Helpers the tests share.

``settle_load`` used to be the quick way to get a statement out of one ticket
without a database, and several tests leaned on it for that rather than for the
settlement rule it carried. It is gone; this is the part they actually wanted.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from datetime import date
from decimal import Decimal

from sqlalchemy import event

from bushelworks.domain.rulebook import RuleBook
from bushelworks.domain.settlement import LineKind, Settlement, SettlementBuilder
from bushelworks.domain.tickets import ScaleTicket
from bushelworks.money import amount, extend
from bushelworks.services.charges import Advance, DiscountBasis, StorageCharge
from bushelworks.services.intake import drying_charge, receive


def quick_statement(
    rules: RuleBook,
    ticket: ScaleTicket,
    number: str = "S-2024-0417",
    unit_price: Decimal = Decimal("4.3500"),
    settled_on: date = date(2024, 10, 20),
    party_code: str = "MERRICK",
    discount_basis: DiscountBasis = DiscountBasis.DRY,
    advances: Sequence[Advance] = (),
    storage: Sequence[StorageCharge] = (),
    contract_number: str | None = None,
) -> Settlement:
    """A finished statement for one ticket, with no database anywhere near it."""
    load = receive(ticket, rules)
    builder = SettlementBuilder(number, settled_on, party_code, load.location_code)
    builder.reference_ticket(load.ticket_number)
    if contract_number:
        builder.reference_contract(contract_number)

    builder.add_gross(
        "GRAIN",
        f"{load.commodity_code} {load.settled_bushels} bu at {unit_price}",
        load.settled_bushels,
        unit_price,
    )

    bushels = (
        load.dry_bushels
        if discount_basis is DiscountBasis.DRY
        else load.clean_bushels
    )
    for factor, rate in sorted(load.discount_detail.items(), key=lambda kv: kv[0].value):
        builder.add_charge(
            LineKind.DISCOUNT,
            factor.value.upper(),
            f"{factor.value.replace('_', ' ')} discount",
            extend(bushels, rate),
            quantity=bushels,
            rate=rate,
        )

    drying = drying_charge(
        load, rules, on_dry_bushels=discount_basis is DiscountBasis.DRY
    )
    if drying:
        builder.add_charge(
            LineKind.DRYING,
            "DRY",
            f"drying {load.moisture_points} points",
            drying,
            quantity=load.moisture_points,
        )

    for charge in storage:
        builder.add_charge(
            LineKind.STORAGE,
            f"STOR-{charge.lot_id}",
            f"storage on {charge.lot_id} through {charge.through}",
            charge.dollars,
        )

    for line in rules.assessments.withhold(
        commodity_code=load.commodity_code,
        state_code=rules.policy.state_code,
        bushels=load.settled_bushels,
        gross_value=builder.running_gross,
        net_value=amount(builder.running_gross - builder.running_discounts),
    ):
        builder.add_charge(LineKind.ASSESSMENT, line.code, line.name, line.dollars)

    for advance in advances:
        builder.add_charge(
            LineKind.ADVANCE,
            f"ADV-{advance.reference}",
            f"advance of {advance.advanced_on}",
            advance.dollars,
        )

    return builder.build()


def settlement_for(
    number: str,
    ticket: ScaleTicket,
    unit_price: Decimal,
    rules: RuleBook,
    party_code: str,
    settled_on: date,
    **kwargs: object,
) -> Settlement:
    """The old ``settle_load`` argument order, for the tests that used it."""
    return quick_statement(
        rules,
        ticket,
        number=number,
        unit_price=unit_price,
        settled_on=settled_on,
        party_code=party_code,
        **kwargs,  # type: ignore[arg-type]
    )


class Counter:
    """Counts the statements an engine executes while it is armed.

    Lived in two test modules with the same body until the settlement rollups
    went in and needed a third. The copies matched table names by substring,
    which counts ``FROM settlement_line`` as a read of ``settlement`` and makes
    every count in a query test come out high. This one matches on a word
    boundary.
    """

    def __init__(self, engine) -> None:
        self.engine = engine
        self.statements: list[str] = []

    def __enter__(self) -> Counter:
        event.listen(self.engine, "before_cursor_execute", self._record)
        return self

    def __exit__(self, *exc: object) -> None:
        event.remove(self.engine, "before_cursor_execute", self._record)

    def _record(self, conn, cursor, statement, parameters, context, executemany):
        self.statements.append(statement)

    @property
    def count(self) -> int:
        return len(self.statements)

    def selecting(self, table: str) -> int:
        """Statements that read one named table."""
        pattern = re.compile(rf"FROM {re.escape(table)}\b")
        return len([item for item in self.statements if pattern.search(item)])
