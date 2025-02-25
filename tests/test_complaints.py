from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.complaints import (
    Complaint,
    ComplaintError,
    Outcome,
    Register,
    Source,
    by_source,
)
from bushelworks.errors import ConflictError

DELIVERED = date(2024, 10, 3)
RAISED = date(2024, 10, 9)


def a_complaint(**overrides) -> Complaint:
    base = dict(
        reference="C-2024-018",
        source=Source.BUYER,
        raised_on=RAISED,
        delivered_on=DELIVERED,
        subject="car ran 15.8 moisture against a 14.0 contract",
        party_code="CARGILL",
        bin_id="BIN-3",
        probe_id="P-88213",
    )
    base.update(overrides)
    return Complaint(**base)


def test_a_complaint_needs_a_reference_and_a_subject():
    with pytest.raises(ComplaintError):
        a_complaint(reference="")
    with pytest.raises(ComplaintError):
        a_complaint(subject="")


def test_a_complaint_cannot_predate_the_delivery():
    with pytest.raises(ComplaintError):
        a_complaint(raised_on=date(2024, 10, 1))


def test_a_credit_cannot_be_negative():
    with pytest.raises(ComplaintError):
        a_complaint(credit=Decimal("-10.00"))


def test_a_complaint_knows_how_long_the_grain_had_been_gone():
    assert a_complaint().days_after == 6


def test_a_complaint_inside_the_month_is_not_late():
    assert not a_complaint().is_late


def test_a_complaint_after_the_month_is_late():
    assert a_complaint(raised_on=date(2024, 11, 20)).is_late


def test_a_new_complaint_is_open():
    complaint = a_complaint()
    assert complaint.is_open
    assert complaint.outcome is Outcome.OPEN


def test_a_sample_on_the_shelf_can_be_pulled():
    assert a_complaint().sample_is_available()


def test_a_sample_past_retention_is_gone():
    assert not a_complaint(raised_on=date(2024, 12, 1)).sample_is_available()


def test_a_complaint_with_no_probe_has_nothing_to_pull():
    assert not a_complaint(probe_id=None).sample_is_available()


def test_the_retention_period_can_be_set_by_the_house():
    complaint = a_complaint(raised_on=date(2024, 10, 20))
    assert complaint.sample_is_available(retention_days=45)
    assert not complaint.sample_is_available(retention_days=10)


def test_closing_records_the_outcome_and_the_day():
    closed = a_complaint().close(Outcome.UPHELD, date(2024, 10, 14), Decimal("412.5"))
    assert closed.outcome is Outcome.UPHELD
    assert closed.closed_on == date(2024, 10, 14)
    assert closed.credit == Decimal("412.50")
    assert not closed.is_open


def test_closing_a_closed_complaint_is_a_conflict():
    closed = a_complaint().close(Outcome.REJECTED, date(2024, 10, 14))
    with pytest.raises(ConflictError):
        closed.close(Outcome.UPHELD, date(2024, 10, 15))


def test_closing_needs_a_real_outcome():
    with pytest.raises(ComplaintError):
        a_complaint().close(Outcome.OPEN, date(2024, 10, 14))


def test_a_complaint_cannot_close_before_it_was_raised():
    with pytest.raises(ComplaintError):
        a_complaint().close(Outcome.SETTLED, date(2024, 10, 4))


def test_a_rejected_complaint_carries_no_credit():
    with pytest.raises(ComplaintError):
        a_complaint().close(Outcome.REJECTED, date(2024, 10, 14), Decimal("50"))


def test_the_note_survives_closing():
    closed = a_complaint().close(
        Outcome.SETTLED, date(2024, 10, 14), Decimal("100"), note="split the difference"
    )
    assert closed.note == "split the difference"


def a_register() -> Register:
    register = Register()
    register.add(a_complaint())
    return register


def test_the_same_reference_twice_is_a_conflict():
    register = a_register()
    with pytest.raises(ConflictError):
        register.add(a_complaint())


def test_replacing_a_complaint_that_is_not_there_is_refused():
    with pytest.raises(ComplaintError):
        Register().replace(a_complaint())


def test_closing_a_complaint_takes_it_off_the_open_list():
    register = a_register()
    register.replace(register.open_complaints()[0].close(Outcome.REJECTED, RAISED))
    assert register.open_complaints() == ()


def test_the_open_list_is_oldest_first():
    register = a_register()
    register.add(a_complaint(reference="C-2024-005", raised_on=date(2024, 10, 4)))
    assert [item.reference for item in register.open_complaints()] == [
        "C-2024-005",
        "C-2024-018",
    ]


def test_complaints_can_be_found_by_bin():
    register = a_register()
    register.add(a_complaint(reference="C-2", bin_id="BIN-7"))
    assert [item.reference for item in register.for_bin("BIN-7")] == ["C-2"]


def test_a_bin_complained_about_three_times_is_flagged():
    register = a_register()
    register.add(a_complaint(reference="C-2"))
    register.add(a_complaint(reference="C-3"))
    assert register.repeat_bins() == ["BIN-3"]


def test_two_complaints_are_not_a_pattern():
    register = a_register()
    register.add(a_complaint(reference="C-2"))
    assert register.repeat_bins() == []


def test_complaints_with_no_bin_are_left_out_of_the_pattern():
    register = Register()
    for index in range(3):
        register.add(a_complaint(reference=f"C-{index}", bin_id=None))
    assert register.repeat_bins() == []


def test_the_credits_add_up():
    register = a_register()
    register.replace(
        register.open_complaints()[0].close(Outcome.UPHELD, RAISED, Decimal("412.50"))
    )
    register.add(a_complaint(reference="C-2", credit=Decimal("87.25")))
    assert register.credits() == Decimal("499.75")


def test_nothing_closed_yet_means_no_upheld_rate():
    assert a_register().upheld_rate() == Decimal("0.0")


def test_the_upheld_rate_counts_only_closed_complaints():
    register = a_register()
    register.replace(register.open_complaints()[0].close(Outcome.UPHELD, RAISED))
    register.add(a_complaint(reference="C-2"))
    register.replace(a_complaint(reference="C-2").close(Outcome.REJECTED, RAISED))
    register.add(a_complaint(reference="C-3"))
    assert register.upheld_rate() == Decimal("50.0")


def test_an_open_complaint_with_no_sample_is_indefensible():
    register = Register()
    register.add(a_complaint(reference="C-9", probe_id=None))
    assert [item.reference for item in register.indefensible()] == ["C-9"]


def test_a_complaint_with_a_sample_is_defensible():
    assert a_register().indefensible() == ()


def test_a_closed_complaint_is_not_on_the_indefensible_list():
    register = Register()
    register.add(a_complaint(reference="C-9", probe_id=None))
    register.replace(register.open_complaints()[0].close(Outcome.SETTLED, RAISED))
    assert register.indefensible() == ()


def test_complaints_group_by_who_rang():
    complaints = [
        a_complaint(),
        a_complaint(reference="C-2", source=Source.PRODUCER),
        a_complaint(reference="C-3", source=Source.PRODUCER),
    ]
    assert by_source(complaints) == {"buyer": 1, "producer": 2}


def test_grouping_nothing_gives_nothing():
    assert by_source([]) == {}
