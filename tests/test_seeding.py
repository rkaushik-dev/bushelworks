from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner
from sqlalchemy import func, select

from bushelworks.cli.main import cli
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import BinRow, CommodityRow, Location, ScaleTicketRow
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.parties import agreement_for
from bushelworks.services.seeding import LOADS, describe, is_seeded, seed


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def seeded(factory, rules):
    with unit_of_work(factory) as uow:
        result = seed(uow, rules)
        uow.commit()
    return factory, result


def test_an_empty_database_is_not_seeded(factory):
    with unit_of_work(factory) as uow:
        assert not is_seeded(uow)


def test_seeding_reports_what_it_put_in(seeded):
    _, result = seeded
    assert result.locations == 2
    assert result.parties == 6
    assert result.bins == 6
    assert result.tickets == len(LOADS)
    assert not result.is_empty


def test_the_sites_are_on_file(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        codes = [row.code for row in uow.session.scalars(select(Location))]
    assert sorted(codes) == ["HOME", "LURAY"]


def test_the_commodity_master_is_on_file(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        codes = sorted(row.code for row in uow.session.scalars(select(CommodityRow)))
    assert codes == ["CORN", "HRW", "MILO", "SOYB", "SUNF"]


def test_the_producers_are_on_file(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        codes = [party.code for party in uow.parties.active()]
    assert "MERRICK" in codes
    assert len(codes) == 6


def test_the_bins_are_on_file(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        aerated = [row.is_aerated for row in uow.session.scalars(select(BinRow))]
    assert len(aerated) == 6
    assert all(aerated)


def test_the_split_agreement_is_filed(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        agreement = agreement_for(uow, "HOME-PLACE", date(2024, 10, 1))
    assert agreement.is_balanced()
    assert {share.party_id for share in agreement.shares} == {"BROCKMAN", "MERRICK"}


def test_the_tickets_are_written(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        count = uow.session.scalar(select(func.count()).select_from(ScaleTicketRow))
    assert count == len(LOADS)


def test_the_tickets_are_spread_over_the_week(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        days = {row.scale_date for row in uow.session.scalars(select(ScaleTicketRow))}
    assert len(days) > 1


def test_the_tickets_are_spread_over_the_sites(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        sites = {row.location_code for row in uow.session.scalars(select(ScaleTicketRow))}
    assert sites == {"HOME", "LURAY"}


def test_the_ticket_numbers_run_consecutively(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        numbers = sorted(
            row.ticket_number for row in uow.session.scalars(select(ScaleTicketRow))
        )
    assert numbers[0] == "A-10441"
    assert len(set(numbers)) == len(LOADS)


def test_seeding_twice_is_refused(seeded):
    factory, _ = seeded
    with unit_of_work(factory) as uow:
        with pytest.raises(ConflictError):
            seed(uow, None)


def test_seeding_is_deterministic(factory, rules):
    with unit_of_work(factory) as uow:
        seed(uow, rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        weights = [
            row.gross_pounds
            for row in uow.session.scalars(
                select(ScaleTicketRow).order_by(ScaleTicketRow.ticket_number)
            )
        ]
    assert weights[0] == Decimal("78420")


def test_the_description_is_one_line(seeded):
    _, result = seeded
    text = describe(result)
    assert "\n" not in text
    assert "6 producers" in text


def test_the_command_seeds_a_fresh_database(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'demo.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    runner = CliRunner()
    assert runner.invoke(cli, ["init-db"]).exit_code == 0
    result = runner.invoke(cli, ["seed"])
    assert result.exit_code == 0
    assert "2 sites" in result.output


def test_the_command_refuses_to_seed_twice(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'demo.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    runner = CliRunner()
    runner.invoke(cli, ["init-db"])
    runner.invoke(cli, ["seed"])
    result = runner.invoke(cli, ["seed"])
    assert result.exit_code == 1
    assert "conflict" in result.output
