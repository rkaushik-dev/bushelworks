"""Command line entry point.

The group and nothing else. Commands live in :mod:`bushelworks.cli.grain` and
:mod:`bushelworks.cli.yard`, which is where they went when this file reached
three hundred lines and adding the storage run would have made it four.

Every command opens its own unit of work and commits at the end or not at all.
Nothing is interactive: the office runs these out of a batch file at six in the
morning and there is nobody at the keyboard to answer a prompt.
"""

from __future__ import annotations

import click

from ..persistence.base import create_all
from . import grain, yard
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


for command in (*grain.COMMANDS, *yard.COMMANDS):
    cli.add_command(command)


def main() -> None:  # pragma: no cover - console script shim
    cli()
