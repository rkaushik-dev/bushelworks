"""Alembic environment.

Two things here are not the generated default and both matter.

The URL comes from the application settings rather than from ``alembic.ini``, so
there is exactly one place a database is named and no chance of migrating the
wrong one because somebody edited the ini and forgot the environment.

``render_as_batch`` is on. SQLite cannot ALTER a column, and the office box runs
SQLite while the second site runs Postgres. Batch mode rebuilds the table on
SQLite and is a no-op on Postgres, so one migration file serves both.
"""

from __future__ import annotations

from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from bushelworks.config import load_settings
from bushelworks.persistence.base import metadata
from bushelworks.persistence import models  # registers the tables on the metadata

config = context.config

if config.config_file_name is not None:
    # Not the generated default. ``fileConfig`` disables every logger it does
    # not know about, and it does not know about ``bushelworks``: running a
    # migration in process turned the application's logging off for the rest of
    # the run, which took the settlement events with it.
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = metadata


def database_url() -> str:
    return load_settings().database_url


def run_migrations_offline() -> None:
    context.configure(
        url=database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    section = config.get_section(config.config_ini_section, {})
    section["sqlalchemy.url"] = database_url()
    connectable = engine_from_config(
        section,
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
