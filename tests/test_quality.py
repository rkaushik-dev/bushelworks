"""Taking, working and closing a complaint against what is on file."""

from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.complaints import ComplaintError, Outcome, Source
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.quality import (
    RETENTION_DAYS,
    close,
    file_complaint,
    indefensible,
    open_complaints,
    references,
    register_between,
    repeat_bins,
    summary,
    to_domain,
    to_row,
)

DELIVERED = date(2024, 10, 3)
RAISED = date(2024, 10, 9)
OPENS = date(2024, 9, 1)
CLOSES = date(2025, 8, 31)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def file_one(uow, **overrides):
    body = dict(
        reference="C-2024-018",
        source=Source.BUYER,
        subject="car ran 15.8 moisture against a 14.0 contract",
        raised_on=RAISED,
        delivered_on=DELIVERED,
        party_code="CARGILL",
        bin_id="BIN-3",
        probe_id="P-88213",
    )
    body.update(overrides)
    return file_complaint(uow, **body)


def test_a_complaint_can_be_filed(factory):
    with unit_of_work(factory) as uow:
        row = file_one(uow)
        uow.commit()
    assert row.outcome == "open"


def test_a_source_can_come_in_as_a_string(factory):
    with unit_of_work(factory) as uow:
        row = file_one(uow, source="producer")
        uow.commit()
    assert row.source == "producer"


def test_a_source_nobody_knows_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(ValueError):
        file_one(uow, source="postman")


def test_a_complaint_before_the_delivery_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(ComplaintError):
        file_one(uow, raised_on=date(2024, 10, 1))


def test_the_same_reference_twice_is_a_conflict(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        with pytest.raises(ConflictError):
            file_one(uow)


def test_a_named_ticket_that_is_not_on_file_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(ComplaintError):
        file_one(uow, ticket_number="A-99999")


def test_a_ticket_on_file_is_accepted(factory, rules, corn_ticket):
    from bushelworks.services.scalehouse import record_load

    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        row = file_one(uow, ticket_number=corn_ticket.ticket_number)
        uow.commit()
    assert row.ticket_number == corn_ticket.ticket_number


def test_a_ticket_weighed_after_the_delivery_is_refused(factory, rules, corn_ticket):
    from dataclasses import replace

    from bushelworks.services.scalehouse import record_load

    with unit_of_work(factory) as uow:
        record_load(
            uow, replace(corn_ticket, scale_date=date(2024, 11, 20)), "MERRICK", rules
        )
        with pytest.raises(ComplaintError):
            file_one(uow, ticket_number=corn_ticket.ticket_number)


def test_a_row_and_the_domain_object_agree(factory):
    with unit_of_work(factory) as uow:
        row = file_one(uow)
        uow.commit()
        complaint = to_domain(row)
    assert complaint.reference == "C-2024-018"
    assert complaint.source is Source.BUYER
    assert complaint.is_open


def test_the_mapping_survives_a_round_trip(factory):
    with unit_of_work(factory) as uow:
        row = file_one(uow)
        uow.commit()
        again = to_domain(to_row(to_domain(row)))
    assert again == to_domain(row)


def test_closing_writes_the_outcome_back(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        row = close(uow, "C-2024-018", Outcome.UPHELD, date(2024, 10, 14), Decimal("412.5"))
        uow.commit()
    assert row.outcome == "upheld"
    assert row.credit == Decimal("412.50")
    assert row.closed_on == date(2024, 10, 14)


def test_an_outcome_can_come_in_as_a_string(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        row = close(uow, "C-2024-018", "settled", date(2024, 10, 14))
        uow.commit()
    assert row.outcome == "settled"


def test_closing_twice_is_a_conflict(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        close(uow, "C-2024-018", Outcome.REJECTED, date(2024, 10, 14))
        with pytest.raises(ConflictError):
            close(uow, "C-2024-018", Outcome.UPHELD, date(2024, 10, 15))


def test_a_rejected_complaint_carries_no_credit(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        with pytest.raises(ComplaintError):
            close(
                uow,
                "C-2024-018",
                Outcome.REJECTED,
                date(2024, 10, 14),
                Decimal("50"),
            )


def test_a_closed_complaint_leaves_the_open_list(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        close(uow, "C-2024-018", Outcome.SETTLED, date(2024, 10, 14))
        uow.commit()
    with unit_of_work(factory) as uow:
        assert open_complaints(uow) == []


def test_the_open_list_hands_back_domain_objects(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        uow.commit()
        found = open_complaints(uow)
    assert found[0].days_after == 6


def test_the_open_list_can_be_narrowed_to_a_bin(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        file_one(uow, reference="C-2", bin_id="BIN-7")
        uow.commit()
        assert references(open_complaints(uow, "BIN-7")) == ("C-2",)


def test_a_register_carries_the_span(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        file_one(uow, reference="C-2", raised_on=date(2025, 10, 1))
        uow.commit()
        register = register_between(uow, OPENS, CLOSES)
    assert references(register.complaints) == ("C-2024-018",)


def test_a_complaint_with_no_sample_is_indefensible(factory):
    with unit_of_work(factory) as uow:
        file_one(uow, probe_id=None)
        uow.commit()
        assert references(indefensible(uow, OPENS, CLOSES)) == ("C-2024-018",)


def test_a_complaint_past_retention_is_indefensible(factory):
    with unit_of_work(factory) as uow:
        file_one(uow, raised_on=date(2024, 12, 1))
        uow.commit()
        assert len(indefensible(uow, OPENS, CLOSES)) == 1


def test_a_complaint_inside_retention_is_defensible(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        uow.commit()
        assert indefensible(uow, OPENS, CLOSES) == []


def test_the_retention_period_is_six_weeks_and_a_bit():
    assert RETENTION_DAYS == 45


def test_three_complaints_make_a_bin_a_pattern(factory):
    with unit_of_work(factory) as uow:
        for index in range(3):
            file_one(uow, reference=f"C-{index}")
        uow.commit()
        assert repeat_bins(uow, OPENS, CLOSES) == ["BIN-3"]


def test_two_complaints_are_not_a_pattern(factory):
    with unit_of_work(factory) as uow:
        for index in range(2):
            file_one(uow, reference=f"C-{index}")
        uow.commit()
        assert repeat_bins(uow, OPENS, CLOSES) == []


def test_the_threshold_can_be_moved(factory):
    with unit_of_work(factory) as uow:
        for index in range(2):
            file_one(uow, reference=f"C-{index}")
        uow.commit()
        assert repeat_bins(uow, OPENS, CLOSES, threshold=2) == ["BIN-3"]


def test_the_summary_counts_what_came_in(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        file_one(uow, reference="C-2", source=Source.PRODUCER)
        uow.commit()
        page = summary(uow, OPENS, CLOSES)
    assert page["count"] == 2
    assert page["open"] == 2
    assert page["by_source"] == {"buyer": 1, "producer": 1}


def test_the_summary_carries_the_upheld_rate(factory):
    with unit_of_work(factory) as uow:
        file_one(uow)
        file_one(uow, reference="C-2")
        close(uow, "C-2024-018", Outcome.UPHELD, date(2024, 10, 14), Decimal("412.50"))
        close(uow, "C-2", Outcome.REJECTED, date(2024, 10, 14))
        uow.commit()
        page = summary(uow, OPENS, CLOSES)
    assert page["upheld_rate"] == Decimal("50.0")
    assert page["credits"] == Decimal("412.50")


def test_a_quiet_span_summarises_to_nothing(factory):
    with unit_of_work(factory) as uow:
        page = summary(uow, OPENS, CLOSES)
    assert page["count"] == 0
    assert page["by_source"] == {}
    assert page["indefensible"] == []


def test_the_summary_names_the_window(factory):
    with unit_of_work(factory) as uow:
        assert summary(uow, OPENS, CLOSES)["window_days"] == 30
