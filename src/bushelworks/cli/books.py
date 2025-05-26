"""Ledger commands.

The month end ones are the point. ``review`` says whether a month would close
and ``close`` closes it, and they are separate commands rather than one with a
``--dry-run`` because the office runs the first one on the third of the month
and the second one a week later, and a flag that changes whether a command
writes is a flag somebody eventually forgets.

``file-year`` is run once each December. It is not part of ``init-db`` because a
house being set up mid year wants the current year filed, not 2000.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import click

from ..domain.ledger import ChartOfAccounts, TrialBalance, trial_balance
from ..persistence.unitofwork import UnitOfWork
from ..reports.ledger import balance_sheet, income_statement, to_csv, trial_balance_page
from ..services.closing import close_year, review_year
from ..services.closing import render as render_year
from ..services.monthend import (
    close_period,
    earliest_open,
    file_year,
    reopen_period,
    review,
)
from .context import Context, handle_errors, pass_context


def _balance(
    uow: UnitOfWork, through: date, since: date | None = None
) -> tuple[ChartOfAccounts, TrialBalance]:
    chart = uow.ledger.chart()
    entries = uow.ledger.entries_between(since or date.min, through)
    return chart, trial_balance(chart, entries, through=through, since=since)


@click.command("chart")
@click.option("--inactive/--active-only", default=False, help="Include closed accounts.")
@pass_context
@handle_errors
def print_chart(ctx: Context, inactive: bool) -> None:
    """The filed chart of accounts."""
    with ctx.unit_of_work() as uow:
        chart = uow.ledger.chart(include_inactive=inactive)
        for account in chart:
            depth = len(chart.lineage(account.code)) - 1
            mark = "  " if chart.is_heading(account.code) else ". "
            click.echo(f"{account.code:<8}{'  ' * depth}{mark}{account.name}")


@click.command("trial-balance")
@click.argument("through", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.option(
    "--since",
    type=click.DateTime(formats=["%Y-%m-%d"]),
    default=None,
    help="Make it a period rather than a running total.",
)
@click.option("--empty/--used-only", default=False, help="Show untouched accounts.")
@click.option(
    "--csv",
    "csv_path",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Write it as a file for the accounting package instead.",
)
@pass_context
@handle_errors
def print_trial_balance(
    ctx: Context,
    through,
    since,
    empty: bool,
    csv_path: Path | None,
) -> None:
    """The books as at a day."""
    with ctx.unit_of_work() as uow:
        _, balance = _balance(
            uow, through.date(), since.date() if since else None
        )
        if csv_path is not None:
            csv_path.write_text(to_csv(balance, include_empty=empty), encoding="utf-8")
            written = csv_path.read_text(encoding="utf-8")
            click.echo(f"{len(written.splitlines()) - 1} accounts to {csv_path}")
            return
        click.echo(trial_balance_page(balance, include_empty=empty))


@click.command("income")
@click.argument("opens", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.argument("closes", type=click.DateTime(formats=["%Y-%m-%d"]))
@pass_context
@handle_errors
def print_income(ctx: Context, opens, closes) -> None:
    """What the house earned between two days."""
    with ctx.unit_of_work() as uow:
        chart, balance = _balance(uow, closes.date(), opens.date())
        click.echo(income_statement(chart, balance, opens.date()))


@click.command("balance-sheet")
@click.argument("through", type=click.DateTime(formats=["%Y-%m-%d"]))
@pass_context
@handle_errors
def print_balance_sheet(ctx: Context, through) -> None:
    """What the house owns and owes as at a day."""
    with ctx.unit_of_work() as uow:
        chart, balance = _balance(uow, through.date())
        click.echo(balance_sheet(chart, balance))


@click.command("journal")
@click.argument("opens", type=click.DateTime(formats=["%Y-%m-%d"]))
@click.argument("closes", type=click.DateTime(formats=["%Y-%m-%d"]))
@pass_context
@handle_errors
def print_journal(ctx: Context, opens, closes) -> None:
    """Every entry posted between two days."""
    with ctx.unit_of_work() as uow:
        for entry in uow.ledger.entries_between(opens.date(), closes.date()):
            click.echo(f"{entry.number}  {entry.posted_on}  {entry.memo}")
            for leg in entry.legs:
                side = "dr" if leg.side.value == "debit" else "  cr"
                click.echo(f"    {leg.account_code:<8}{side} {leg.dollars:>14}")


@click.command("file-year")
@click.argument("year", type=int)
@pass_context
@handle_errors
def file_accounting_year(ctx: Context, year: int) -> None:
    """Put twelve open months on file."""
    with ctx.unit_of_work() as uow:
        rows = file_year(uow, year)
        count = len(rows)
        uow.commit()
    click.echo(f"{count} months filed for {year}")


@click.command("review")
@click.argument("period")
@pass_context
@handle_errors
def review_month(ctx: Context, period: str) -> None:
    """Say whether a month would close, and write nothing."""
    with ctx.unit_of_work() as uow:
        found = review(uow, period)
        click.echo(f"{found.code}  {found.starts_on} to {found.ends_on}")
        click.echo(f"  settlements {found.settlements}, entries {found.entries}")
        click.echo(f"  revenue {found.revenue}, expense {found.expense}")
        click.echo(f"  margin {found.margin}")
        if found.is_clean:
            click.echo("  would close clean")
            return
        click.echo(f"  {len(found.outstanding)} outstanding:")
        for item in found.outstanding:
            click.echo(f"    {item}")


@click.command("close-month")
@click.argument("period")
@click.option("--by", "closed_by", required=True, help="Who is closing it.")
@click.option(
    "--on",
    type=click.DateTime(formats=["%Y-%m-%d"]),
    default=None,
    help="The day it was closed, if not today.",
)
@click.option(
    "--force/--no-force",
    default=False,
    help="Close over what is outstanding, and record who did.",
)
@pass_context
@handle_errors
def close_month(ctx: Context, period: str, closed_by: str, on, force: bool) -> None:
    """Close a month."""
    with ctx.unit_of_work() as uow:
        found = close_period(
            uow, period, closed_by, on=on.date() if on else None, force=force
        )
        clean = found.is_clean
        outstanding = [str(item) for item in found.outstanding]
        uow.commit()
    if clean:
        click.echo(f"{period} closed")
        return
    click.echo(f"{period} closed over {len(outstanding)} outstanding items:")
    for item in outstanding:
        click.echo(f"  {item}")


@click.command("reopen-month")
@click.argument("period")
@click.option("--reason", required=True, help="Why, for the log.")
@pass_context
@handle_errors
def reopen_month(ctx: Context, period: str, reason: str) -> None:
    """Open a closed month again. The return will have to be amended."""
    with ctx.unit_of_work() as uow:
        reopen_period(uow, period, reason)
        uow.commit()
    click.echo(f"{period} reopened")


@click.command("open-month")
@pass_context
@handle_errors
def print_open_month(ctx: Context) -> None:
    """Which month a ticket keyed late belongs in."""
    with ctx.unit_of_work() as uow:
        row = earliest_open(uow)
        if row is None:
            click.echo("no months on file")
            return
        click.echo(f"{row.code}  {row.starts_on} to {row.ends_on}")


@click.command("year-end")
@click.argument("year", type=int)
@pass_context
@handle_errors
def review_the_year(ctx: Context, year: int) -> None:
    """Say what closing a year would post, and write nothing."""
    with ctx.unit_of_work() as uow:
        click.echo(render_year(review_year(uow, year)))


@click.command("close-year")
@click.argument("year", type=int)
@click.option("--by", "closed_by", required=True, help="Who is closing it.")
@click.option(
    "--retained",
    default=None,
    help="Close into this account instead of retained earnings.",
)
@click.option(
    "--force/--no-force",
    default=False,
    help="Close over what is outstanding, and record who did.",
)
@pass_context
@handle_errors
def close_the_year(
    ctx: Context, year: int, closed_by: str, retained: str | None, force: bool
) -> None:
    """Empty the revenue and expense accounts into equity."""
    with ctx.unit_of_work() as uow:
        if retained is None:
            found = close_year(uow, year, closed_by, force=force)
        else:
            found = close_year(uow, year, closed_by, retained=retained, force=force)
        page = render_year(found)
        uow.commit()
    click.echo(page)


COMMANDS = [
    print_chart,
    print_trial_balance,
    print_income,
    print_balance_sheet,
    print_journal,
    file_accounting_year,
    review_month,
    close_month,
    reopen_month,
    print_open_month,
    review_the_year,
    close_the_year,
]
