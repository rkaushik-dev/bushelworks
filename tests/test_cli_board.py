"""The commands the office runs in September and the manager runs at eight."""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.houses import house_rulebook
from bushelworks.services.parties import open_account
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


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
def settled(database, rules, corn_ticket):
    factory = session_factory(engine_for(database))
    for party_code, count, start in (("MERRICK", 3, 10441), ("HOLLIS", 2, 20441)):
        with unit_of_work(factory) as uow:
            open_account(uow, code=party_code, name=f"{party_code.title()} Farms")
            for index in range(count):
                record_load(
                    uow,
                    replace(corn_ticket, ticket_number=f"A-{start + index}"),
                    party_code,
                    rules,
                )
            settle_producer(uow, party_code, "HOME", house_rulebook(), SETTLED, SPOT)
            uow.commit()
    return database


def run(runner, *args):
    return runner.invoke(cli, list(args), catch_exceptions=False)


def test_a_quiet_year_still_prints_a_page(runner, database):
    result = run(runner, "patronage", "2024")
    assert result.exit_code == 0
    assert "PATRONAGE BY BUSHELS" in result.output


def test_the_page_names_the_producers(runner, settled):
    result = run(runner, "patronage", "2024")
    assert "MERRICK" in result.output
    assert "Merrick Farms" in result.output


def test_the_page_fits_eighty_columns(runner, settled):
    result = run(runner, "patronage", "2024")
    assert all(len(line) <= 80 for line in result.output.splitlines())


def test_the_page_can_be_ranked_on_margin(runner, settled):
    result = run(runner, "patronage", "2024", "--order", "margin")
    assert "PATRONAGE BY MARGIN" in result.output


def test_an_order_nobody_knows_is_refused(runner, settled):
    result = runner.invoke(cli, ["patronage", "2024", "--order", "sideways"])
    assert result.exit_code == 2


def test_another_site_has_nothing_on_it(runner, settled):
    result = run(runner, "patronage", "2024", "--location", "LURAY")
    assert "MERRICK" not in result.output


def test_the_year_before_has_nothing_on_it(runner, settled):
    assert "MERRICK" not in run(runner, "patronage", "2023").output


def a_roll(tmp_path, *codes) -> str:
    path = tmp_path / "members.txt"
    path.write_text(
        "# exported from the accounting package\n" + "\n".join(codes) + "\n",
        encoding="utf-8",
    )
    return str(path)


def test_a_roll_marks_the_outsiders(runner, settled, tmp_path):
    result = run(
        runner, "patronage", "2024", "--members", a_roll(tmp_path, "MERRICK")
    )
    assert "HOLLIS    *" in result.output


def test_the_comment_line_in_a_roll_is_ignored(runner, settled, tmp_path):
    result = run(
        runner,
        "patronage",
        "2024",
        "--members",
        a_roll(tmp_path, "MERRICK", "HOLLIS"),
    )
    rows = [line for line in result.output.splitlines() if "Farms" in line]
    assert rows
    assert not any("*" in line for line in rows)


def test_a_roll_that_is_not_there_is_refused(runner, settled):
    result = runner.invoke(cli, ["patronage", "2024", "--members", "nowhere.txt"])
    assert result.exit_code == 2


def test_the_export_is_csv_on_standard_output(runner, settled):
    result = run(runner, "patronage-export", "2024")
    assert result.output.splitlines()[0].startswith("party_code,")


def test_the_export_has_a_row_for_each_producer(runner, settled):
    result = run(runner, "patronage-export", "2024")
    assert len(result.output.strip().splitlines()) == 3


def test_the_export_can_be_written_to_a_file(runner, settled, tmp_path):
    out = tmp_path / "patronage.csv"
    result = run(runner, "patronage-export", "2024", "--out", str(out))
    assert "2 producers written" in result.output
    assert out.read_text(encoding="utf-8").startswith("party_code,")


def test_the_written_file_has_no_carriage_returns(runner, settled, tmp_path):
    out = tmp_path / "patronage.csv"
    run(runner, "patronage-export", "2024", "--out", str(out))
    assert b"\r" not in out.read_bytes()


def test_an_allocation_names_every_member(runner, settled):
    result = run(runner, "allocate", "2024", "--pool", "10000.00")
    assert "MERRICK" in result.output
    assert "HOLLIS" in result.output


def test_an_allocation_shows_what_is_left_unallocated(runner, settled):
    result = run(runner, "allocate", "2024", "--pool", "10000.00")
    assert "UNALLOCATED" in result.output


def test_the_whole_pool_goes_out_when_nothing_is_too_small(runner, settled):
    result = run(runner, "allocate", "2024", "--pool", "10000.00")
    line = next(
        row for row in result.output.splitlines() if row.startswith("UNALLOCATED")
    )
    assert line.split()[-1] == "0.00"


def test_a_share_under_the_minimum_stays_in_the_pool(runner, settled):
    result = run(
        runner, "allocate", "2024", "--pool", "10.00", "--minimum", "9.00"
    )
    assert "HOLLIS" not in result.output


def test_a_non_member_gets_nothing(runner, settled, tmp_path):
    result = run(
        runner,
        "allocate",
        "2024",
        "--pool",
        "10000.00",
        "--members",
        a_roll(tmp_path, "MERRICK"),
    )
    assert "HOLLIS" not in result.output


def test_a_pool_that_is_not_a_number_is_refused(runner, settled):
    result = runner.invoke(cli, ["allocate", "2024", "--pool", "lots"])
    assert result.exit_code == 2


def test_the_top_list_is_biggest_first(runner, settled):
    lines = run(runner, "top", "2024").output.splitlines()
    assert "MERRICK" in lines[0]
    assert "HOLLIS" in lines[1]


def test_the_top_list_can_be_cut_short(runner, settled):
    result = run(runner, "top", "2024", "--count", "1")
    assert "HOLLIS" not in result.output
    assert "these 1 hold" in result.output


def test_an_empty_year_names_nobody(runner, database):
    assert run(runner, "top", "2024").output.strip() == ""


def test_the_basis_is_cash_less_futures(runner):
    assert run(runner, "basis", "4.1000", "4.3225").output.strip() == "-0.2225"


def test_a_basis_that_is_not_a_number_is_refused(runner):
    assert runner.invoke(cli, ["basis", "high", "4.32"]).exit_code == 2


def test_a_carry_that_beats_storage_says_store(runner):
    result = run(
        runner, "carry", "4.20", "4.45", "--months", "3", "--storage", "0.05"
    )
    assert result.output.strip().endswith("store")
    assert "0.1000 per bushel" in result.output


def test_a_carry_that_does_not_cover_storage_says_sell(runner):
    result = run(
        runner, "carry", "4.20", "4.25", "--months", "3", "--storage", "0.05"
    )
    assert result.output.strip().endswith("sell")


def test_a_carry_over_no_months_is_refused(runner):
    result = runner.invoke(
        cli, ["carry", "4.20", "4.45", "--months", "0", "--storage", "0.05"]
    )
    assert result.exit_code == 1
    assert "hedge_error" in result.output
