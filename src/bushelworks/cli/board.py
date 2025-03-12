"""Board and desk commands.

The office runs these once a year and the manager runs the desk ones every
morning, so they have opposite shapes. The patronage commands take a crop year
and write a file; the desk commands take a handful of numbers off the screen and
print an answer.

The membership roll comes in as a file of codes, one per line, because it is
kept in the accounting package and exported from there. Typing four hundred
codes onto a command line is not a workflow anybody has.
"""

from __future__ import annotations

from decimal import Decimal
from pathlib import Path

import click

from ..reports.patronage import render, to_csv
from ..services.desk import basis, carry
from ..services.patronage import allocate, build_crop_year, top_producers
from .context import DECIMAL, Context, handle_errors, pass_context


def _roll(path: Path | None) -> list[str] | None:
    """Read a membership roll, or None for a house that is not a co-op.

    Blank lines and anything after a hash are dropped, because the export from
    the accounting package has a header comment on it and always will.
    """
    if path is None:
        return None
    codes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        code = line.split("#", 1)[0].strip()
        if code:
            codes.append(code)
    return codes


@click.command("patronage")
@click.argument("year", type=int)
@click.option("--location", default=None, help="Limit to one elevator.")
@click.option(
    "--members",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="A file of member codes, one per line.",
)
@click.option(
    "--order",
    type=click.Choice(["bushels", "margin"]),
    default="bushels",
    help="Rank on bushels delivered or on margin kept.",
)
@click.option("--limit", type=int, default=25, help="How many producers to name.")
@pass_context
@handle_errors
def print_patronage(
    ctx: Context,
    year: int,
    location: str | None,
    members: Path | None,
    order: str,
    limit: int,
) -> None:
    """Print the patronage table for a crop year."""
    with ctx.unit_of_work() as uow:
        built = build_crop_year(uow, year, location, _roll(members))
        page = render(built, order=order, limit=limit)
    click.echo(page)


@click.command("patronage-export")
@click.argument("year", type=int)
@click.option("--location", default=None, help="Limit to one elevator.")
@click.option(
    "--members",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="A file of member codes, one per line.",
)
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Write here instead of to standard output.",
)
@pass_context
@handle_errors
def export_patronage(
    ctx: Context,
    year: int,
    location: str | None,
    members: Path | None,
    out: Path | None,
) -> None:
    """Write the patronage table as CSV for the accountant."""
    with ctx.unit_of_work() as uow:
        text = to_csv(build_crop_year(uow, year, location, _roll(members)))
    if out is None:
        click.echo(text, nl=False)
        return
    out.write_text(text, encoding="utf-8", newline="")
    click.echo(f"{len(text.splitlines()) - 1} producers written to {out}")


@click.command("allocate")
@click.argument("year", type=int)
@click.option("--pool", type=DECIMAL, required=True, help="Dollars to split.")
@click.option("--location", default=None, help="Limit to one elevator.")
@click.option(
    "--members",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="A file of member codes, one per line.",
)
@click.option(
    "--minimum",
    type=DECIMAL,
    default=Decimal("5.00"),
    help="Shares under this stay in the pool.",
)
@pass_context
@handle_errors
def allocate_pool(
    ctx: Context,
    year: int,
    pool: Decimal,
    location: str | None,
    members: Path | None,
    minimum: Decimal,
) -> None:
    """Work out what each member would get out of a patronage pool."""
    with ctx.unit_of_work() as uow:
        built = build_crop_year(uow, year, location, _roll(members))
        split = allocate(built, pool, minimum)
    paid = sum(split.values(), Decimal("0"))
    for code in sorted(split, key=lambda item: (-split[item], item)):
        click.echo(f"{code:<24}{split[code]:>14}")
    click.echo(f"{'ALLOCATED':<24}{paid:>14}")
    click.echo(f"{'UNALLOCATED':<24}{pool - paid:>14}")


@click.command("top")
@click.argument("year", type=int)
@click.option("--count", type=int, default=10, help="How many to name.")
@click.option("--location", default=None, help="Limit to one elevator.")
@pass_context
@handle_errors
def print_top(ctx: Context, year: int, count: int, location: str | None) -> None:
    """Name the biggest producers of a crop year, biggest first."""
    with ctx.unit_of_work() as uow:
        built = build_crop_year(uow, year, location)
        codes = top_producers(built, count)
        shares = {code: built.share_of_bushels(code) for code in codes}
    for rank, code in enumerate(codes, start=1):
        click.echo(f"{rank:>3}  {code:<24}{shares[code]:>10}%")
    if codes:
        held = sum(shares.values(), Decimal("0"))
        click.echo(f"     these {len(codes)} hold {held}% of the bushels")


@click.command("basis")
@click.argument("cash_bid", type=DECIMAL)
@click.argument("futures", type=DECIMAL)
@handle_errors
def print_basis(cash_bid: Decimal, futures: Decimal) -> None:
    """Cash less futures, for the board out front."""
    click.echo(str(basis(cash_bid, futures)))


@click.command("carry")
@click.argument("near", type=DECIMAL)
@click.argument("deferred", type=DECIMAL)
@click.option("--months", type=int, required=True, help="Months between the two.")
@click.option(
    "--storage",
    type=DECIMAL,
    required=True,
    help="What the house charges itself per bushel per month.",
)
@handle_errors
def print_carry(
    near: Decimal, deferred: Decimal, months: int, storage: Decimal
) -> None:
    """Say whether the spread pays to hold the grain rather than sell it."""
    per_bushel = carry(near, deferred, months, storage)
    click.echo(f"spread    {deferred - near}")
    click.echo(f"carry     {per_bushel} per bushel")
    click.echo("store" if per_bushel > 0 else "sell")


COMMANDS = [
    print_patronage,
    export_patronage,
    allocate_pool,
    print_top,
    print_basis,
    print_carry,
]
