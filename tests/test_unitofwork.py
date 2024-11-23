from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import ScaleTicketRow
from bushelworks.persistence.repositories import TicketNotFound
from bushelworks.persistence.unitofwork import unit_of_work


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def test_a_committed_unit_of_work_persists(factory, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")
        uow.commit()

    with factory() as session:
        assert session.scalar(select(ScaleTicketRow)) is not None


def test_leaving_the_block_without_committing_rolls_back(factory, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")

    with factory() as session:
        assert session.scalar(select(ScaleTicketRow)) is None


def test_an_exception_rolls_back_what_was_written(factory, corn_ticket):
    with pytest.raises(RuntimeError):
        with unit_of_work(factory) as uow:
            uow.tickets.add(corn_ticket, "MERRICK")
            raise RuntimeError("the printer jammed")

    with factory() as session:
        assert session.scalar(select(ScaleTicketRow)) is None


def test_an_exception_after_a_commit_leaves_the_commit_alone(factory, corn_ticket):
    with pytest.raises(RuntimeError):
        with unit_of_work(factory) as uow:
            uow.tickets.add(corn_ticket, "MERRICK")
            uow.commit()
            raise RuntimeError("the printer jammed afterwards")

    with factory() as session:
        assert session.scalar(select(ScaleTicketRow)) is not None


def test_the_repositories_share_one_session(factory, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")
        # not committed yet, but visible to another repository on the same session
        assert uow.tickets.exists("HOME", "A-10441")
        assert uow.session is uow.contracts.session


def test_the_unit_of_work_closes_itself(factory, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")
        uow.commit()
    assert not uow.is_open


def test_reaching_for_a_repository_outside_the_block_says_so(factory):
    with unit_of_work(factory) as uow:
        pass
    with pytest.raises(ConflictError) as caught:
        uow.tickets.exists("HOME", "A-10441")
    assert "open unit of work" in str(caught.value)


def test_reaching_for_the_session_outside_the_block_says_so(factory):
    with unit_of_work(factory) as uow:
        pass
    with pytest.raises(ConflictError):
        uow.session.execute(select(ScaleTicketRow))


def test_every_repository_is_reachable_inside_the_block(factory):
    with unit_of_work(factory) as uow:
        assert uow.tickets is not None
        assert uow.contracts is not None
        assert uow.settlements is not None
        assert uow.parties is not None


def test_committing_after_the_block_is_refused(factory):
    with unit_of_work(factory) as uow:
        pass
    with pytest.raises(ConflictError):
        uow.commit()


def test_flushing_after_the_block_is_refused(factory):
    with unit_of_work(factory) as uow:
        pass
    with pytest.raises(ConflictError):
        uow.flush()


def test_a_unit_of_work_cannot_be_opened_twice(factory):
    uow = unit_of_work(factory)
    with uow:
        with pytest.raises(ConflictError):
            uow.__enter__()


def test_it_reports_whether_it_committed(factory, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")
        assert not uow.committed
        uow.commit()
        assert uow.committed


def test_a_rollback_inside_the_block_undoes_only_what_followed(factory, corn_ticket):
    from dataclasses import replace

    with unit_of_work(factory) as uow:
        uow.tickets.add(corn_ticket, "MERRICK")
        uow.commit()
        uow.tickets.add(replace(corn_ticket, ticket_number="A-10442"), "MERRICK")
        uow.rollback()

    with factory() as session:
        numbers = [row.ticket_number for row in session.scalars(select(ScaleTicketRow))]
    assert numbers == ["A-10441"]


def test_a_missing_ticket_still_raises_inside_a_unit_of_work(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(TicketNotFound):
            uow.tickets.get("HOME", "A-99999")


def test_settlements_and_tickets_land_together(factory, rules, corn_ticket):
    from support import settlement_for

    with unit_of_work(factory) as uow:
        row = uow.tickets.add(corn_ticket, "MERRICK")
        statement = settlement_for(
            "S-1", corn_ticket, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20)
        )
        uow.settlements.add(statement, ticket_ids=[row.id])
        uow.commit()

    with unit_of_work(factory) as uow:
        assert uow.settlements.get("S-1").net == statement.net
        assert uow.tickets.exists("HOME", "A-10441")
