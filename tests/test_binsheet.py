from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import BinRow
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.binsheet import WIDTH, line_for, render, room_for, to_csv
from bushelworks.services.binyard import bins_at, put_away, record_reading, take_out_of_service

TAKEN = datetime(2024, 11, 27, 6, 0, tzinfo=UTC)

SAMPLE = {
    Factor.MOISTURE: Decimal("14.0"),
    Factor.TEST_WEIGHT: Decimal("56.0"),
    Factor.DAMAGE_TOTAL: Decimal("1.0"),
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
        for bin_id, capacity in (("B-01", "40000"), ("B-02", "100000")):
            uow.session.add(
                BinRow(
                    bin_id=bin_id,
                    location_code="HOME",
                    capacity_bushels=Decimal(capacity),
                    is_aerated=True,
                )
            )
        uow.flush()
        put_away(uow, "HOME", "CORN", Decimal("20000"), SAMPLE, bin_id="B-02")
        uow.commit()
    return factory


def sheet(factory, since=None):
    with unit_of_work(factory) as uow:
        return [line_for(row, since) for row in bins_at(uow, "HOME")]


def test_an_empty_bin_shows_no_crop(yard):
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-01"].commodity_code == ""
    assert lines["B-01"].bushels == Decimal("0.0000")


def test_a_filled_bin_shows_what_is_in_it(yard):
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].commodity_code == "CORN"
    assert lines["B-02"].moisture == Decimal("14.0000")


def test_the_percentage_full_is_of_the_whole_bin(yard):
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].percent_full == Decimal("20.0")


def test_the_room_left_is_to_the_working_fill(yard):
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].headroom == Decimal("77000.0000")


def test_a_bin_with_no_readings_has_no_temperature(yard):
    assert all(line.warmest is None for line in sheet(yard))
    assert not any(line.is_warm for line in sheet(yard))


def test_the_warmest_reading_is_the_one_shown(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("12.0"))
        record_reading(
            uow, "HOME", "B-02", TAKEN + timedelta(hours=6), Decimal("24.0"), cable=2
        )
        uow.commit()
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].warmest == Decimal("24.0000")
    assert lines["B-02"].is_warm


def test_a_warm_bin_with_the_fan_off_needs_attention(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("24.0"), fan_running=False)
        uow.commit()
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].needs_attention


def test_a_warm_bin_with_the_fan_on_does_not(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("24.0"), fan_running=True)
        uow.commit()
    lines = {line.bin_id: line for line in sheet(yard)}
    assert lines["B-02"].is_warm
    assert not lines["B-02"].needs_attention


def test_older_readings_can_be_left_off(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("24.0"))
        uow.commit()
    lines = {line.bin_id: line for line in sheet(yard, since=TAKEN + timedelta(days=1))}
    assert lines["B-02"].warmest is None


def test_the_sheet_fits_its_width(yard):
    text = render(sheet(yard), "HOME")
    assert all(len(line) <= WIDTH for line in text.split("\n"))


def test_the_sheet_names_the_site_and_the_bins(yard):
    text = render(sheet(yard), "HOME")
    assert "BIN SHEET  HOME" in text
    assert "B-01" in text
    assert "B-02" in text


def test_the_sheet_totals_the_yard(yard):
    text = render(sheet(yard), "HOME")
    assert "TOTAL" in text
    assert "20,000" in text or "20000" in text


def test_a_bin_out_of_service_is_marked(yard):
    from datetime import date

    with unit_of_work(yard) as uow:
        take_out_of_service(uow, "HOME", "B-01", "fan motor", date(2024, 11, 27))
        uow.commit()
    text = render(sheet(yard), "HOME")
    assert "OUT" in text


def test_a_bin_that_needs_looking_at_is_called_out(yard):
    with unit_of_work(yard) as uow:
        record_reading(uow, "HOME", "B-02", TAKEN, Decimal("26.0"))
        uow.commit()
    text = render(sheet(yard), "HOME")
    assert "LOOK AT TODAY: B-02" in text


def test_a_quiet_yard_calls_nothing_out(yard):
    assert "LOOK AT TODAY" not in render(sheet(yard), "HOME")


def test_the_csv_has_a_row_per_bin(yard):
    text = to_csv(sheet(yard))
    rows = text.strip().split("\n")
    assert rows[0].startswith("bin_id,commodity_code")
    assert len(rows) == 3


def test_the_csv_leaves_an_unmeasured_factor_blank(yard):
    text = to_csv(sheet(yard))
    assert ",," in text


def test_the_room_for_a_commodity_skips_the_bins_holding_something_else(yard):
    with unit_of_work(yard) as uow:
        put_away(uow, "HOME", "SOYB", Decimal("1000"), SAMPLE, bin_id="B-01")
        uow.commit()
    lines = sheet(yard)
    assert room_for(lines, "CORN") == Decimal("77000.0000")


def test_the_room_skips_a_bin_out_of_service(yard):
    from datetime import date

    with unit_of_work(yard) as uow:
        take_out_of_service(uow, "HOME", "B-01", "fan motor", date(2024, 11, 27))
        uow.commit()
    lines = sheet(yard)
    assert room_for(lines, "CORN") == Decimal("77000.0000")
