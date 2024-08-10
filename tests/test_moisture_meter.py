from decimal import Decimal

import pytest

from bushelworks.devices.moisture_meter import (
    MeterError,
    check_calibration,
    parse_batch,
    parse_report,
    temperature_warning,
)
from bushelworks.domain.commodity import Factor

REPORT = """GAC2500-UGMA
SAMPLE: A-10441-2
CALIB: YELLOW CORN 12.4
MOIST: 18.2 %
TEST WT: 56.5 LB/BU
TEMP: 14.2 C
END
"""


def test_a_report_reads_into_a_reading():
    reading = parse_report(REPORT)
    assert reading.sample_id == "A-10441-2"
    assert reading.commodity_code == "CORN"
    assert reading.moisture == Decimal("18.2")
    assert reading.test_weight == Decimal("56.5")
    assert reading.temperature == Decimal("14.2")
    assert reading.instrument == "GAC2500-UGMA"


def test_the_calibration_name_is_kept_without_its_version():
    assert parse_report(REPORT).calibration == "YELLOW CORN"


def test_the_reading_becomes_grade_factors():
    sample = parse_report(REPORT).as_sample()
    assert sample[Factor.MOISTURE] == Decimal("18.2")
    assert sample[Factor.TEST_WEIGHT] == Decimal("56.5")


def test_a_report_without_a_test_weight_still_reads():
    text = REPORT.replace("TEST WT: 56.5 LB/BU\n", "")
    reading = parse_report(text)
    assert reading.test_weight is None
    assert Factor.TEST_WEIGHT not in reading.as_sample()


def test_a_sample_id_with_a_colon_survives():
    text = REPORT.replace("A-10441-2", "A-10441:2")
    assert parse_report(text).sample_id == "A-10441:2"


def test_a_truncated_report_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("END\n", ""))


def test_an_empty_report_is_refused():
    with pytest.raises(MeterError):
        parse_report("")


def test_a_report_missing_the_moisture_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("MOIST: 18.2 %\n", ""))


def test_a_report_missing_the_calibration_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("CALIB: YELLOW CORN 12.4\n", ""))


def test_a_line_with_no_colon_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("TEMP: 14.2 C", "TEMP 14.2 C"))


def test_an_unknown_calibration_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("YELLOW CORN", "CANOLA"))


def test_a_moisture_that_is_not_a_number_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("18.2 %", "WET %"))


def test_an_impossible_moisture_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("18.2 %", "84.0 %"))


def test_an_implausible_test_weight_is_refused():
    with pytest.raises(MeterError):
        parse_report(REPORT.replace("56.5 LB/BU", "12.0 LB/BU"))


def test_the_reading_knows_which_commodity_it_belongs_to():
    reading = parse_report(REPORT)
    assert reading.matches("CORN")
    assert not reading.matches("SOYB")


def test_the_wrong_calibration_is_caught():
    reading = parse_report(REPORT)
    check_calibration(reading, "CORN")
    with pytest.raises(MeterError) as caught:
        check_calibration(reading, "SOYB")
    assert "YELLOW CORN" in str(caught.value)


def test_a_batch_reads_every_report():
    text = REPORT + REPORT.replace("A-10441-2", "A-10442-1")
    readings = parse_batch(text)
    assert [reading.sample_id for reading in readings] == ["A-10441-2", "A-10442-1"]


def test_a_bad_report_in_a_batch_does_not_cost_the_others():
    bad = REPORT.replace("YELLOW CORN", "CANOLA")
    text = REPORT + bad + REPORT.replace("A-10441-2", "A-10443-1")
    readings = parse_batch(text)
    assert [reading.sample_id for reading in readings] == ["A-10441-2", "A-10443-1"]


def test_an_empty_spool_reads_as_nothing():
    assert parse_batch("") == []


def test_a_cold_sample_is_flagged():
    reading = parse_report(REPORT.replace("14.2 C", "-3.0 C"))
    assert "too cold" in temperature_warning(reading)


def test_a_warm_sample_is_flagged():
    reading = parse_report(REPORT.replace("14.2 C", "38.0 C"))
    assert "too warm" in temperature_warning(reading)


def test_a_sample_in_range_is_not_flagged():
    assert temperature_warning(parse_report(REPORT)) is None


def test_a_report_with_no_temperature_cannot_be_flagged():
    text = REPORT.replace("TEMP: 14.2 C\n", "")
    assert temperature_warning(parse_report(text)) is None


def test_a_soybean_report_maps_to_the_right_code():
    text = REPORT.replace("YELLOW CORN 12.4", "SOYBEANS 11.0").replace(
        "18.2 %", "12.8 %"
    )
    assert parse_report(text).commodity_code == "SOYB"
