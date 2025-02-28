from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import BinRow
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.binyard import (
    BinNotFound,
    BinYardError,
    bin_at,
    bins_at,
    choose_bin,
    draw_from,
    put_away,
    record_reading,
    take_out_of_service,
    warm_bins,
    working_headroom,
    yard_total,
)

TAKEN = datetime(2024, 11, 27, 6, 0, tzinfo=UTC)

SAMPLE = {
    Factor.MOISTURE: Decimal("14.0"),
    Factor.TEST_WEIGHT: Decimal("56.0"),
    Factor.DAMAGE_TOTAL: Decimal("1.0"),
}

WET = {
    Factor.MOISTURE: Decimal("18.0"),
    Factor.TEST_WEIGHT: Decimal("54.0"),
    Factor.DAMAGE_TOTAL: Decimal("3.0"),
}


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def yard(factory):
    with unit_of_work(factory) as uow:
        for bin_id, capacity in (
            ("B-01", "40000"),
            ("B-02", "100000"),
            ("B-03", "250000"),
        ):
            uow.session.add(
                BinRow(
                    bin_id=bin_id,
                    location_code="HOME",
                    capacity_bushels=Decimal(capacity),
                    is_aerated=True,
                )
            )
        uow.commit()
    return factory


def test_a_missing_bin_says_so(yard):
    with unit_of_work(yard) as uow:
        with pytest.raises(BinNotFound):
            bin_at(uow, "HOME", "B-99")


def test_the_yard_lists_its_bins_in_order(yard):
    with unit_of_work(yard) as uow:
        assert [row.bin_id for row in bins_at(uow, "HOME")] == ["B-01", "B-02", "B-03"]


def test_an_empty_bin_has_working_headroom(yard):
    with unit_of_work(yard) as uow:
        assert working_headroom(bin_at(uow, "HOME", "B-02")) == Decimal("97000.0000")


def test_a_load_goes_into_the_smallest_empty_bin_that_takes_it(yard):
    with unit_of_work(yard) as uow:
        placed = put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE)
        uow.commit()
    assert placed.bin_id == "B-01"


def test_the_next_load_finishes_the_bin_already_holding_corn(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE)
        placed = put_away(uow, "HOME", "CORN", Decimal("500"), SAMPLE)
        uow.commit()
    assert placed.bin_id == "B-01"


def test_a_second_load_blends_with_the_first(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE, bin_id="B-02")
        put_away(uow, "HOME", "CORN", Decimal("1000"), WET, bin_id="B-02")
        row = bin_at(uow, "HOME", "B-02")
        assert row.moisture == Decimal("16.0000")
        assert row.test_weight == Decimal("55.0000")


def test_the_blend_is_weighted_by_bushels(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("3000"), SAMPLE, bin_id="B-02")
        put_away(uow, "HOME", "CORN", Decimal("1000"), WET, bin_id="B-02")
        assert bin_at(uow, "HOME", "B-02").moisture == Decimal("15.0000")


def test_a_factor_the_load_was_not_tested_for_leaves_the_blend_alone(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE, bin_id="B-02")
        put_away(
            uow,
            "HOME",
            "CORN",
            Decimal("1000"),
            {Factor.MOISTURE: Decimal("18.0")},
            bin_id="B-02",
        )
        assert bin_at(uow, "HOME", "B-02").test_weight == Decimal("56.0000")


def test_a_bin_will_not_take_another_commodity(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE, bin_id="B-02")
        with pytest.raises(ConflictError):
            put_away(uow, "HOME", "SOYB", Decimal("1000"), SAMPLE, bin_id="B-02")


def test_a_bin_out_of_service_will_not_take_anything(yard):
    with unit_of_work(yard) as uow:
        take_out_of_service(uow, "HOME", "B-02", "fan motor", date(2024, 11, 27))
        with pytest.raises(ConflictError):
            put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE, bin_id="B-02")


def test_a_bin_out_of_service_is_not_chosen(yard):
    with unit_of_work(yard) as uow:
        take_out_of_service(uow, "HOME", "B-01", "fan motor", date(2024, 11, 27))
        assert choose_bin(uow, "HOME", "CORN", Decimal("1000")).bin_id == "B-02"


def test_taking_a_bin_out_twice_is_a_conflict(yard):
    with unit_of_work(yard) as uow:
        take_out_of_service(uow, "HOME", "B-01", "fan motor", date(2024, 11, 27))
        with pytest.raises(ConflictError):
            take_out_of_service(uow, "HOME", "B-01", "again", date(2024, 11, 28))


def test_taking_a_bin_out_needs_a_reason(yard):
    with unit_of_work(yard) as uow:
        with pytest.raises(BinYardError):
            take_out_of_service(uow, "HOME", "B-01", "", date(2024, 11, 27))


def test_a_load_bigger_than_the_yard_has_nowhere_to_go(yard):
    with unit_of_work(yard) as uow:
        with pytest.raises(BinYardError):
            put_away(uow, "HOME", "CORN", Decimal("500000"), SAMPLE)


def test_putting_nothing_away_is_refused(yard):
    with unit_of_work(yard) as uow:
        with pytest.raises(BinYardError):
            put_away(uow, "HOME", "CORN", Decimal("0"), SAMPLE)


def test_a_load_past_the_working_fill_is_refused(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("38000"), SAMPLE, bin_id="B-01")
        with pytest.raises(BinYardError):
            put_away(uow, "HOME", "CORN", Decimal("2000"), SAMPLE, bin_id="B-01")


def test_drawing_takes_grain_out(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("10000"), SAMPLE, bin_id="B-02")
        row = draw_from(uow, "HOME", "B-02", Decimal("4000"))
        assert row.bushels == Decimal("6000.0000")
        assert row.moisture == Decimal("14.0000")


def test_emptying_a_bin_clears_what_it_held(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("10000"), SAMPLE, bin_id="B-02")
        row = draw_from(uow, "HOME", "B-02", Decimal("10000"))
        assert row.commodity_code is None
        assert row.moisture is None


def test_drawing_more_than_is_there_is_refused(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("1000"), SAMPLE, bin_id="B-02")
        with pytest.raises(BinYardError):
            draw_from(uow, "HOME", "B-02", Decimal("1001"))


def test_drawing_nothing_is_refused(yard):
    with unit_of_work(yard) as uow:
        with pytest.raises(BinYardError):
            draw_from(uow, "HOME", "B-02", Decimal("0"))


def test_the_yard_totals_what_is_in_it(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "CORN", Decimal("10000"), SAMPLE, bin_id="B-02")
        put_away(uow, "HOME", "SOYB", Decimal("5000"), SAMPLE, bin_id="B-03")
        assert yard_total(uow, "HOME") == Decimal("15000.0000")
        assert yard_total(uow, "HOME", "CORN") == Decimal("10000.0000")


def test_a_reading_is_logged(yard):
    with unit_of_work(yard) as uow:
        reading = record_reading(
            uow, "HOME", "B-02", TAKEN, Decimal("12.4"), fan_running=True
        )
        uow.commit()
    assert reading.cable == 1
    assert not reading.is_warm


def test_a_warm_reading_puts_the_bin_on_the_list(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("24.0"))
        uow.commit()
    with unit_of_work(yard) as uow:
        found = warm_bins(uow, "HOME", TAKEN - timedelta(days=1))
        assert [row.bin_id for row in found] == ["B-02"]


def test_a_cool_yard_has_no_warm_bins(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("12.4"))
        uow.commit()
    with unit_of_work(yard) as uow:
        assert warm_bins(uow, "HOME", TAKEN - timedelta(days=1)) == []


def test_an_old_warm_reading_falls_off_the_list(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("24.0"))
        uow.commit()
    with unit_of_work(yard) as uow:
        assert warm_bins(uow, "HOME", TAKEN + timedelta(days=1)) == []
