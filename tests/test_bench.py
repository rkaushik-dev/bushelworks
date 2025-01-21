from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import pytest

from bushelworks.domain import commodity as cm
from bushelworks.domain.bench import RETENTION, Bench, BenchError, Sample, Step
from bushelworks.domain.grade import NumericalGrade
from bushelworks.errors import ConflictError

DRAWN = datetime(2024, 10, 3, 8, 15, tzinfo=timezone.utc)

READINGS = {
    cm.Factor.TEST_WEIGHT: Decimal("56.5"),
    cm.Factor.MOISTURE: Decimal("18.2"),
    cm.Factor.BCFM: Decimal("1.2"),
    cm.Factor.DAMAGE_TOTAL: Decimal("1.0"),
}


def a_sample(**overrides) -> Sample:
    base = dict(
        probe_id="P-4417",
        commodity_code="CORN",
        drawn_at=DRAWN,
        drawn_by="jvoss",
    )
    base.update(overrides)
    return Sample(**base)


def test_a_sample_needs_a_probe_number_and_a_grader():
    with pytest.raises(BenchError):
        a_sample(probe_id="")
    with pytest.raises(BenchError):
        a_sample(drawn_by="")


def test_a_tested_sample_needs_readings():
    with pytest.raises(BenchError):
        a_sample(step=Step.TESTED)


def test_a_new_sample_is_drawn():
    assert a_sample().step is Step.DRAWN
    assert not a_sample().retained


def test_splitting_keeps_a_half_on_the_shelf():
    split = a_sample().split()
    assert split.step is Step.SPLIT
    assert split.retained


def test_a_split_can_be_told_not_to_keep_anything():
    assert not a_sample().split(retain=False).retained


def test_a_sample_cannot_skip_a_step():
    with pytest.raises(ConflictError):
        a_sample().advance(Step.TESTED)


def test_testing_records_the_instrument():
    tested = a_sample().split().test(READINGS, "GAC2500")
    assert tested.step is Step.TESTED
    assert tested.instrument == "GAC2500"
    assert tested.readings[cm.Factor.MOISTURE] == Decimal("18.2")


def test_a_test_with_no_readings_is_refused():
    with pytest.raises(BenchError):
        a_sample().split().test({}, "GAC2500")


def test_a_test_with_no_instrument_is_refused():
    with pytest.raises(BenchError):
        a_sample().split().test(READINGS, "")


def test_grading_ties_the_sample_to_a_ticket():
    tested = a_sample().split().test(READINGS, "GAC2500")
    graded, result = tested.grade(cm.CORN, "A-10441")
    assert graded.step is Step.GRADED
    assert graded.ticket_number == "A-10441"
    assert result.grade is NumericalGrade.US_1


def test_an_untested_sample_cannot_be_graded():
    with pytest.raises(ConflictError):
        a_sample().split().grade(cm.CORN, "A-10441")


def test_a_grade_needs_a_ticket():
    tested = a_sample().split().test(READINGS, "GAC2500")
    with pytest.raises(BenchError):
        tested.grade(cm.CORN, "")


def test_a_sample_of_another_commodity_is_refused():
    tested = a_sample().split().test(READINGS, "GAC2500")
    with pytest.raises(BenchError):
        tested.grade(cm.SOYBEANS, "A-10441")


def test_a_retained_sample_is_kept_for_the_retention_period():
    split = a_sample().split()
    assert split.discard_after == (DRAWN + RETENTION).date()
    assert split.is_retained_on(date(2024, 10, 20))
    assert not split.is_retained_on(date(2025, 1, 1))


def test_a_retained_sample_cannot_be_discarded_early():
    graded, _ = a_sample().split().test(READINGS, "GAC2500").grade(cm.CORN, "A-10441")
    with pytest.raises(ConflictError):
        graded.discard(date(2024, 10, 20))


def test_a_retained_sample_can_be_discarded_after_its_date():
    graded, _ = a_sample().split().test(READINGS, "GAC2500").grade(cm.CORN, "A-10441")
    thrown = graded.discard(date(2025, 1, 1))
    assert thrown.step is Step.DISCARDED
    assert not thrown.retained


def test_an_unretained_sample_can_go_at_any_time():
    graded, _ = (
        a_sample().split(retain=False).test(READINGS, "GAC2500").grade(cm.CORN, "A-1")
    )
    assert graded.discard(date(2024, 10, 4)).step is Step.DISCARDED


def a_bench() -> Bench:
    bench = Bench("HOME")
    bench.draw(a_sample())
    bench.draw(a_sample(probe_id="P-4418", drawn_at=DRAWN + timedelta(minutes=20)))
    return bench


def test_the_bench_holds_what_is_drawn():
    assert a_bench().depth() == 2


def test_the_same_probe_twice_is_a_conflict():
    bench = a_bench()
    with pytest.raises(ConflictError):
        bench.draw(a_sample())


def test_a_probe_not_on_the_bench_says_so():
    with pytest.raises(BenchError):
        a_bench().get("P-9999")


def test_the_waiting_list_is_oldest_first():
    assert [item.probe_id for item in a_bench().waiting()] == ["P-4417", "P-4418"]


def test_a_graded_sample_leaves_the_waiting_list():
    bench = a_bench()
    graded, _ = bench.get("P-4417").split().test(READINGS, "GAC2500").grade(
        cm.CORN, "A-10441"
    )
    bench.replace(graded)
    assert [item.probe_id for item in bench.waiting()] == ["P-4418"]


def test_a_sample_can_be_found_by_its_ticket():
    bench = a_bench()
    graded, _ = bench.get("P-4417").split().test(READINGS, "GAC2500").grade(
        cm.CORN, "A-10441"
    )
    bench.replace(graded)
    assert bench.for_ticket("A-10441").probe_id == "P-4417"
    assert bench.for_ticket("A-99999") is None


def test_the_shelf_lists_what_is_retained():
    bench = a_bench()
    bench.replace(bench.get("P-4417").split())
    assert [item.probe_id for item in bench.retained_on(date(2024, 10, 20))] == [
        "P-4417"
    ]


def test_nothing_is_due_for_discard_inside_the_retention():
    bench = a_bench()
    bench.replace(bench.get("P-4417").split())
    assert bench.due_for_discard(date(2024, 10, 20)) == ()


def test_the_shelf_clears_after_the_retention():
    bench = a_bench()
    bench.replace(bench.get("P-4417").split())
    assert [item.probe_id for item in bench.due_for_discard(date(2025, 1, 1))] == [
        "P-4417"
    ]
