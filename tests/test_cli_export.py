"""The three files the auditor asks for every February."""

import csv
import io
from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.exports import (
    LINE_COLUMNS,
    SETTLEMENT_COLUMNS,
    TICKET_COLUMNS,
)
from bushelworks.services.houses import house_rulebook
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")
SPAN = ["--from", "2024-01-01", "--to", "2025-12-31"]


@pytest.fixture
def database(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'house.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    create_all(engine_for(url))
    return url


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def weighed(database, rules, corn_ticket):
    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        for index in range(3):
            record_load(
                uow,
                replace(corn_ticket, ticket_number=f"A-{10441 + index}"),
                "MERRICK",
                rules,
            )
        uow.commit()
    return factory


@pytest.fixture
def settled(weighed):
    with unit_of_work(weighed) as uow:
        settle_producer(uow, "MERRICK", "HOME", house_rulebook(), SETTLED, SPOT)
        uow.commit()
    return weighed


def run(runner, *args):
    return runner.invoke(cli, list(args), catch_exceptions=False)


def rows(text: str) -> list[list[str]]:
    return list(csv.reader(io.StringIO(text)))


def test_an_empty_house_still_writes_a_header(runner, database):
    result = run(runner, "export-tickets")
    assert rows(result.output)[0] == list(TICKET_COLUMNS)


def test_every_ticket_gets_a_row(runner, weighed):
    assert len(rows(run(runner, "export-tickets").output)) == 4


def test_the_rows_are_in_scale_date_order(runner, weighed):
    numbers = [row[0] for row in rows(run(runner, "export-tickets").output)[1:]]
    assert numbers == sorted(numbers)


def test_a_date_window_leaves_the_rest_out(runner, weighed):
    result = run(runner, "export-tickets", "--from", "2025-01-01")
    assert len(rows(result.output)) == 1


def test_a_site_that_is_not_there_has_nothing(runner, weighed):
    result = run(runner, "export-tickets", "--location", "LURAY")
    assert len(rows(result.output)) == 1


def test_a_producer_filter_narrows_it(runner, weighed):
    assert len(rows(run(runner, "export-tickets", "--party", "MERRICK").output)) == 4
    assert len(rows(run(runner, "export-tickets", "--party", "HOLLIS").output)) == 1


def test_voided_tickets_are_left_out_by_default(runner, weighed):
    run(runner, "void", "HOME", "A-10441", "--reason", "keyed twice")
    assert len(rows(run(runner, "export-tickets").output)) == 3


def test_voided_tickets_come_back_when_asked_for(runner, weighed):
    run(runner, "void", "HOME", "A-10441", "--reason", "keyed twice")
    result = run(runner, "export-tickets", "--include-voided")
    assert len(rows(result.output)) == 4


def test_the_file_can_be_written_to_disk(runner, weighed, tmp_path):
    out = tmp_path / "tickets.csv"
    result = run(runner, "export-tickets", "--out", str(out))
    assert "3 tickets written" in result.output
    assert rows(out.read_text(encoding="utf-8"))[0] == list(TICKET_COLUMNS)


def test_the_written_file_has_no_carriage_returns(runner, weighed, tmp_path):
    out = tmp_path / "tickets.csv"
    run(runner, "export-tickets", "--out", str(out))
    assert b"\r" not in out.read_bytes()


def test_a_remark_with_a_newline_does_not_break_the_shape(runner, weighed, tmp_path):
    factory = weighed
    with unit_of_work(factory) as uow:
        row = uow.tickets.row("HOME", "A-10441")
        row.remarks = "driver says\nthe tarp was off"
        uow.commit()
    out = tmp_path / "tickets.csv"
    run(runner, "export-tickets", "--out", str(out))
    assert len(rows(out.read_text(encoding="utf-8"))) == 4


def test_the_settlement_export_needs_a_span(runner, settled):
    assert runner.invoke(cli, ["export-settlements"]).exit_code == 2


def test_a_settlement_gets_one_row(runner, settled):
    result = run(runner, "export-settlements", *SPAN)
    assert rows(result.output)[0] == list(SETTLEMENT_COLUMNS)
    assert len(rows(result.output)) == 2


def test_a_span_that_misses_it_writes_only_a_header(runner, settled):
    result = run(runner, "export-settlements", "--from", "2023-01-01", "--to", "2023-12-31")
    assert len(rows(result.output)) == 1


def test_the_settlement_row_carries_the_net(runner, settled):
    row = rows(run(runner, "export-settlements", *SPAN).output)[1]
    assert Decimal(row[SETTLEMENT_COLUMNS.index("net")]) > 0


def test_the_settlement_export_can_be_narrowed_to_a_site(runner, settled):
    result = run(runner, "export-settlements", *SPAN, "--location", "LURAY")
    assert len(rows(result.output)) == 1


def test_the_settlement_export_writes_to_a_file(runner, settled, tmp_path):
    out = tmp_path / "settlements.csv"
    result = run(runner, "export-settlements", *SPAN, "--out", str(out))
    assert "1 settlements written" in result.output


def test_the_line_export_has_a_row_for_each_printed_line(runner, settled):
    result = run(runner, "export-lines", *SPAN)
    assert rows(result.output)[0] == list(LINE_COLUMNS)
    assert len(rows(result.output)) > 2


def test_the_lines_carry_the_settlement_number(runner, settled):
    numbers = {row[0] for row in rows(run(runner, "export-lines", *SPAN).output)[1:]}
    assert len(numbers) == 1


def test_every_line_has_a_kind(runner, settled):
    kind = LINE_COLUMNS.index("kind")
    assert all(row[kind] for row in rows(run(runner, "export-lines", *SPAN).output)[1:])


def test_the_gross_line_is_signed_positive(runner, settled):
    signed = LINE_COLUMNS.index("signed")
    kind = LINE_COLUMNS.index("kind")
    gross = next(
        row
        for row in rows(run(runner, "export-lines", *SPAN).output)[1:]
        if row[kind] == "gross"
    )
    assert Decimal(gross[signed]) > 0


def test_a_deduction_line_is_signed_negative(runner, settled):
    signed = LINE_COLUMNS.index("signed")
    kind = LINE_COLUMNS.index("kind")
    charges = [
        row
        for row in rows(run(runner, "export-lines", *SPAN).output)[1:]
        if row[kind] != "gross"
    ]
    assert charges
    assert all(Decimal(row[signed]) <= 0 for row in charges)


def test_the_line_export_writes_to_a_file(runner, settled, tmp_path):
    out = tmp_path / "lines.csv"
    result = run(runner, "export-lines", *SPAN, "--out", str(out))
    assert "lines written to" in result.output


def test_the_patronage_export_keeps_a_leading_zero(runner, database, rules, corn_ticket):
    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        for index in range(2):
            record_load(
                uow,
                replace(corn_ticket, ticket_number=f"A-{10441 + index}"),
                "0041",
                rules,
            )
        settle_producer(uow, "0041", "HOME", house_rulebook(), SETTLED, SPOT)
        uow.commit()
    result = run(runner, "patronage-export", "2024")
    assert rows(result.output)[1][0] == '="0041"'


def test_a_code_that_does_not_start_with_a_zero_is_left_alone(runner, settled):
    assert rows(run(runner, "patronage-export", "2024").output)[1][0] == "MERRICK"
