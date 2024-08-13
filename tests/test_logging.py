import io
import logging
from decimal import Decimal

from bushelworks.logging import (
    LOGGER_NAME,
    capture,
    configure,
    current_request_id,
    event,
    failure,
    get_logger,
    render_fields,
    render_value,
    set_request_id,
    warn,
)


def test_a_decimal_keeps_its_trailing_zeroes():
    assert render_value(Decimal("4.3500")) == "4.3500"
    assert render_value(Decimal("0")) == "0"


def test_none_renders_as_a_dash():
    assert render_value(None) == "-"


def test_booleans_render_as_words():
    assert render_value(True) == "yes"
    assert render_value(False) == "no"


def test_an_empty_string_is_visible():
    assert render_value("") == '""'


def test_a_value_with_a_space_is_quoted():
    assert render_value("Merrick Farms") == '"Merrick Farms"'


def test_a_value_with_an_equals_sign_is_quoted():
    assert render_value("a=b") == '"a=b"'


def test_a_quote_inside_a_value_is_escaped():
    assert render_value('he said "no"') == '"he said \\"no\\""'


def test_a_plain_value_is_left_alone():
    assert render_value("MERRICK") == "MERRICK"


def test_fields_render_in_the_order_they_were_given():
    text = render_fields({"ticket": "A-10441", "party": "MERRICK"})
    assert text == "ticket=A-10441 party=MERRICK"


def test_an_event_line_carries_its_fields():
    with capture() as log:
        event(
            get_logger("intake"),
            "ticket.recorded",
            ticket="A-10441",
            bushels=Decimal("897.6324"),
        )
    assert len(log.lines) == 1
    assert "ticket.recorded" in log.lines[0]
    assert "ticket=A-10441" in log.lines[0]
    assert "bushels=897.6324" in log.lines[0]


def test_the_line_starts_with_a_timestamp_and_a_level():
    with capture() as log:
        event(get_logger(), "ticket.recorded")
    parts = log.lines[0].split(" ")
    assert parts[0].startswith("20")
    assert parts[1] == "info"
    assert parts[2] == "ticket.recorded"


def test_a_warning_says_so():
    with capture() as log:
        warn(get_logger(), "contract.overfilled", contract="F-1")
    assert " warning " in log.lines[0]


def test_a_failure_says_so():
    with capture() as log:
        failure(get_logger(), "settlement.refused", party="MERRICK")
    assert " error " in log.lines[0]


def test_the_request_id_rides_along():
    set_request_id("abc123")
    try:
        with capture() as log:
            event(get_logger(), "ticket.recorded", ticket="A-10441")
        assert "request_id=abc123" in log.lines[0]
        assert current_request_id() == "abc123"
    finally:
        set_request_id(None)


def test_no_request_id_leaves_the_field_off():
    set_request_id(None)
    with capture() as log:
        event(get_logger(), "ticket.recorded")
    assert "request_id" not in log.lines[0]


def test_capture_finds_lines_by_event_name():
    with capture() as log:
        event(get_logger(), "ticket.recorded", ticket="A-1")
        event(get_logger(), "settlement.written", number="S-1")
    assert len(log.find("ticket.recorded")) == 1
    assert len(log.find("settlement.written")) == 1
    assert log.find("nothing.happened") == []


def test_configure_replaces_its_own_handler():
    stream = io.StringIO()
    configure("INFO", stream)
    configure("INFO", stream)
    logger = logging.getLogger(LOGGER_NAME)
    assert len(logger.handlers) == 1
    event(logger, "ticket.recorded", ticket="A-10441")
    assert "ticket=A-10441" in stream.getvalue()


def test_configure_honours_the_level():
    stream = io.StringIO()
    configure("ERROR", stream)
    logger = logging.getLogger(LOGGER_NAME)
    event(logger, "ticket.recorded")
    failure(logger, "settlement.refused")
    output = stream.getvalue()
    assert "ticket.recorded" not in output
    assert "settlement.refused" in output


def test_the_package_logger_does_not_propagate():
    configure("INFO", io.StringIO())
    assert not logging.getLogger(LOGGER_NAME).propagate


def test_child_loggers_are_namespaced():
    assert get_logger("intake").name == "bushelworks.intake"
    assert get_logger().name == "bushelworks"
