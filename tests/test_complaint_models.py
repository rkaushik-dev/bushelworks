"""The complaint tables and the repository over them."""

from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy.exc import IntegrityError

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import ComplaintRow
from bushelworks.persistence.repositories import ComplaintNotFound
from bushelworks.persistence.unitofwork import unit_of_work

DELIVERED = date(2024, 10, 3)
RAISED = date(2024, 10, 9)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_row(**overrides) -> ComplaintRow:
    base = dict(
        reference="C-2024-018",
        source="buyer",
        subject="car ran 15.8 moisture against a 14.0 contract",
        raised_on=RAISED,
        delivered_on=DELIVERED,
        party_code="CARGILL",
        bin_id="BIN-3",
        probe_id="P-88213",
        outcome="open",
        credit=Decimal("0.00"),
        note="",
    )
    base.update(overrides)
    return ComplaintRow(**base)


def test_a_complaint_can_be_filed(factory):
    with unit_of_work(factory) as uow:
        row = uow.complaints.add(a_row())
        uow.commit()
    assert row.id is not None


def test_a_filed_complaint_can_be_read_back(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.complaints.row("C-2024-018").party_code == "CARGILL"


def test_asking_for_one_that_is_not_there_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(ComplaintNotFound):
        uow.complaints.row("C-NOPE")


def test_the_same_reference_twice_is_a_conflict(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        with pytest.raises(ConflictError):
            uow.complaints.add(a_row())


def test_a_negative_credit_is_refused_by_the_database(factory):
    with unit_of_work(factory) as uow, pytest.raises(IntegrityError):
        uow.complaints.add(a_row(credit=Decimal("-1.00")))
        uow.flush()


def test_a_complaint_before_the_delivery_is_refused_by_the_database(factory):
    with unit_of_work(factory) as uow, pytest.raises(IntegrityError):
        uow.complaints.add(a_row(raised_on=date(2024, 10, 1)))
        uow.flush()


def test_a_complaint_needs_no_bin_or_ticket(factory):
    with unit_of_work(factory) as uow:
        row = uow.complaints.add(
            a_row(reference="C-2", bin_id=None, ticket_number=None, party_code=None)
        )
        uow.commit()
    assert row.bin_id is None


def test_the_open_list_leaves_out_the_closed(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2", outcome="upheld"))
        uow.commit()
    with unit_of_work(factory) as uow:
        assert [row.reference for row in uow.complaints.open_complaints()] == [
            "C-2024-018"
        ]


def test_the_open_list_is_oldest_first(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2", raised_on=date(2024, 10, 4)))
        uow.commit()
    with unit_of_work(factory) as uow:
        assert [row.reference for row in uow.complaints.open_complaints()] == [
            "C-2",
            "C-2024-018",
        ]


def test_the_open_list_can_be_narrowed_to_a_bin(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2", bin_id="BIN-7"))
        uow.commit()
    with unit_of_work(factory) as uow:
        found = [row.reference for row in uow.complaints.open_complaints("BIN-7")]
    assert found == ["C-2"]


def test_a_span_picks_up_what_falls_inside_it(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2", raised_on=date(2024, 12, 1)))
        uow.commit()
    with unit_of_work(factory) as uow:
        found = [
            row.reference
            for row in uow.complaints.between(date(2024, 10, 1), date(2024, 10, 31))
        ]
    assert found == ["C-2024-018"]


def test_complaints_can_be_found_by_party(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2", party_code="MERRICK"))
        uow.commit()
    with unit_of_work(factory) as uow:
        assert [row.reference for row in uow.complaints.for_party("MERRICK")] == ["C-2"]


def test_the_bin_counts_group_by_bin(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.add(a_row(reference="C-2"))
        uow.complaints.add(a_row(reference="C-3", bin_id="BIN-7"))
        uow.commit()
    with unit_of_work(factory) as uow:
        counts = uow.complaints.bin_counts(date(2024, 1, 1), date(2024, 12, 31))
    assert counts == {"BIN-3": 2, "BIN-7": 1}


def test_a_complaint_with_no_bin_is_left_out_of_the_counts(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row(bin_id=None))
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.complaints.bin_counts(date(2024, 1, 1), date(2024, 12, 31)) == {}


def test_an_action_is_numbered_from_one(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        action = uow.complaints.act(
            "C-2024-018", RAISED, "dee", "pulled the retained sample"
        )
        uow.commit()
    assert action.sequence == 1


def test_actions_number_in_the_order_they_are_taken(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.act("C-2024-018", RAISED, "dee", "pulled the sample")
        second = uow.complaints.act(
            "C-2024-018", date(2024, 10, 11), "dee", "reran it on the meter"
        )
        uow.commit()
    assert second.sequence == 2


def test_the_actions_hang_off_the_complaint(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(a_row())
        uow.complaints.act("C-2024-018", RAISED, "dee", "pulled the sample")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert len(uow.complaints.row("C-2024-018").actions) == 1


def test_acting_on_a_complaint_that_is_not_there_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(ComplaintNotFound):
        uow.complaints.act("C-NOPE", RAISED, "dee", "nothing")


def test_deleting_a_complaint_takes_its_actions_with_it(factory):
    with unit_of_work(factory) as uow:
        row = uow.complaints.add(a_row())
        uow.complaints.act("C-2024-018", RAISED, "dee", "pulled the sample")
        uow.commit()
        uow.session.delete(row)
        uow.commit()
    with unit_of_work(factory) as uow, pytest.raises(ComplaintNotFound):
        uow.complaints.row("C-2024-018")


def test_the_credits_add_up_over_a_span(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(
            a_row(outcome="upheld", credit=Decimal("412.50"), closed_on=date(2024, 10, 14))
        )
        uow.complaints.add(
            a_row(
                reference="C-2",
                outcome="settled",
                credit=Decimal("87.25"),
                closed_on=date(2024, 10, 20),
            )
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.complaints.credits_between(
            date(2024, 10, 1), date(2024, 10, 31)
        ) == Decimal("499.75")


def test_a_credit_outside_the_span_does_not_count(factory):
    with unit_of_work(factory) as uow:
        uow.complaints.add(
            a_row(outcome="upheld", credit=Decimal("412.50"), closed_on=date(2024, 12, 1))
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.complaints.credits_between(
            date(2024, 10, 1), date(2024, 10, 31)
        ) == Decimal("0")


def test_the_register_is_not_available_outside_the_block(factory):
    uow = unit_of_work(factory)
    with pytest.raises(ConflictError):
        uow.complaints.open_complaints()
