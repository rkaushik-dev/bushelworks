"""Ticket and settlement commands."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

import click

from ..persistence.mappers import settlement_from_row, ticket_from_row
from ..reports.statement import register, render
from ..services.intake import receive
from ..services.settlement_run import settle_producer
from .context import DECIMAL, Context, as_date, handle_errors, pass_context


@click.command("tickets")
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


@click.command("grade")
@click.argument("location")
@click.argument("ticket_number")
@pass_context
@handle_errors
def grade_ticket(ctx: Context, location: str, ticket_number: str) -> None:
    """Regrade a stored ticket against the tables in force on its scale date."""
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


@click.command("void")
@click.argument("location")
@click.argument("ticket_number")
@click.option("--reason", required=True, help="Why the ticket is being voided.")
@pass_context
@handle_errors
def void_ticket(ctx: Context, location: str, ticket_number: str, reason: str) -> None:
    """Void a ticket, keeping its weights on file."""
    with ctx.unit_of_work() as uow:
        row = uow.tickets.void(location, ticket_number, reason)
        uow.commit()
    click.echo(f"{row.ticket_number} voided: {reason}")


@click.command("settle")
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
    settled_on = as_date(on) or date.today()
    with ctx.unit_of_work() as uow:
        statement = settle_producer(
            uow,
            party_code=party,
            location_code=location,
            rules=ctx.rules,
            settled_on=settled_on,
            spot_price=price,
            actor="cli",
        )
        uow.commit()
    if do_print:
        click.echo(render(statement, house_name=ctx.settings.house_name))
    else:
        click.echo(f"{statement.number}  net {statement.net}")


@click.command("register")
@click.argument("on", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.option("--location", default=None)
@pass_context
@handle_errors
def print_register(ctx: Context, on: Any, location: str | None) -> None:
    """Print the settlement register for a day."""
    day = as_date(on)
    with ctx.unit_of_work() as uow:
        statements = [
            settlement_from_row(row) for row in uow.settlements.for_day(day, location)
        ]
    click.echo(register(statements, day.strftime("%d %B %Y")))


@click.command("statement")
@click.argument("number")
@pass_context
@handle_errors
def print_statement(ctx: Context, number: str) -> None:
    """Reprint one statement."""
    with ctx.unit_of_work() as uow:
        statement = uow.settlements.get(number)
    click.echo(render(statement, house_name=ctx.settings.house_name))


@click.command("unpaid")
@pass_context
@handle_errors
def list_unpaid(ctx: Context) -> None:
    """Everything waiting on a cheque, oldest first."""
    with ctx.unit_of_work() as uow:
        rows = [
            (row.number, row.settled_on, row.party_code, row.net)
            for row in uow.settlements.unpaid()
        ]
    for number, settled_on, party, net in rows:
        click.echo(f"{number:<16}{settled_on}  {party:<16}{net:>12}")
    click.echo(f"{len(rows)} unpaid")


COMMANDS = [
    list_tickets,
    grade_ticket,
    void_ticket,
    settle,
    print_register,
    print_statement,
    list_unpaid,
]
