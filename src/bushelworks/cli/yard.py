"""Storage, position and cheque commands.

The month end run lives here. It is three commands rather than one because the
office wants to look at the accrual before it commits it, and because the cheque
run is a separate decision made on a different day.
"""

from __future__ import annotations

from datetime import date
from typing import Any

import click

from ..reports.position import hedge_sheet, to_csv
from ..reports.position import render as render_board
from ..services.payments import run_cheques
from ..services.positions import build_report
from ..services.warehousing import accrue, on_hand, open_lots
from .context import Context, as_date, handle_errors, pass_context


@click.command("lots")
@click.option("--party", default=None)
@click.option("--commodity", default=None)
@click.option("--location", default=None)
@pass_context
@handle_errors
def list_lots(
    ctx: Context, party: str | None, commodity: str | None, location: str | None
) -> None:
    """Open storage lots."""
    with ctx.unit_of_work() as uow:
        rows = [
            (
                row.lot_id,
                row.party_code,
                row.commodity_code,
                row.stored_on,
                row.open_bushels,
                row.accrued_to_date,
                row.is_deferred_price,
            )
            for row in open_lots(uow, party, commodity, location)
        ]
    for lot_id, party_code, code, stored_on, bushels, accrued, deferred in rows:
        kind = "DP " if deferred else "STO"
        click.echo(
            f"{lot_id:<14}{kind} {party_code:<16}{code:<6}{stored_on}"
            f"{bushels:>14}{accrued:>12}"
        )
    click.echo(f"{len(rows)} open lots")


@click.command("on-hand")
@click.argument("commodity")
@click.option("--location", default=None)
@pass_context
@handle_errors
def show_on_hand(ctx: Context, commodity: str, location: str | None) -> None:
    """Bushels the house is holding for producers."""
    with ctx.unit_of_work() as uow:
        bushels = on_hand(uow, commodity, location)
    click.echo(f"{commodity} {bushels} bu held for producers")


@click.command("accrue")
@click.argument("through", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.option("--location", default=None)
@click.option(
    "--commit/--dry-run",
    "do_commit",
    default=False,
    help="Bill the lots, or only show what would be billed.",
)
@pass_context
@handle_errors
def run_accrual(
    ctx: Context, through: Any, location: str | None, do_commit: bool
) -> None:
    """Charge storage on every open lot up to a date."""
    day = as_date(through)
    with ctx.unit_of_work() as uow:
        run = accrue(uow, ctx.rules, day, location, record=do_commit)
        lines = [
            (line.lot_id, line.party_code, line.open_bushels, line.dollars)
            for line in run.lines
        ]
        total = run.total
        if do_commit:
            uow.commit()
    for lot_id, party, bushels, dollars in lines:
        click.echo(f"{lot_id:<14}{party:<16}{bushels:>14}{dollars:>12}")
    verb = "billed" if do_commit else "would bill"
    click.echo(f"{len(lines)} lots, {verb} {total} through {day}")


@click.command("position")
@click.argument("as_of", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.option("--location", default="*")
@click.option(
    "--format",
    "output",
    type=click.Choice(["board", "hedge", "csv"]),
    default="board",
)
@pass_context
@handle_errors
def show_position(ctx: Context, as_of: Any, location: str, output: str) -> None:
    """The daily position, as a board, a hedge sheet or a spreadsheet."""
    day = as_date(as_of)
    with ctx.unit_of_work() as uow:
        report = build_report(uow, day, location_code=location)
        if output == "board":
            text = render_board(report, house_name=ctx.settings.house_name)
        elif output == "hedge":
            text = hedge_sheet(report)
        else:
            text = to_csv(report)
    click.echo(text)


@click.command("cheques")
@click.option("--first", required=True, help="The first cheque number off the stock.")
@click.option(
    "--on",
    type=click.DateTime(formats=["%Y-%m-%d"]),
    default=None,
    help="Payment date, defaulting to today.",
)
@click.option("--party", "parties", multiple=True, help="Limit to named producers.")
@pass_context
@handle_errors
def cheque_run(ctx: Context, first: str, on: Any, parties: tuple[str, ...]) -> None:
    """Pay everything unpaid, one cheque per producer."""
    paid_on = as_date(on) or date.today()
    with ctx.unit_of_work() as uow:
        run = run_cheques(
            uow,
            paid_on,
            first,
            party_codes=list(parties) or None,
            actor="cli",
        )
        written = [
            (cheque.cheque_number, cheque.party_code, cheque.dollars)
            for cheque in run.cheques
        ]
        held = list(run.held)
        total = run.total
        uow.commit()
    for number, party, dollars in written:
        click.echo(f"{number:<10}{party:<20}{dollars:>12}")
    for party, owed in held:
        click.echo(f"{'held':<10}{party:<20}{owed:>12}  debit balance")
    click.echo(f"{len(written)} cheques, {total}")


COMMANDS = [list_lots, show_on_hand, run_accrual, show_position, cheque_run]
