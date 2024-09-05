from datetime import datetime, timedelta

import pytest

from bushelworks.domain.driveway import (
    Driveway,
    DrivewayError,
    Priority,
    Stage,
    Truck,
)
from bushelworks.errors import ConflictError

EIGHT = datetime(2024, 10, 3, 8, 0)


def a_truck(truck_id="T-01", minutes=0, **overrides) -> Truck:
    base = dict(
        truck_id=truck_id,
        party_code="MERRICK",
        commodity_code="CORN",
        arrived_at=EIGHT + timedelta(minutes=minutes),
    )
    base.update(overrides)
    return Truck(**base)


def test_a_truck_needs_an_id():
    with pytest.raises(DrivewayError):
        a_truck(truck_id="")


def test_a_probed_truck_needs_a_sample_id():
    with pytest.raises(DrivewayError):
        a_truck(stage=Stage.PROBED)


def test_a_new_truck_is_waiting():
    assert a_truck().priority is Priority.WAITING


def test_a_company_truck_outranks_a_waiting_one():
    assert a_truck(is_company=True).priority is Priority.COMPANY
    assert Priority.COMPANY < Priority.WAITING


def test_a_probed_truck_outranks_a_company_one():
    probed = a_truck().advance(Stage.PROBED, "P-1")
    assert probed.priority is Priority.PROBED
    assert Priority.PROBED < Priority.COMPANY


def test_a_truck_on_the_scale_outranks_everything():
    on_scale = a_truck().advance(Stage.PROBED, "P-1").advance(Stage.ON_SCALE)
    assert on_scale.priority is Priority.ON_SCALE


def test_a_truck_cannot_skip_a_stage():
    with pytest.raises(ConflictError):
        a_truck().advance(Stage.ON_SCALE)


def test_a_truck_cannot_go_backwards():
    probed = a_truck().advance(Stage.PROBED, "P-1")
    with pytest.raises(ConflictError):
        probed.advance(Stage.ARRIVED)


def test_probing_needs_a_sample_id():
    with pytest.raises(DrivewayError):
        a_truck().advance(Stage.PROBED)


def test_the_sample_id_is_kept_through_the_later_stages():
    truck = a_truck().advance(Stage.PROBED, "P-1").advance(Stage.ON_SCALE)
    assert truck.probe_id == "P-1"


def test_waiting_time_is_measured_from_arrival():
    assert a_truck().waiting_for(EIGHT + timedelta(minutes=25)) == timedelta(minutes=25)


def test_a_clock_before_arrival_is_refused():
    with pytest.raises(DrivewayError):
        a_truck(minutes=10).waiting_for(EIGHT)


def a_driveway() -> Driveway:
    yard = Driveway("HOME")
    yard.arrive(a_truck("T-01", minutes=0))
    yard.arrive(a_truck("T-02", minutes=5))
    yard.arrive(a_truck("T-03", minutes=10, is_company=True))
    return yard


def test_the_queue_is_deep_as_the_trucks_on_it():
    assert a_driveway().depth() == 3


def test_the_same_truck_twice_is_a_conflict():
    yard = a_driveway()
    with pytest.raises(ConflictError):
        yard.arrive(a_truck("T-01"))


def test_a_truck_not_on_the_driveway_says_so():
    with pytest.raises(DrivewayError):
        a_driveway().get("T-99")


def test_the_company_truck_goes_before_the_older_producers():
    assert a_driveway().next_up().truck_id == "T-03"


def test_a_probed_truck_goes_before_the_company_one():
    yard = a_driveway()
    yard.advance("T-02", Stage.PROBED, "P-2")
    assert yard.next_up().truck_id == "T-02"


def test_two_probed_trucks_go_oldest_first():
    yard = a_driveway()
    yard.advance("T-02", Stage.PROBED, "P-2")
    yard.advance("T-01", Stage.PROBED, "P-1")
    assert [t.truck_id for t in yard.queue()[:2]] == ["T-01", "T-02"]


def test_the_truck_on_the_scale_is_first_of_all():
    yard = a_driveway()
    yard.advance("T-02", Stage.PROBED, "P-2")
    yard.advance("T-02", Stage.ON_SCALE)
    assert yard.next_up().truck_id == "T-02"
    assert yard.on_scale().truck_id == "T-02"


def test_only_one_truck_fits_on_the_scale():
    yard = a_driveway()
    yard.advance("T-02", Stage.PROBED, "P-2")
    yard.advance("T-02", Stage.ON_SCALE)
    yard.advance("T-01", Stage.PROBED, "P-1")
    with pytest.raises(ConflictError):
        yard.advance("T-01", Stage.ON_SCALE)


def test_an_empty_driveway_has_nobody_on_the_scale():
    assert Driveway("HOME").on_scale() is None
    assert Driveway("HOME").next_up() is None


def test_a_finished_truck_leaves_the_queue():
    yard = a_driveway()
    yard.advance("T-01", Stage.PROBED, "P-1")
    yard.advance("T-01", Stage.ON_SCALE)
    yard.advance("T-01", Stage.DUMPING)
    yard.advance("T-01", Stage.DONE)
    assert yard.depth() == 2
    with pytest.raises(DrivewayError):
        yard.get("T-01")


def test_a_truck_can_be_pulled_out_of_the_queue():
    yard = a_driveway()
    pulled = yard.leave("T-02")
    assert pulled.truck_id == "T-02"
    assert yard.depth() == 2


def test_pulling_a_truck_that_is_not_there_is_refused():
    with pytest.raises(DrivewayError):
        a_driveway().leave("T-99")


def test_the_longest_wait_is_the_oldest_arrival():
    assert a_driveway().waiting_longest(EIGHT + timedelta(minutes=30)).truck_id == "T-01"


def test_an_empty_driveway_has_nobody_waiting():
    assert Driveway("HOME").waiting_longest(EIGHT) is None


def test_trucks_over_the_wait_limit_are_named():
    yard = a_driveway()
    over = yard.over_wait(EIGHT + timedelta(minutes=40), timedelta(minutes=30))
    assert [t.truck_id for t in over] == ["T-01", "T-02"]


def test_nobody_is_over_the_limit_on_a_quiet_morning():
    yard = a_driveway()
    assert yard.over_wait(EIGHT + timedelta(minutes=12), timedelta(minutes=35)) == ()


def test_the_queue_is_counted_by_commodity():
    yard = a_driveway()
    yard.arrive(a_truck("T-04", minutes=12, commodity_code="SOYB"))
    assert yard.by_commodity() == {"CORN": 3, "SOYB": 1}
