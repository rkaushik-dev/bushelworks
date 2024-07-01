"""Command line entry point.

Every command opens its own unit of work and commits at the end or not at all.
Nothing is interactive: the office runs these out of a batch file at six in the
morning and there is nobody at the keyboard to answer a prompt.

Errors come out as one line on stderr and a non zero exit. A traceback in a
batch log is noise; the message and the code are what the grain accountant needs
to ring somebody about.
"""

from __future__ import annotations

import sys
from collections.abc import Callable
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any

import click

from ..config import load_settings
from ..errors import BushelworksError
from ..persistence.base import create_all, engine_for, session_factory
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
    """What every command needs, built once by the group."""

    def __init__(self, database_url: str | None = None) -> None:
        self.settings = load_settings()
        if database_url:
            from dataclasses import replace

            self.settings = replace(self.settings, database_url=database_url)
        self.engine = engine_for(self.settings.database_url, echo=self.settings.echo_sql)
        self.factory = session_factory(self.engine)
        self.rules = house_rulebook(self.settings)

    def unit_of_work(self) -> UnitOfWork:
        return UnitOfWork(self.factory)


pass_context = click.make_pass_decorator(Context)


def handle_errors(func: Callable[..., Any]) -> Callable[..., Any]:
    """Turn a domain error into a one line message and a non zero exit."""
    import functools

    @functools.wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        try:
            return func(*args, **kwargs)
        except BushelworksError as error:
            click.echo(f"{error.code}: {error.message}", err=True)
            sys.exit(1)

    return wrapper


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.option(
    "--database",
    envvar="BUSHELWORKS_DATABASE_URL",
    default=None,
    help="Override the database url for this run.",
)
@click.version_option(package_name="bushelworks", message="%(version)s")
@click.pass_context
def cli(ctx: click.Context, database: str | None) -> None:
    """Scale house capture and settlement."""
    ctx.obj = Context(database)


@cli.command("init-db")
@pass_context
@handle_errors
def init_db(ctx: Context) -> None:
    """Build the schema directly, for a scratch database."""
    create_all(ctx.engine)
    click.echo(f"schema built at {ctx.settings.database_url}")


@cli.command("tickets")
@click.option("--location", default=None, help="Limit to one elevator.")
@click.option("--party", default=None, help="Limit to one producer.")
@pass_context
@handle_errors
def list_tickets(ctx: Context, location: str | None, party: str | None) -> None:
    """List the tickets waiting to be settled."""
    with ctx.unit_of_work() as uow:
        rows = uow.tickets.awaiting_settlement(location)
        if party:
            rows = [row for row in rows if row.party_code == party]
        for row in rows:
            click.echo(
                f"{row.scale_date}  {row.location_code:<8}{row.ticket_number:<12}"
                f"{row.party_code:<16}{row.commodity_code:<6}{row.net_pounds:>10} lb"
            )
        click.echo(f"{len(rows)} tickets waiting")


@cli.command("grade")
@click.argument("location")
@click.argument("ticket_number")
@pass_context
@handle_errors
def grade_ticket(ctx: Context, location: str, ticket_number: str) -> None:
    """Regrade a stored ticket against the tables in force on its scale date."""
    from ..persistence.mappers import ticket_from_row
    from ..services.intake import receive

    with ctx.unit_of_work() as uow:
        row = uow.tickets.row(location, ticket_number)
        load = receive(ticket_from_row(row), ctx.rules)
    click.echo(f"ticket        {load.ticket_number}")
    click.echo(f"commodity     {load.commodity_code}")
    click.echo(f"gross bushels {load.gross_bushels}")
    click.echo(f"clean bushels {load.clean_bushels}")
    click.echo(f"shrink        {load.shrink_percent}% ({load.shrink_bushels} bu)")
    click.echo(f"dry bushels   {load.dry_bushels}")
    click.echo(f"grade         US {load.grade.grade.value}")
    if load.grade.limiting_factors:
        held = ", ".join(f.value for f in load.grade.limiting_factors)
        click.echo(f"held back by  {held}")
    click.echo(f"discount      {load.discount_rate} per bushel")


@cli.command("settle")
@click.argument("party")
@click.option("--location", required=True, help="Which elevator to settle at.")
@click.option("--price", type=DECIMAL, required=True, help="The spot price per bushel.")
@click.option(
    "--on",
    type=click.DateTime(formats=["%Y-%m-%d"]),
    default=None,
    help="Settlement date, defaulting to today.",
)
@click.option("--print/--no-print", "do_print", default=True, help="Print the statement.")
@pass_context
@handle_errors
def settle(
    ctx: Context,
    party: str,
    location: str,
    price: Decimal,
    on: Any,
    do_print: bool,
) -> None:
    """Settle everything a producer has waiting at one elevator."""
    from ..reports.statement import render
    from ..services.settlement_run import settle_producer

    settled_on = on.date() if on else date.today()
    with ctx.unit_of_work() as uow:
        statement = settle_producer(
            uow,
            party_code=party,
            location_code=location,
            rules=ctx.rules,
            settled_on=settled_on,
            spot_price=price,
        )
        uow.commit()
    if do_print:
        click.echo(render(statement, house_name=ctx.settings.house_name))
    else:
        click.echo(f"{statement.number}  net {statement.net}")


@cli.command("register")
@click.argument("on", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.option("--location", default=None)
@pass_context
@handle_errors
def print_register(ctx: Context, on: Any, location: str | None) -> None:
    """Print the settlement register for a day."""
    from ..persistence.mappers import settlement_from_row
    from ..reports.statement import register

    day = on.date()
    with ctx.unit_of_work() as uow:
        rows = uow.settlements.for_day(day, location)
        statements = [settlement_from_row(row) for row in rows]
    click.echo(register(statements, day.strftime("%d %B %Y")))


@cli.command("statement")
@click.argument("number")
@pass_context
@handle_errors
def print_statement(ctx: Context, number: str) -> None:
    """Reprint one statement."""
    from ..reports.statement import render

    with ctx.unit_of_work() as uow:
        statement = uow.settlements.get(number)
    click.echo(render(statement, house_name=ctx.settings.house_name))


def main() -> None:  # pragma: no cover - console script shim
    cli()
