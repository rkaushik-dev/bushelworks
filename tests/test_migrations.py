"""The migrations have to build the same schema the models describe.

A drift between the two is not caught by anything else: the tests build the
schema with ``create_all`` and production builds it with alembic, so a table
added to the models and forgotten in a migration passes every other test in the
suite and fails on the office box at six on a Monday.
"""

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect

from bushelworks.persistence.base import create_all, engine_for

ROOT = Path(__file__).resolve().parents[1]


def alembic_config(url: str) -> Config:
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


@pytest.fixture
def migrated(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'migrated.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    command.upgrade(alembic_config(url), "head")
    return engine_for(url)


@pytest.fixture
def built(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'built.sqlite3'}"
    engine = engine_for(url)
    create_all(engine)
    return engine


def table_names(engine) -> set[str]:
    return set(inspect(engine).get_table_names()) - {"alembic_version"}


def column_names(engine, table: str) -> set[str]:
    return {column["name"] for column in inspect(engine).get_columns(table)}


def test_the_migration_runs_from_nothing(migrated):
    assert "scale_ticket" in table_names(migrated)


def test_the_first_migration_covers_the_reference_and_ticket_tables(migrated):
    assert {
        "party",
        "location",
        "commodity",
        "document_counter",
        "split_agreement",
        "scale_ticket",
        "ticket_sample",
        "ticket_split",
    } <= table_names(migrated)


def test_the_ticket_columns_match_the_model(migrated, built):
    assert column_names(migrated, "scale_ticket") == column_names(built, "scale_ticket")


def test_the_party_columns_match_the_model(migrated, built):
    assert column_names(migrated, "party") == column_names(built, "party")


def test_the_sample_columns_match_the_model(migrated, built):
    assert column_names(migrated, "ticket_sample") == column_names(
        built, "ticket_sample"
    )


def test_the_ticket_indexes_are_created(migrated):
    names = {index["name"] for index in inspect(migrated).get_indexes("scale_ticket")}
    assert "ix_scale_ticket_scale_date" in names
    assert "ix_scale_ticket_commodity_code" in names


def test_the_unique_constraint_on_ticket_number_is_per_location(migrated):
    uniques = inspect(migrated).get_unique_constraints("scale_ticket")
    by_name = {u["name"]: u["column_names"] for u in uniques}
    assert by_name["uq_scale_ticket_location_code"] == ["location_code", "ticket_number"]


def test_the_migration_reverses(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'reversed.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.downgrade(config, "base")
    assert table_names(engine_for(url)) == set()


def test_upgrading_twice_is_a_no_op(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'twice.sqlite3'}"
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.upgrade(config, "head")
    assert "scale_ticket" in table_names(engine_for(url))
