from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.fleet import (
    TARE_LIFE,
    Fleet,
    FleetError,
    Tare,
    Truck,
    average_tare,
)
from bushelworks.errors import ConflictError

WEIGHED = date(2024, 9, 20)
HARVEST = date(2024, 10, 3)


def a_tare(pounds="26180", weighed_on=WEIGHED) -> Tare:
    return Tare(pounds=Decimal(pounds), weighed_on=weighed_on, ticket_number="A-10400")


def a_truck(**overrides) -> Truck:
    base = dict(
        truck_id="T-14",
        party_code="MERRICK",
        description="Peterbilt hopper",
    )
    base.update(overrides)
    return Truck(**base)


def test_an_implausible_tare_is_refused():
    with pytest.raises(FleetError):
        a_tare(pounds="5000")
    with pytest.raises(FleetError):
        a_tare(pounds="90000")


def test_a_tare_is_current_for_a_month():
    tare = a_tare()
    assert tare.expires_on() == WEIGHED + TARE_LIFE
    assert tare.is_current_on(HARVEST)
    assert not tare.is_current_on(date(2024, 12, 1))


def test_a_tare_is_not_current_before_it_was_taken():
    assert not a_tare().is_current_on(date(2024, 9, 1))


def test_the_age_of_a_tare_is_counted_in_days():
    assert a_tare().age_days(HARVEST) == 13


def test_a_truck_needs_an_id_and_an_owner():
    with pytest.raises(FleetError):
        a_truck(truck_id="")
    with pytest.raises(FleetError):
        a_truck(party_code="")


def test_a_new_truck_has_no_tare():
    truck = a_truck()
    assert truck.latest_tare() is None
    assert truck.tare_on(HARVEST) is None
    assert truck.tare_is_stale(HARVEST)


def test_weighing_empty_gives_the_truck_a_tare():
    truck = a_truck().weigh_empty(a_tare())
    assert truck.latest_tare().pounds == Decimal("26180")
    assert not truck.tare_is_stale(HARVEST)


def test_the_most_recent_current_tare_is_used():
    truck = (
        a_truck()
        .weigh_empty(a_tare())
        .weigh_empty(a_tare("26420", date(2024, 10, 1)))
    )
    assert truck.tare_on(HARVEST).pounds == Decimal("26420")


def test_an_old_tare_goes_stale():
    truck = a_truck().weigh_empty(a_tare())
    assert truck.tare_is_stale(date(2024, 12, 1))
    assert truck.latest_tare() is not None


def test_weighing_the_same_truck_twice_in_a_day_is_a_conflict():
    truck = a_truck().weigh_empty(a_tare())
    with pytest.raises(ConflictError):
        truck.weigh_empty(a_tare("26200"))


def test_a_retired_truck_cannot_be_weighed():
    truck = a_truck().retire(date(2024, 11, 1))
    assert truck.is_retired
    with pytest.raises(ConflictError):
        truck.weigh_empty(a_tare())


def test_retiring_twice_is_a_conflict():
    truck = a_truck().retire(date(2024, 11, 1))
    with pytest.raises(ConflictError):
        truck.retire(date(2024, 11, 2))


def test_one_tare_shows_no_drift():
    assert a_truck().weigh_empty(a_tare()).drift() is None


def test_drift_is_measured_from_the_first_weighing():
    truck = (
        a_truck()
        .weigh_empty(a_tare())
        .weigh_empty(a_tare("27380", date(2024, 10, 1)))
    )
    assert truck.drift() == Decimal("1200")


def test_a_fleet_holds_its_trucks_in_order():
    fleet = Fleet()
    fleet.add(a_truck(truck_id="T-15"))
    fleet.add(a_truck())
    assert [truck.truck_id for truck in fleet.active()] == ["T-14", "T-15"]


def test_the_same_truck_twice_is_a_conflict():
    fleet = Fleet()
    fleet.add(a_truck())
    with pytest.raises(ConflictError):
        fleet.add(a_truck())


def test_a_truck_not_on_the_fleet_says_so():
    with pytest.raises(FleetError):
        Fleet().get("T-99")


def test_a_retired_truck_drops_off_the_active_list():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.replace(fleet.get("T-14").retire(date(2024, 11, 1)))
    assert fleet.active() == ()


def test_trucks_can_be_found_by_owner():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.add(a_truck(truck_id="T-20", party_code="HOLLIS"))
    assert [truck.truck_id for truck in fleet.for_party("HOLLIS")] == ["T-20"]


def test_the_morning_list_is_the_trucks_needing_a_tare():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.add(a_truck(truck_id="T-15"))
    fleet.replace(fleet.get("T-14").weigh_empty(a_tare()))
    assert [truck.truck_id for truck in fleet.needing_a_tare(HARVEST)] == ["T-15"]


def test_a_stale_tare_puts_a_truck_back_on_the_morning_list():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.replace(fleet.get("T-14").weigh_empty(a_tare()))
    assert fleet.needing_a_tare(date(2024, 12, 1))


def test_a_drifting_truck_is_found():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.replace(
        fleet.get("T-14")
        .weigh_empty(a_tare())
        .weigh_empty(a_tare("30180", date(2024, 10, 1)))
    )
    assert [truck.truck_id for truck in fleet.drifting()] == ["T-14"]


def test_a_settled_truck_is_not_drifting():
    fleet = Fleet()
    fleet.add(a_truck())
    fleet.replace(
        fleet.get("T-14")
        .weigh_empty(a_tare())
        .weigh_empty(a_tare("26380", date(2024, 10, 1)))
    )
    assert fleet.drifting() == ()


def test_the_average_tare_of_nothing_is_nothing():
    assert average_tare([]) is None


def test_the_average_tare_rounds_to_the_pound():
    assert average_tare([a_tare(), a_tare("26420", date(2024, 10, 1))]) == Decimal(
        "26300"
    )
