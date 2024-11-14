"""Generated reference material.

The command list and the schedule tables are documented in three places and two
of them go stale. This generates them from the code, so the README and the
office's wall sheet are always what the software actually does.

Run it with ``python -m bushelworks.reference`` and paste the output, or let the
test that compares it with the README fail when somebody adds a command and
forgets.
"""

from __future__ import annotations

from decimal import Decimal

import click

from .cli.main import cli
from .domain import commodity as cm
from .domain.grade import ladder
from .services.houses import discount_book


def command_rows() -> list[tuple[str, str]]:
    """Every CLI command with the first line of its help."""
    rows: list[tuple[str, str]] = []
    for name, command in sorted(cli.commands.items()):
        help_text = (command.help or "").strip().split("\n")[0]
        rows.append((name, help_text))
    return rows


def command_reference() -> str:
    """The command list, as a markdown table."""
    rows = command_rows()
    width = max(len(name) for name, _ in rows)
    lines = [
        f"| {'command'.ljust(width)} | what it does |",
        f"| {'-' * width} | --- |",
    ]
    lines.extend(f"| {name.ljust(width)} | {text} |" for name, text in rows)
    return "\n".join(lines)


def commodity_reference() -> str:
    """The commodity master, as the office pins it to the wall."""
    lines = [
        "| code | commodity | lb/bu | base moisture | priced per |",
        "| --- | --- | --- | --- | --- |",
    ]
    for code in cm.codes():
        one = cm.get(code)
        lines.append(
            f"| {one.code} | {one.name} | {one.standard_test_weight} | "
            f"{one.base_moisture}% | {one.price_unit.value} |"
        )
    return "\n".join(lines)


def grade_reference(commodity_code: str) -> str:
    """One commodity's grade ladder, in the order it is walked."""
    rungs = ladder(commodity_code)
    factors = sorted(
        {factor for rung in rungs for factor in rung.limits}, key=lambda f: f.value
    )
    header = " | ".join(factor.value.replace("_", " ") for factor in factors)
    lines = [
        f"| grade | {header} |",
        "| --- | " + " | ".join("---" for _ in factors) + " |",
    ]
    for rung in rungs:
        cells = [
            str(rung.limits[factor]) if factor in rung.limits else ""
            for factor in factors
        ]
        lines.append(f"| US {rung.grade.value} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def discount_reference(commodity_code: str) -> str:
    """What the house discounts, and where each schedule starts."""
    book = discount_book()
    factors = book.factors(commodity_code)
    if not factors:
        return "no discount schedules filed"
    lines = ["| factor | starts at | rate | per | mode |", "| --- | --- | --- | --- | --- |"]
    for factor in factors:
        schedule = book.for_factor(commodity_code, factor)
        band = schedule.bands[0]
        lines.append(
            f"| {factor.value.replace('_', ' ')} | {band.start} | {band.rate} | "
            f"{band.increment} | {band.mode.value} |"
        )
    return "\n".join(lines)


def worked_example() -> str:
    """The load every test and every explanation uses."""
    from .domain.moisture import ShrinkMethod, ShrinkSchedule

    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=Decimal("1.18"),
    )
    gross = Decimal("78420")
    tare = Decimal("26180")
    net = gross - tare
    bushels = (net / Decimal("56")).quantize(Decimal("0.0001"))
    moisture = Decimal("18.2")
    dry = schedule.dry_bushels(bushels, moisture)
    return "\n".join(
        [
            f"gross          {gross} lb",
            f"tare           {tare} lb",
            f"net            {net} lb",
            f"bushels        {bushels} at 56 lb/bu",
            f"moisture       {moisture}% against a 15.0% base",
            f"shrink         {schedule.shrink_percent(moisture)}%",
            f"dry bushels    {dry}",
        ]
    )


def render_all() -> str:
    """Everything, in the order the README carries it."""
    return "\n\n".join(
        [
            "## Commands",
            command_reference(),
            "## Commodities",
            commodity_reference(),
            "## Corn grade ladder",
            grade_reference("CORN"),
            "## Corn discount schedules",
            discount_reference("CORN"),
            "## A worked load",
            "```\n" + worked_example() + "\n```",
        ]
    )


@click.command()
@click.option(
    "--section",
    type=click.Choice(["all", "commands", "commodities", "grades", "discounts", "load"]),
    default="all",
)
@click.option("--commodity", default="CORN")
def main(section: str, commodity: str) -> None:  # pragma: no cover - a shim
    """Print reference material generated from the code."""
    if section == "commands":
        click.echo(command_reference())
    elif section == "commodities":
        click.echo(commodity_reference())
    elif section == "grades":
        click.echo(grade_reference(commodity))
    elif section == "discounts":
        click.echo(discount_reference(commodity))
    elif section == "load":
        click.echo(worked_example())
    else:
        click.echo(render_all())


if __name__ == "__main__":  # pragma: no cover - module entry point
    main()
