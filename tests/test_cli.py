from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import DECIMAL, cli
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.scalehouse import record_load


@pytest.fixture
def database(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'house.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    engine = engine_for(url)
    create_all(engine)
    return url


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def loaded(database, rules, corn_ticket):
    from dataclasses import replace

    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        uow.commit()
    return database


def test_the_decimal_parameter_parses_exactly():
    assert DECIMAL.convert("4.3500", None, None) == Decimal("4.3500")


def test_the_decimal_parameter_refuses_nonsense(runner):
    result = runner.invoke(cli, ["settle", "MERRICK", "--location", "HOME", "--price", "x"])
    assert result.exit_code != 0
    assert "is not a number" in result.output


def test_help_lists_the_commands(runner):
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    for command in ("settle", "tickets", "grade", "register", "statement", "init-db"):
        assert command in result.output


def test_init_db_builds_the_schema(runner, tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'fresh.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    result = runner.invoke(cli, ["init-db"])
    assert result.exit_code == 0
    assert "schema built" in result.output


def test_tickets_lists_what_is_waiting(runner, loaded):
    result = runner.invoke(cli, ["tickets"])
    assert result.exit_code == 0
    assert "A-10441" in result.output
    assert "A-10442" in result.output
    assert "2 tickets waiting" in result.output


def test_tickets_can_be_filtered_by_party(runner, loaded):
    result = runner.invoke(cli, ["tickets", "--party", "HOLLIS"])
    assert "0 tickets waiting" in result.output


def test_grade_prints_the_worked_numbers(runner, loaded):
    result = runner.invoke(cli, ["grade", "HOME", "A-10441"])
    assert result.exit_code == 0
    assert "clean bushels 932.8571" in result.output
    assert "dry bushels   897.6324" in result.output
    assert "grade         US 1" in result.output


def test_grading_a_missing_ticket_exits_non_zero(runner, loaded):
    result = runner.invoke(cli, ["grade", "HOME", "A-99999"])
    assert result.exit_code == 1
    assert "ticket_not_found" in result.output


def test_settle_prints_the_statement(runner, loaded):
    result = runner.invoke(
        cli,
        [
            "settle",
            "MERRICK",
            "--location",
            "HOME",
            "--price",
            "4.3500",
            "--on",
            "2024-10-20",
        ],
    )
    assert result.exit_code == 0
    assert "SETTLEMENT STATEMENT" in result.output
    assert "NET PAYABLE" in result.output


def test_settle_can_be_quiet(runner, loaded):
    result = runner.invoke(
        cli,
        [
            "settle",
            "MERRICK",
            "--location",
            "HOME",
            "--price",
            "4.3500",
            "--on",
            "2024-10-20",
            "--no-print",
        ],
    )
    assert result.exit_code == 0
    assert result.output.startswith("S-2024-0001  net ")


def test_settling_a_producer_with_nothing_waiting_exits_non_zero(runner, database):
    result = runner.invoke(
        cli, ["settle", "HOLLIS", "--location", "HOME", "--price", "4.35"]
    )
    assert result.exit_code == 1
    assert "nothing_to_settle" in result.output


def test_the_register_prints_after_settling(runner, loaded):
    runner.invoke(
        cli,
        [
            "settle",
            "MERRICK",
            "--location",
            "HOME",
            "--price",
            "4.3500",
            "--on",
            "2024-10-20",
            "--no-print",
        ],
    )
    result = runner.invoke(cli, ["register", "2024-10-20"])
    assert "SETTLEMENT REGISTER" in result.output
    assert "1 statements" in result.output


def test_a_statement_can_be_reprinted(runner, loaded):
    runner.invoke(
        cli,
        [
            "settle",
            "MERRICK",
            "--location",
            "HOME",
            "--price",
            "4.3500",
            "--on",
            "2024-10-20",
            "--no-print",
        ],
    )
    result = runner.invoke(cli, ["statement", "S-2024-0001"])
    assert result.exit_code == 0
    assert "S-2024-0001" in result.output


def test_reprinting_a_missing_statement_exits_non_zero(runner, database):
    result = runner.invoke(cli, ["statement", "S-9999"])
    assert result.exit_code == 1
    assert "settlement_not_found" in result.output


def test_the_database_can_be_overridden_on_the_command_line(runner, tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'other.sqlite3'}"
    result = runner.invoke(cli, ["--database", url, "init-db"])
    assert result.exit_code == 0
    assert url in result.output
