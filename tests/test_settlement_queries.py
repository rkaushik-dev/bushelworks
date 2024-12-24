"""How many round trips a settlement takes.

Pricing used to query per ticket and then fetch each contract on its own. A nine
load settlement was nineteen statements for what is one join, and on the office
box over a network share that was most of the wall clock. These count the
statements so it cannot quietly go back.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import event

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import (
    applications_for,
    price_from,
    settle_producer,
)

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


class Counter:
    """Counts the statements an engine executes while it is armed."""

    def __init__(self, engine) -> None:
        self.engine = engine
        self.statements: list[str] = []

    def __enter__(self) -> "Counter":
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
        return len(
            [item for item in self.statements if f"FROM {table}" in item]
        )


@pytest.fixture
def engine():
    eng = engine_for("sqlite+pysqlite:///:memory:")
    create_all(eng)
    yield eng
    drop_all(eng)


@pytest.fixture
def factory(engine):
    return session_factory(engine)


def a_contract(number="F-2024-118"):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("100000"),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )


@pytest.fixture
def nine_loads(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        for index in range(9):
            record_load(
                uow,
                replace(corn_ticket, ticket_number=f"A-{10441 + index}"),
                "MERRICK",
                rules,
            )
        uow.commit()
    return factory


def test_the_applications_come_back_grouped_by_ticket(nine_loads):
    with unit_of_work(nine_loads) as uow:
        rows = uow.tickets.awaiting_settlement("HOME")
        grouped = applications_for(uow, [row.id for row in rows])
    assert len(grouped) == 9
    assert all(len(items) == 1 for items in grouped.values())


def test_asking_for_no_tickets_queries_nothing(nine_loads, engine):
    with unit_of_work(nine_loads) as uow, Counter(engine) as counter:
        assert applications_for(uow, []) == {}
    assert counter.count == 0


def test_the_applications_are_fetched_in_one_pass(nine_loads, engine):
    with unit_of_work(nine_loads) as uow:
        rows = uow.tickets.awaiting_settlement("HOME")
        ticket_ids = [row.id for row in rows]
        with Counter(engine) as counter:
            applications_for(uow, ticket_ids)
    # One for the applications, one for the eagerly loaded contracts.
    assert counter.selecting("contract_application") == 1
    assert counter.selecting("purchase_contract") == 1


def test_a_ticket_with_no_applications_prices_at_spot():
    assert price_from([], SPOT, Decimal("900")) == SPOT


def test_a_wholly_contracted_ticket_prices_at_the_contract(nine_loads):
    with unit_of_work(nine_loads) as uow:
        rows = uow.tickets.awaiting_settlement("HOME")
        grouped = applications_for(uow, [row.id for row in rows])
        applications = grouped[rows[0].id]
        price = price_from(applications, SPOT, Decimal("897.6324"))
    assert price == Decimal("4.6200")


def test_a_partly_contracted_ticket_blends(nine_loads):
    with unit_of_work(nine_loads) as uow:
        rows = uow.tickets.awaiting_settlement("HOME")
        grouped = applications_for(uow, [row.id for row in rows])
        price = price_from(grouped[rows[0].id], SPOT, Decimal("2000"))
    assert SPOT < price < Decimal("4.6200")


def test_settling_nine_loads_does_not_query_per_ticket(nine_loads, engine):
    with unit_of_work(nine_loads) as uow, Counter(engine) as counter:
        settle_producer(uow, "MERRICK", "HOME", rules_for(uow), SETTLED, SPOT)
        uow.rollback()
    assert counter.selecting("contract_application") <= 2


def rules_for(uow):
    from bushelworks.services.houses import house_rulebook

    return house_rulebook()


def test_the_settlement_still_comes_out_right(nine_loads):
    with unit_of_work(nine_loads) as uow:
        statement = settle_producer(
            uow, "MERRICK", "HOME", rules_for(uow), SETTLED, SPOT
        )
        uow.commit()
    assert len(statement.ticket_numbers) == 9
    statement.check_balances()


def test_every_load_is_priced_at_the_contract(nine_loads):
    with unit_of_work(nine_loads) as uow:
        statement = settle_producer(
            uow, "MERRICK", "HOME", rules_for(uow), SETTLED, SPOT
        )
        uow.commit()
    from bushelworks.domain.settlement import LineKind

    rates = {line.rate for line in statement.of_kind(LineKind.GROSS)}
    assert rates == {Decimal("4.6200")}
