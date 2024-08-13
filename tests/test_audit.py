from datetime import UTC, date
from decimal import Decimal

import pytest

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.audit import (
    Action,
    AuditError,
    Document,
    count,
    for_day,
    for_document,
    for_party,
    last_action,
    record,
)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def test_an_entry_is_written_and_read_back(factory):
    with unit_of_work(factory) as uow:
        record(
            uow,
            Action.RECORDED,
            Document.TICKET,
            "A-10441",
            actor="rmerrick",
            party_code="MERRICK",
            location_code="HOME",
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        entries = for_document(uow, Document.TICKET, "A-10441")
    assert len(entries) == 1
    assert entries[0].action == "recorded"
    assert entries[0].actor == "rmerrick"


def test_the_actor_defaults_to_the_system(factory):
    with unit_of_work(factory) as uow:
        record(uow, Action.ACCRUED, Document.STORAGE_LOT, "L-1")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert for_document(uow, Document.STORAGE_LOT, "L-1")[0].actor == "system"


def test_an_entry_without_an_action_is_refused(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(AuditError):
            record(uow, "", Document.TICKET, "A-10441")


def test_an_entry_without_a_document_number_is_refused(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(AuditError):
            record(uow, Action.RECORDED, Document.TICKET, "")


def test_a_rolled_back_transaction_leaves_no_evidence(factory):
    with unit_of_work(factory) as uow:
        record(uow, Action.RECORDED, Document.TICKET, "A-10441")
    with unit_of_work(factory) as uow:
        assert count(uow) == 0


def test_entries_come_back_oldest_first(factory):
    with unit_of_work(factory) as uow:
        record(uow, Action.RECORDED, Document.TICKET, "A-10441")
        record(uow, Action.VOIDED, Document.TICKET, "A-10441")
        uow.commit()
    with unit_of_work(factory) as uow:
        actions = [entry.action for entry in for_document(uow, Document.TICKET, "A-10441")]
    assert actions == ["recorded", "voided"]


def test_the_last_action_is_the_most_recent(factory):
    with unit_of_work(factory) as uow:
        record(uow, Action.RECORDED, Document.TICKET, "A-10441")
        record(uow, Action.VOIDED, Document.TICKET, "A-10441")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert last_action(uow, Document.TICKET, "A-10441").action == "voided"


def test_a_document_nothing_happened_to_has_no_last_action(factory):
    with unit_of_work(factory) as uow:
        assert last_action(uow, Document.TICKET, "A-99999") is None


def test_entries_for_a_producer_come_back_newest_first(factory):
    with unit_of_work(factory) as uow:
        record(uow, Action.RECORDED, Document.TICKET, "A-1", party_code="MERRICK")
        record(uow, Action.SETTLED, Document.SETTLEMENT, "S-1", party_code="MERRICK")
        record(uow, Action.RECORDED, Document.TICKET, "A-2", party_code="HOLLIS")
        uow.commit()
    with unit_of_work(factory) as uow:
        entries = for_party(uow, "MERRICK")
    assert [entry.document_number for entry in entries] == ["S-1", "A-1"]


def test_the_producer_view_can_be_limited(factory):
    with unit_of_work(factory) as uow:
        for index in range(5):
            record(
                uow, Action.RECORDED, Document.TICKET, f"A-{index}", party_code="MERRICK"
            )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert len(for_party(uow, "MERRICK", limit=2)) == 2


def test_an_amount_is_kept_as_it_stood(factory):
    with unit_of_work(factory) as uow:
        record(
            uow,
            Action.SETTLED,
            Document.SETTLEMENT,
            "S-2024-0417",
            party_code="MERRICK",
            amount=Decimal("2848.01"),
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        entry = for_document(uow, Document.SETTLEMENT, "S-2024-0417")[0]
    assert entry.amount == Decimal("2848.01")


def test_a_long_detail_is_trimmed_rather_than_refused(factory):
    with unit_of_work(factory) as uow:
        record(
            uow,
            Action.VOIDED,
            Document.TICKET,
            "A-10441",
            detail="x" * 900,
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert len(for_document(uow, Document.TICKET, "A-10441")[0].detail) == 400


def test_the_day_view_finds_todays_entries(factory):
    from datetime import datetime

    today = datetime.now(UTC).date()
    with unit_of_work(factory) as uow:
        record(uow, Action.RECORDED, Document.TICKET, "A-1")
        record(uow, Action.VOIDED, Document.TICKET, "A-1")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert len(for_day(uow, today)) == 2
        assert len(for_day(uow, today, action=Action.VOIDED)) == 1
        assert for_day(uow, date(2000, 1, 1)) == []


def test_the_count_is_what_was_written(factory):
    with unit_of_work(factory) as uow:
        for index in range(3):
            record(uow, Action.RECORDED, Document.TICKET, f"A-{index}")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert count(uow) == 3
