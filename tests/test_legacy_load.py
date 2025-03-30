"""Writing the old system's file into the ticket file."""

from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.domain.commodity import Factor
from bushelworks.domain.tickets import Disposition
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.legacy import (
    load,
    load_summary,
    parse_file,
    parse_line,
    to_ticket,
)


def a_line(
    ticket="A-10441   ",
    scale_date="20241014",
    party="MRK001",
    commodity="CORN",
    location="HOME",
    gross="     78420",
    tare="     26180",
    moisture="  18.2",
    test_weight="  56.5",
    damage="   1.0",
    disposition="CASH    ",
    void=" ",
) -> str:
    return (
        ticket
        + scale_date
        + party
        + commodity
        + location
        + gross
        + tare
        + moisture
        + test_weight
        + damage
        + disposition
        + void
    )


def a_file(*lines: str) -> str:
    return "\n".join(lines or (a_line(),)) + "\n"


# The old file has no BCFM column and never had one, so every load in the
# migration supplies the house long run average once.
ASSUME = {Factor.BCFM: Decimal("1.2")}


def load_file(uow, rules, text=None, **options):
    return load(uow, parse_file(text or a_file()), rules, assume=ASSUME, **options)


@pytest.fixture
def database(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'house.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    create_all(engine_for(url))
    return url


@pytest.fixture
def factory(database):
    return session_factory(engine_for(database))


@pytest.fixture
def runner():
    return CliRunner()


def test_a_row_becomes_a_ticket():
    ticket = to_ticket(parse_line(a_line(), 1))
    assert ticket.ticket_number == "A-10441"
    assert ticket.scale_date == date(2024, 10, 14)
    assert ticket.disposition is Disposition.CASH_SALE
    assert ticket.gross_pounds == Decimal("78420")


def test_the_whole_load_goes_to_the_party_on_it():
    ticket = to_ticket(parse_line(a_line(), 1))
    assert len(ticket.splits) == 1
    assert ticket.splits[0].party_id == "MRK001"
    assert ticket.splits[0].percent == Decimal("100")


def test_the_sample_carries_what_the_old_file_had():
    ticket = to_ticket(parse_line(a_line(), 1))
    assert ticket.sample[Factor.MOISTURE] == Decimal("18.2")
    assert ticket.sample[Factor.TEST_WEIGHT] == Decimal("56.5")
    assert ticket.sample[Factor.DAMAGE_TOTAL] == Decimal("1.0")


def test_a_blank_moisture_is_left_out_rather_than_sent_as_zero():
    ticket = to_ticket(parse_line(a_line(moisture="      "), 1))
    assert Factor.MOISTURE not in ticket.sample


def test_the_remark_says_where_it_came_from():
    ticket = to_ticket(parse_line(a_line(), 7))
    assert "line 7" in ticket.remarks


def test_a_voided_row_stays_voided_on_the_ticket():
    assert to_ticket(parse_line(a_line(void="V"), 1)).voided


def test_a_clean_file_lands(factory, rules):
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules)
        uow.commit()
    assert loaded.written == ["A-10441"]
    assert loaded.is_clean


def test_the_ticket_is_on_file_afterwards(factory, rules):
    with unit_of_work(factory) as uow:
        load_file(uow, rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.tickets.exists("HOME", "A-10441")


def test_nothing_is_written_when_the_caller_does_not_commit(factory, rules):
    with unit_of_work(factory) as uow:
        load_file(uow, rules)
    with unit_of_work(factory) as uow:
        assert not uow.tickets.exists("HOME", "A-10441")


def test_a_voided_row_is_skipped(factory, rules):
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, a_file(a_line(void="V")))
    assert loaded.written == []
    assert loaded.skipped[0].reason == "voided in the old system"


def test_a_voided_row_is_skipped_before_intake_can_refuse_it(factory, rules):
    """Intake refuses a voided ticket outright, so there is no way in.

    The loader skips it first rather than letting the refusal come back as an
    error, because a hundred thousand rows of "ticket is voided" in a migration
    log buries the failures that mean something.
    """
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, a_file(a_line(void="V")))
    assert loaded.skipped[0].reason == "voided in the old system"


def test_a_second_pass_skips_what_the_first_one_wrote(factory, rules):
    with unit_of_work(factory) as uow:
        load_file(uow, rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        again = load_file(uow, rules)
    assert again.written == []
    assert again.skipped[0].reason == "already on file"


def test_a_second_pass_can_be_told_to_try_anyway(factory, rules):
    with unit_of_work(factory) as uow:
        load_file(uow, rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        again = load_file(uow, rules, skip_existing=False)
    assert again.written == []
    assert "already" in again.skipped[0].reason.lower()


def test_a_row_the_domain_refuses_is_kept_not_lost(factory, rules):
    bad = a_line(ticket="A-10442   ", commodity="OATS")
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, a_file(a_line(), bad))
    assert loaded.written == ["A-10441"]
    assert len(loaded.skipped) == 1


def test_the_rest_of_the_file_still_lands_after_a_bad_row(factory, rules):
    bad = a_line(ticket="A-10442   ", commodity="OATS")
    good = a_line(ticket="A-10443   ")
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, a_file(bad, good))
    assert loaded.written == ["A-10443"]


def test_a_clean_load_summarises_to_one_line(factory, rules):
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules)
    assert load_summary(loaded) == "1 tickets written, none skipped"


def test_a_dirty_load_names_the_reasons(factory, rules):
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, a_file(a_line(void="V")))
    assert "voided in the old system" in load_summary(loaded)


def write_file(tmp_path, text: str):
    path = tmp_path / "tickets.dat"
    path.write_text(text, encoding="utf-8")
    return str(path)


def test_the_command_dry_runs_by_default(runner, database, tmp_path):
    result = runner.invoke(
        cli,
        ["import-legacy", write_file(tmp_path, a_file()), "--assume", "bcfm=1.2"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert "dry run, nothing written" in result.output


def test_a_dry_run_leaves_the_ticket_file_alone(runner, factory, tmp_path):
    runner.invoke(cli, ["import-legacy", write_file(tmp_path, a_file()), "--assume", "bcfm=1.2"])
    with unit_of_work(factory) as uow:
        assert not uow.tickets.exists("HOME", "A-10441")


def test_the_command_writes_when_told_to(runner, factory, tmp_path):
    result = runner.invoke(
        cli,
        ["import-legacy", write_file(tmp_path, a_file()), "--assume", "bcfm=1.2", "--commit"],
        catch_exceptions=False,
    )
    assert "1 tickets written" in result.output
    with unit_of_work(factory) as uow:
        assert uow.tickets.exists("HOME", "A-10441")


def test_the_command_reports_what_it_could_not_read(runner, database, tmp_path):
    text = a_file(a_line(), "short line")
    result = runner.invoke(
        cli,
        ["import-legacy", write_file(tmp_path, text), "--assume", "bcfm=1.2"],
        catch_exceptions=False,
    )
    assert "1 refused" in result.output


def test_a_file_that_is_not_there_is_refused(runner, database):
    assert runner.invoke(cli, ["import-legacy", "nowhere.dat"]).exit_code == 2


def test_the_party_map_renumbers_the_producer(runner, factory, tmp_path):
    mapping = tmp_path / "codes.csv"
    mapping.write_text("# from the crosswalk\nMRK001,MERRICK\n", encoding="utf-8")
    runner.invoke(
        cli,
        [
            "import-legacy",
            write_file(tmp_path, a_file()),
            "--map",
            str(mapping),
            "--assume",
            "bcfm=1.2",
            "--commit",
        ],
        catch_exceptions=False,
    )
    with unit_of_work(factory) as uow:
        assert uow.tickets.row("HOME", "A-10441").party_code == "MERRICK"


def test_a_map_line_with_no_new_code_is_refused(runner, database, tmp_path):
    mapping = tmp_path / "codes.csv"
    mapping.write_text("MRK001\n", encoding="utf-8")
    result = runner.invoke(
        cli, ["import-legacy", write_file(tmp_path, a_file()), "--map", str(mapping)]
    )
    assert result.exit_code == 2


def test_a_blank_map_line_is_ignored(runner, factory, tmp_path):
    mapping = tmp_path / "codes.csv"
    mapping.write_text("\n# nothing here\n\nMRK001,MERRICK\n", encoding="utf-8")
    result = runner.invoke(
        cli,
        ["import-legacy", write_file(tmp_path, a_file()), "--map", str(mapping)],
        catch_exceptions=False,
    )
    assert result.exit_code == 0


def test_a_file_older_than_every_schedule_is_kept_not_lost(factory, rules):
    """Eleven years of files, and the shrink tables only go back so far.

    The house filed its first schedule at go live. Anything older than that
    grades against nothing, and the loader has to say so per row rather than
    stop, because the run is one file a night for eleven years.
    """
    old = a_file(a_line(scale_date="20140312"))
    with unit_of_work(factory) as uow:
        loaded = load_file(uow, rules, old)
    assert loaded.written == []
    assert "schedule" in loaded.skipped[0].reason
