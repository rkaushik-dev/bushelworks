"""The migrations have to build the same schema the models describe.

A drift between the two is not caught by anything else: the tests build the
schema with ``create_all`` and production builds it with alembic, so a table
added to the models and forgotten in a migration passes every other test in the
suite and fails on the office box at six on a Monday.

The two databases are built once for the module rather than once per test. An
alembic run from nothing takes about a second and a half, and at ten tests that
was the slowest thing in the suite by a factor of five. Nothing here writes to
either database, so sharing them is safe.
"""

import os
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


def run_upgrade(url: str, revision: str = "head") -> None:
    """Run alembic with the environment pointed at this database.

    ``migrations/env.py`` reads the url out of the settings rather than the ini,
    so the environment variable has to be set for the length of the call. It is
    put back afterwards because the API tests read the same variable.
    """
    previous = os.environ.get("BUSHELWORKS_DATABASE_URL")
    os.environ["BUSHELWORKS_DATABASE_URL"] = url
    try:
        command.upgrade(alembic_config(url), revision)
    finally:
        if previous is None:
            os.environ.pop("BUSHELWORKS_DATABASE_URL", None)
        else:
            os.environ["BUSHELWORKS_DATABASE_URL"] = previous


def run_downgrade(url: str, revision: str) -> None:
    previous = os.environ.get("BUSHELWORKS_DATABASE_URL")
    os.environ["BUSHELWORKS_DATABASE_URL"] = url
    try:
        command.downgrade(alembic_config(url), revision)
    finally:
        if previous is None:
            os.environ.pop("BUSHELWORKS_DATABASE_URL", None)
        else:
            os.environ["BUSHELWORKS_DATABASE_URL"] = previous


@pytest.fixture(scope="module")
def migrated(tmp_path_factory):
    path = tmp_path_factory.mktemp("migrated") / "migrated.sqlite3"
    url = f"sqlite+pysqlite:///{path}"
    run_upgrade(url)
    return engine_for(url)


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    path = tmp_path_factory.mktemp("built") / "built.sqlite3"
    engine = engine_for(f"sqlite+pysqlite:///{path}")
    create_all(engine)
    return engine


def table_names(engine) -> set[str]:
    return set(inspect(engine).get_table_names()) - {"alembic_version"}


def column_names(engine, table: str) -> set[str]:
    return {column["name"] for column in inspect(engine).get_columns(table)}


def index_names(engine, table: str) -> set[str]:
    return {index["name"] for index in inspect(engine).get_indexes(table)}


def unique_names(engine, table: str) -> set[str]:
    return {u["name"] for u in inspect(engine).get_unique_constraints(table)}


def test_the_migration_runs_from_nothing(migrated):
    assert "scale_ticket" in table_names(migrated)


def test_every_model_table_exists_in_the_migrations(migrated, built):
    assert table_names(built) == table_names(migrated)


def test_every_table_has_the_same_columns_either_way(migrated, built):
    for table in sorted(table_names(built)):
        assert column_names(migrated, table) == column_names(built, table), table


def test_every_table_has_the_same_indexes_either_way(migrated, built):
    for table in sorted(table_names(built)):
        assert index_names(migrated, table) == index_names(built, table), table


def test_every_table_has_the_same_unique_constraints_either_way(migrated, built):
    for table in sorted(table_names(built)):
        assert unique_names(migrated, table) == unique_names(built, table), table


def test_the_reference_and_ticket_tables_are_there(migrated):
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


def test_the_trading_tables_are_there(migrated):
    assert {
        "purchase_contract",
        "contract_application",
        "settlement",
        "settlement_line",
        "settlement_ticket",
    } <= table_names(migrated)


def test_the_warehouse_tables_are_there(migrated):
    assert {
        "storage_lot",
        "storage_withdrawal",
        "warehouse_receipt",
    } <= table_names(migrated)


def test_the_sale_tables_are_there(migrated):
    assert {"sale_contract", "shipment"} <= table_names(migrated)


def test_the_audit_table_is_there(migrated):
    assert "audit_entry" in table_names(migrated)


def test_the_unique_constraint_on_ticket_number_is_per_location(migrated):
    uniques = inspect(migrated).get_unique_constraints("scale_ticket")
    by_name = {u["name"]: u["column_names"] for u in uniques}
    assert by_name["uq_scale_ticket_location_code"] == ["location_code", "ticket_number"]


def test_the_migration_reverses(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'reversed.sqlite3'}"
    run_upgrade(url)
    run_downgrade(url, "base")
    assert table_names(engine_for(url)) == set()


def test_stepping_back_one_revision_drops_only_that_revisions_tables(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'stepped.sqlite3'}"
    run_upgrade(url)
    run_downgrade(url, "c93a5b81ef40")
    names = table_names(engine_for(url))
    assert "storage_lot" in names
    assert "audit_entry" not in names
    assert "sale_contract" not in names


def test_upgrading_twice_is_a_no_op(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'twice.sqlite3'}"
    run_upgrade(url)
    run_upgrade(url)
    assert "scale_ticket" in table_names(engine_for(url))
