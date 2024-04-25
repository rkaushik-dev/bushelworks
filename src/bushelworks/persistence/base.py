"""Engine, session and the declarative base.

Two things here are worth more than they look.

The naming convention means every index and constraint has a predictable name,
which is what lets a migration drop one by name on Postgres and on SQLite alike.
Without it SQLAlchemy invents names and Alembic autogenerate produces migrations
that cannot be reversed.

The ``Numeric`` defaults are set once and used everywhere. Money is stored as
``NUMERIC(14, 2)`` and bushels as ``NUMERIC(14, 4)`` because the settlement path
is exact decimal all the way through, and a column that quietly rounds is a bug
that only shows up on the biggest loads.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any, Iterator

from sqlalchemy import Numeric, create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.pool import StaticPool
from sqlalchemy.schema import MetaData

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

metadata = MetaData(naming_convention=NAMING_CONVENTION)

# Money, prices, bushels and weights. Anything that reaches a statement.
MONEY = Numeric(14, 2)
PRICE = Numeric(12, 4)
BUSHELS = Numeric(14, 4)
POUNDS = Numeric(12, 0)
PERCENT = Numeric(7, 4)


class Base(DeclarativeBase):
    """Declarative base carrying the shared metadata."""

    metadata = metadata

    def __repr__(self) -> str:
        columns = ", ".join(
            f"{c.name}={getattr(self, c.name)!r}"
            for c in self.__table__.primary_key.columns
        )
        return f"<{type(self).__name__} {columns}>"


def engine_for(url: str, echo: bool = False, **kwargs: Any) -> Engine:
    """Build an engine with the settings this application needs.

    In memory SQLite gets a static pool so every session in a test sees the same
    database. Without it each connection opens its own empty one and the first
    query after a commit finds nothing, which reads like a transaction bug and
    is not.
    """
    if url.startswith("sqlite") and ":memory:" in url:
        kwargs.setdefault("poolclass", StaticPool)
        kwargs.setdefault("connect_args", {"check_same_thread": False})
    engine = create_engine(url, echo=echo, future=True, **kwargs)
    if engine.dialect.name == "sqlite":
        _enable_sqlite_foreign_keys(engine)
    return engine


def _enable_sqlite_foreign_keys(engine: Engine) -> None:
    """SQLite ignores foreign keys unless told not to, once per connection."""

    @event.listens_for(engine, "connect")
    def _set_pragma(dbapi_connection, _record):  # type: ignore[no-untyped-def]
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()


def session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(
        bind=engine,
        expire_on_commit=False,
        autoflush=False,
        future=True,
    )


def create_all(engine: Engine) -> None:
    """Build the schema directly. Used by tests; production uses migrations."""
    from . import models  # noqa: F401  (import for the side effect of registering)

    metadata.create_all(engine)


def drop_all(engine: Engine) -> None:
    metadata.drop_all(engine)


def sessions(factory: sessionmaker[Session]) -> Iterator[Session]:
    """A generator dependency for the API layer."""
    session = factory()
    try:
        yield session
    finally:
        session.close()


def as_decimal(value: Any) -> Decimal:
    """Coerce whatever the driver handed back into a Decimal.

    SQLite has no numeric type and returns floats for ``NUMERIC`` columns unless
    every read goes through here. Postgres returns Decimal already, so this is a
    no-op there and load bearing everywhere else.
    """
    if isinstance(value, Decimal):
        return value
    if value is None:
        return Decimal("0")
    return Decimal(str(value))
