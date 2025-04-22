"""Command line entry point.

The group and nothing else. Commands live in :mod:`bushelworks.cli.grain`,
:mod:`bushelworks.cli.yard` and :mod:`bushelworks.cli.board`, which is where
they went when this file reached three hundred lines and adding the storage
run would have made it four.

Every command opens its own unit of work and commits at the end or not at all.
Nothing is interactive: the office runs these out of a batch file at six in the
morning and there is nobody at the keyboard to answer a prompt.
"""

from __future__ import annotations

import click

from ..persistence.base import create_all
from . import board, export, grain, yard
from .context import Context, handle_errors, pass_context


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


@cli.command("changes")
@click.option(
    "--since",
    "from_version",
    default=None,
    help="Only what was released after this version.",
)
@click.option(
    "--breaking/--all",
    "breaking_only",
    default=False,
    help="Only the lines somebody has to be telephoned about.",
)
def show_changes(from_version: str | None, breaking_only: bool) -> None:
    """What changed, so the house can read it before it upgrades."""
    from ..release import HISTORY, breaking_since, migrations_since, render_history, since

    entries = HISTORY if from_version is None else since(from_version)
    if breaking_only:
        lines = breaking_since(from_version or "0.0.0")
        for line in lines:
            click.echo(line)
        if not lines:
            click.echo("nothing breaking")
        return
    if not entries:
        click.echo("nothing since then")
        return
    click.echo(render_history(entries))
    if from_version is not None:
        revisions = migrations_since(from_version)
        if revisions:
            click.echo("")
            click.echo(f"this upgrade runs {len(revisions)} migrations")


@cli.command("seed")
@click.option(
    "--force/--no-force",
    default=False,
    help="Seed even if there is already a producer file here.",
)
@pass_context
@handle_errors
def seed_house(ctx: Context, force: bool) -> None:
    """Fill an empty database with a week of a working elevator."""
    from ..services.seeding import describe, seed

    with ctx.unit_of_work() as uow:
        result = seed(uow, ctx.rules, force=force)
        summary = describe(result)
        uow.commit()
    click.echo(summary)


for command in (
    *grain.COMMANDS,
    *yard.COMMANDS,
    *board.COMMANDS,
    *export.COMMANDS,
):
    cli.add_command(command)


def main() -> None:  # pragma: no cover - console script shim
    cli()
