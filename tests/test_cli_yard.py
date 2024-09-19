from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.deferred import deliver
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer
from bushelworks.services.warehousing import store


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
def stocked(database, rules):
    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("8000"), date(2024, 10, 3))
        deliver(uow, "D-1", "HOLLIS", "CORN", "HOME", Decimal("4000"), date(2024, 10, 8))
        uow.commit()
    return database


@pytest.fixture
def settled(database, rules, corn_ticket):
    from dataclasses import replace

    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "HOLLIS", rules
        )
        settle_producer(
            uow, "MERRICK", "HOME", rules, date(2024, 10, 20), Decimal("4.35")
        )
        settle_producer(
            uow, "HOLLIS", "HOME", rules, date(2024, 10, 20), Decimal("4.35")
        )
        uow.commit()
    return database


def test_the_group_lists_the_yard_commands(runner):
    result = runner.invoke(cli, ["--help"])
    for command in ("lots", "on-hand", "accrue", "position", "cheques"):
        assert command in result.output


def test_lots_lists_what_is_open(runner, stocked):
    result = runner.invoke(cli, ["lots"])
    assert result.exit_code == 0
    assert "L-1" in result.output
    assert "D-1" in result.output
    assert "2 open lots" in result.output


def test_lots_marks_deferred_price_separately(runner, stocked):
    result = runner.invoke(cli, ["lots"])
    assert "DP " in result.output
    assert "STO" in result.output


def test_lots_can_be_filtered_by_producer(runner, stocked):
    result = runner.invoke(cli, ["lots", "--party", "HOLLIS"])
    assert "1 open lots" in result.output
    assert "L-1" not in result.output


def test_on_hand_reports_what_is_held(runner, stocked):
    result = runner.invoke(cli, ["on-hand", "CORN"])
    assert "12000.0000 bu held for producers" in result.output


def test_a_dry_run_bills_nothing(runner, stocked):
    result = runner.invoke(cli, ["accrue", "2024-12-14"])
    assert result.exit_code == 0
    assert "would bill" in result.output
    second = runner.invoke(cli, ["accrue", "2024-12-14"])
    assert "would bill" in second.output


def test_committing_the_accrual_bills_once(runner, stocked):
    first = runner.invoke(cli, ["accrue", "2024-12-14", "--commit"])
    assert "billed" in first.output
    second = runner.invoke(cli, ["accrue", "2024-12-14", "--commit"])
    assert "0 lots" in second.output


def test_the_position_prints_as_a_board(runner, settled):
    result = runner.invoke(cli, ["position", "2024-11-01"])
    assert result.exit_code == 0
    assert "POSITION AS OF 2024-11-01" in result.output


def test_the_position_prints_as_a_hedge_sheet(runner, settled):
    result = runner.invoke(cli, ["position", "2024-11-01", "--format", "hedge"])
    assert "HEDGE SHEET" in result.output


def test_the_position_prints_as_a_csv(runner, settled):
    result = runner.invoke(cli, ["position", "2024-11-01", "--format", "csv"])
    assert result.output.startswith("as_of,commodity_code")


def test_the_cheque_run_writes_one_per_producer(runner, settled):
    result = runner.invoke(cli, ["cheques", "--first", "010441", "--on", "2024-10-24"])
    assert result.exit_code == 0
    assert "010441" in result.output
    assert "010442" in result.output
    assert "2 cheques" in result.output


def test_the_cheque_run_can_be_limited_to_a_producer(runner, settled):
    result = runner.invoke(
        cli,
        ["cheques", "--first", "010441", "--on", "2024-10-24", "--party", "MERRICK"],
    )
    assert "1 cheques" in result.output
    assert "MERRICK" in result.output


def test_a_second_cheque_run_finds_nothing(runner, settled):
    runner.invoke(cli, ["cheques", "--first", "010441", "--on", "2024-10-24"])
    result = runner.invoke(cli, ["cheques", "--first", "010443", "--on", "2024-10-25"])
    assert result.exit_code == 1
    assert "payment_error" in result.output


def test_unpaid_lists_the_settlements_waiting(runner, settled):
    result = runner.invoke(cli, ["unpaid"])
    assert "2 unpaid" in result.output


def test_voiding_a_ticket_from_the_command_line(runner, database, rules, corn_ticket):
    factory = session_factory(engine_for(database))
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    result = runner.invoke(
        cli, ["void", "HOME", "A-10441", "--reason", "reweighed"]
    )
    assert result.exit_code == 0
    assert "A-10441 voided" in result.output


def test_voiding_a_missing_ticket_exits_non_zero(runner, database):
    result = runner.invoke(cli, ["void", "HOME", "A-99999", "--reason", "x"])
    assert result.exit_code == 1
    assert "ticket_not_found" in result.output
