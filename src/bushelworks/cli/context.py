"""What every command needs, built once by the group.

Split out of ``main`` when the commands went into their own modules. Each
command module imports this rather than the group, so nothing has to import
``main`` and there is no cycle to work around.
"""

from __future__ import annotations

import functools
import sys
from collections.abc import Callable
from decimal import Decimal, InvalidOperation
from typing import Any

import click

from ..config import load_settings
from ..domain.rulebook import RuleBook
from ..errors import BushelworksError
from ..logging import configure
from ..persistence.base import engine_for, session_factory
from ..persistence.unitofwork import UnitOfWork
from ..services.houses import house_rulebook


class DecimalParam(click.ParamType):
    """A money or quantity argument, parsed exactly.

    ``click.FLOAT`` would take these and would quietly turn 4.35 into something
    that is not 4.35, which then multiplies by nine hundred bushels.
    """

    name = "decimal"

    def convert(self, value: Any, param: Any, ctx: Any) -> Decimal:
        if isinstance(value, Decimal):
            return value
        try:
            return Decimal(str(value))
        except InvalidOperation:
            self.fail(f"{value!r} is not a number", param, ctx)


DECIMAL = DecimalParam()


class Context:
    """Settings, a session factory and the house rule book."""

    def __init__(self, database_url: str | None = None) -> None:
        self.settings = load_settings()
        if database_url:
            from dataclasses import replace

            self.settings = replace(self.settings, database_url=database_url)
        configure(self.settings.log_level)
        self.engine = engine_for(self.settings.database_url, echo=self.settings.echo_sql)
        self.factory = session_factory(self.engine)
        self.rules: RuleBook = house_rulebook(self.settings)

    def unit_of_work(self) -> UnitOfWork:
        return UnitOfWork(self.factory)


pass_context = click.make_pass_decorator(Context)


def handle_errors(func: Callable[..., Any]) -> Callable[..., Any]:
    """Turn a domain error into a one line message and a non zero exit.

    A traceback in a batch log is noise. The code and the sentence are what the
    grain accountant reads out over the telephone.
    """

    @functools.wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        try:
            return func(*args, **kwargs)
        except BushelworksError as error:
            click.echo(f"{error.code}: {error.message}", err=True)
            sys.exit(1)

    return wrapper


def as_date(value: Any):
    """click.DateTime hands back a datetime. The domain wants a date."""
    return None if value is None else value.date()
