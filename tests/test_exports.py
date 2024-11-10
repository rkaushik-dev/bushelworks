from datetime import date
from decimal import Decimal

import pytest

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.exports import (
    LINE_COLUMNS,
    SETTLEMENT_COLUMNS,
    TICKET_COLUMNS,
    ExportError,
    cell,
    check_round_trip,
    lines_to_csv,
    settlements_to_csv,
    text_field,
    tickets_to_csv,
)
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def busy(factory, rules, corn_ticket):
    from dataclasses import replace

    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, Decimal("4.35"))
        uow.commit()
    return factory


def test_a_plain_identifier_is_written_as_it_is():
    assert text_field("A-10441") == "A-10441"
    assert text_field(None) == ""


def test_an_identifier_with_leading_zeroes_is_protected():
    assert text_field("0010441") == '="0010441"'


def test_a_number_without_leading_zeroes_is_left_alone():
    assert text_field("10441") == "10441"


def test_cells_render_the_types_the_office_reads():
    assert cell(None) == ""
    assert cell(True) == "yes"
    assert cell(False) == "no"
    assert cell(Decimal("897.6324")) == "897.6324"
    assert cell(date(2024, 10, 3)) == "2024-10-03"


def test_the_ticket_export_has_a_header_and_a_row_each(busy):
    with unit_of_work(busy) as uow:
        rows = uow.tickets.for_party("MERRICK")
        text = tickets_to_csv(rows)
    assert check_round_trip(text, TICKET_COLUMNS) == 2


def test_the_ticket_export_carries_the_net_weight(busy):
    with unit_of_work(busy) as uow:
        text = tickets_to_csv(uow.tickets.for_party("MERRICK"))
    assert "52240" in text


def test_the_ticket_export_says_whether_a_ticket_is_settled(busy):
    with unit_of_work(busy) as uow:
        text = tickets_to_csv(uow.tickets.for_party("MERRICK"))
    assert ",yes" in text


def test_an_empty_ticket_export_still_has_a_header():
    text = tickets_to_csv([])
    assert check_round_trip(text, TICKET_COLUMNS) == 0


def test_the_settlement_export_has_one_row_per_statement(busy):
    with unit_of_work(busy) as uow:
        statements = [uow.settlements.get("S-2024-0001")]
        text = settlements_to_csv(statements)
    assert check_round_trip(text, SETTLEMENT_COLUMNS) == 1


def test_the_settlement_export_lists_the_tickets_it_covers(busy):
    with unit_of_work(busy) as uow:
        row = uow.settlements.row("S-2024-0001")
        statement = uow.settlements.get("S-2024-0001")
        assert row.number == statement.number
        text = settlements_to_csv([statement])
    assert "gross" in text.split("\n")[0]


def test_the_line_export_has_a_row_per_line(busy):
    with unit_of_work(busy) as uow:
        statement = uow.settlements.get("S-2024-0001")
        text = lines_to_csv([statement])
        expected = len(statement.lines)
    assert check_round_trip(text, LINE_COLUMNS) == expected


def test_the_line_export_carries_both_signs(busy):
    with unit_of_work(busy) as uow:
        statement = uow.settlements.get("S-2024-0001")
        text = lines_to_csv([statement])
    assert "gross" in text
    assert "-" in text


def test_the_line_export_numbers_the_lines_from_one(busy):
    with unit_of_work(busy) as uow:
        text = lines_to_csv([uow.settlements.get("S-2024-0001")])
    rows = text.strip().split("\n")
    assert rows[1].split(",")[1] == "1"


def test_a_round_trip_check_on_an_empty_string_is_refused():
    with pytest.raises(ExportError):
        check_round_trip("", TICKET_COLUMNS)


def test_a_round_trip_check_catches_the_wrong_header():
    with pytest.raises(ExportError):
        check_round_trip("a,b,c\n1,2,3\n", TICKET_COLUMNS)


def test_a_round_trip_check_catches_a_short_row():
    header = ",".join(SETTLEMENT_COLUMNS)
    text = f"{header}\nS-1,2024-10-20\n"
    with pytest.raises(ExportError) as caught:
        check_round_trip(text, SETTLEMENT_COLUMNS)
    assert "row 2" in str(caught.value)


def test_a_remark_with_a_comma_survives_the_round_trip(busy):
    with unit_of_work(busy) as uow:
        row = uow.tickets.row("HOME", "A-10441")
        row.remarks = "reweighed, driver had the wrong tare"
        uow.commit()
    with unit_of_work(busy) as uow:
        text = tickets_to_csv(uow.tickets.for_party("MERRICK"))
    assert check_round_trip(text, TICKET_COLUMNS) == 2
