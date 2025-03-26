"""Export commands.

The auditor asks for three files every February and the lender asks for two of
them every quarter. Producing those by hand out of the reporting screen took an
afternoon and produced a slightly different shape each time, which is the sort
of thing that turns a routine audit into a long one.

Every command here writes the file and then reads it straight back before it
says it is done. A CSV with an embedded newline in a remark column reads back
with the wrong number of columns, and the cheap place to find that out is here
rather than six weeks later on somebody else's desk.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import click

from ..persistence.mappers import settlement_from_row
from ..reports.exports import (
    LINE_COLUMNS,
    SETTLEMENT_COLUMNS,
    TICKET_COLUMNS,
    check_round_trip,
    lines_to_csv,
    settlements_to_csv,
    tickets_to_csv,
)
from .context import Context, as_date, handle_errors, pass_context

DAY = click.DateTime(formats=["%Y-%m-%d"])


def _write(text: str, columns: tuple[str, ...], out: Path | None, what: str) -> None:
    """Write it, read it back, then say what happened.

    The read back is not belt and braces. It has caught a remark with a newline
    in it twice, both times on a ticket somebody had pasted a note into.
    """
    rows = check_round_trip(text, columns)
    if out is None:
        click.echo(text, nl=False)
        return
    out.write_text(text, encoding="utf-8", newline="")
    click.echo(f"{rows} {what} written to {out}")


OUT = click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Write here instead of to standard output.",
)


@click.command("export-tickets")
@click.option("--from", "opens", type=DAY, default=None, help="First scale date.")
@click.option("--to", "closes", type=DAY, default=None, help="Last scale date.")
@click.option("--location", default=None, help="Limit to one elevator.")
@click.option("--party", default=None, help="Limit to one producer.")
@click.option(
    "--include-voided/--no-voided",
    default=False,
    help="Voided tickets are left out unless the auditor asks for them.",
)
@OUT
@pass_context
@handle_errors
def export_tickets(
    ctx: Context,
    opens: object,
    closes: object,
    location: str | None,
    party: str | None,
    include_voided: bool,
    out: Path | None,
) -> None:
    """Write scale tickets as CSV."""
    first = as_date(opens)
    last = as_date(closes)
    with ctx.unit_of_work() as uow:
        rows = _tickets(uow, first, last, location, party, include_voided)
        text = tickets_to_csv(rows)
    _write(text, TICKET_COLUMNS, out, "tickets")


def _tickets(uow, first, last, location, party, include_voided):
    from sqlalchemy import select

    from ..persistence.models import ScaleTicketRow

    query = select(ScaleTicketRow)
    if first is not None:
        query = query.where(ScaleTicketRow.scale_date >= first)
    if last is not None:
        query = query.where(ScaleTicketRow.scale_date <= last)
    if location:
        query = query.where(ScaleTicketRow.location_code == location)
    if party:
        query = query.where(ScaleTicketRow.party_code == party)
    if not include_voided:
        query = query.where(ScaleTicketRow.voided.is_(False))
    return list(
        uow.session.scalars(
            query.order_by(ScaleTicketRow.scale_date, ScaleTicketRow.ticket_number)
        )
    )


@click.command("export-settlements")
@click.option("--from", "opens", type=DAY, required=True, help="First settled date.")
@click.option("--to", "closes", type=DAY, required=True, help="Last settled date.")
@click.option("--location", default=None, help="Limit to one elevator.")
@OUT
@pass_context
@handle_errors
def export_settlements(
    ctx: Context,
    opens: object,
    closes: object,
    location: str | None,
    out: Path | None,
) -> None:
    """Write settlement headers as CSV, one row each."""
    with ctx.unit_of_work() as uow:
        statements = _settlements(uow, as_date(opens), as_date(closes), location)
        text = settlements_to_csv(statements)
    _write(text, SETTLEMENT_COLUMNS, out, "settlements")


@click.command("export-lines")
@click.option("--from", "opens", type=DAY, required=True, help="First settled date.")
@click.option("--to", "closes", type=DAY, required=True, help="Last settled date.")
@click.option("--location", default=None, help="Limit to one elevator.")
@OUT
@pass_context
@handle_errors
def export_lines(
    ctx: Context,
    opens: object,
    closes: object,
    location: str | None,
    out: Path | None,
) -> None:
    """Write every printed settlement line as CSV.

    This is the one the auditor actually wants. The headers say what was paid;
    the lines say what it was made of, and a discount nobody can explain shows
    up here and nowhere else.
    """
    with ctx.unit_of_work() as uow:
        statements = _settlements(uow, as_date(opens), as_date(closes), location)
        text = lines_to_csv(statements)
    _write(text, LINE_COLUMNS, out, "lines")


def _settlements(uow, opens: date, closes: date, location: str | None):
    from sqlalchemy import select

    from ..persistence.models import SettlementRow

    query = select(SettlementRow).where(
        SettlementRow.settled_on >= opens,
        SettlementRow.settled_on <= closes,
        SettlementRow.voided.is_(False),
    )
    if location:
        query = query.where(SettlementRow.location_code == location)
    rows = uow.session.scalars(query.order_by(SettlementRow.settled_on, SettlementRow.number))
    return [settlement_from_row(row) for row in rows]


COMMANDS = [
    export_tickets,
    export_settlements,
    export_lines,
]
