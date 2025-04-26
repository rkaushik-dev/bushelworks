"""What changed, release by release, as data rather than prose.

A changelog in a text file is a changelog that goes stale, because nothing reads
it. This one is read: the version is checked against the module, the entries are
checked for order, and the CLI prints it. A release that is not in here is a
release the tests refuse.

The audience is the house, not the internet. Entries say what the office will
notice, and the two that matter most are the ones marked ``breaking``, because
those are the ones somebody has to be telephoned about before the box is
upgraded on a Sunday night.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from . import __version__


@dataclass(frozen=True, slots=True)
class Release:
    """One version, and what the house will notice about it."""

    version: str
    released_on: date
    summary: str
    changes: tuple[str, ...] = ()
    breaking: tuple[str, ...] = ()
    migrations: tuple[str, ...] = ()

    @property
    def numbers(self) -> tuple[int, int, int]:
        major, minor, patch = self.version.split(".")
        return (int(major), int(minor), int(patch))

    @property
    def is_breaking(self) -> bool:
        return bool(self.breaking)

    @property
    def needs_a_migration(self) -> bool:
        return bool(self.migrations)


HISTORY: tuple[Release, ...] = (
    Release(
        version="0.1.0",
        released_on=date(2024, 6, 24),
        summary="Weights, grade factors and the shrink table.",
        changes=(
            "test weight, moisture and damage read off a probe sample",
            "shrink and dockage worked out from the schedule in force on the day",
            "the reference and ticket tables",
        ),
        migrations=("8c1b4f0a2d31",),
    ),
    Release(
        version="0.2.0",
        released_on=date(2024, 8, 2),
        summary="Contracts, settlement and the printed statement.",
        changes=(
            "forward, basis and hedged to arrive contracts",
            "settlement lines in a fixed order with a balance check",
            "the eighty column statement the dot matrix prints",
        ),
        migrations=("b47d9e15c082",),
    ),
    Release(
        version="0.3.0",
        released_on=date(2024, 9, 30),
        summary="Storage, warehouse receipts and the storage accrual.",
        changes=(
            "open lots, withdrawals and negotiable receipts",
            "storage charged on the bushels open during the billing period",
            "the daily position, and the sign of deferred price bushels",
        ),
        migrations=("c93a5b81ef40", "d15e0a73b6c9"),
    ),
    Release(
        version="0.4.0",
        released_on=date(2024, 11, 29),
        summary="Sales, shipments and the bin yard.",
        changes=(
            "sale contracts, loadings and rejected cars",
            "bins with a running blend and an aeration log",
            "the HTTP interface, with a key per role",
        ),
        migrations=("e2b7c40d915a", "f61c8ad3e042"),
    ),
    Release(
        version="0.5.0",
        released_on=date(2025, 2, 14),
        summary="The office tools: audit trail, day end and the exports.",
        changes=(
            "an audit entry for every document that moves",
            "the day end close and its sheet",
            "cheque runs, ACH files and the year end summary",
        ),
    ),
    Release(
        version="0.6.0",
        released_on=date(2025, 3, 21),
        summary="The complaint register and the hedge desk.",
        changes=(
            "complaints, worked and closed through the domain rules",
            "the morning hedge sheet, position against the broker statement",
            "the patronage table and the board reports",
        ),
        migrations=("a904f27c1db5",),
    ),
    Release(
        version="0.7.0",
        released_on=date(2025, 4, 28),
        summary="The migration off the old system, and the exports it needs.",
        changes=(
            "the old fixed width ticket file reads and loads, dry run by default",
            "CSV exports for the auditor, read back before they are called done",
            "reporting indexes, and one group by instead of a query per contract",
        ),
        breaking=(
            "settlement line codes now print eleven characters, not twelve, so a"
            " script that slices the statement by column has to move",
        ),
        migrations=("b73d1e58cf20",),
    ),
    Release(
        version="0.8.0",
        released_on=date(2025, 5, 26),
        summary="The general ledger, and the periods that decide what can post.",
        changes=(
            "a chart of accounts, where an account with sub accounts is a"
            " heading and will not take a posting itself",
            "an append only journal, corrected by posting a reverse rather than"
            " by editing anything",
            "accounting periods, so a ticket keyed after the return went in"
            " lands in the month that is still open",
        ),
        migrations=("c5f0a92b7e14",),
    ),
)


def current() -> Release:
    """The release this build is."""
    for entry in HISTORY:
        if entry.version == __version__:
            return entry
    raise ValueError(f"{__version__} is not in the release history")


def latest() -> Release:
    return HISTORY[-1]


def since(version: str) -> tuple[Release, ...]:
    """Everything released after a version.

    What the house reads before an upgrade. A box three releases behind gets
    three entries and one of them is marked breaking.
    """
    numbers = tuple(int(part) for part in version.split("."))
    return tuple(entry for entry in HISTORY if entry.numbers > numbers)


def breaking_since(version: str) -> tuple[str, ...]:
    """Only the lines somebody has to be telephoned about."""
    lines: list[str] = []
    for entry in since(version):
        lines.extend(f"{entry.version}: {note}" for note in entry.breaking)
    return tuple(lines)


def migrations_since(version: str) -> tuple[str, ...]:
    """Alembic revisions an upgrade from a version will run."""
    revisions: list[str] = []
    for entry in since(version):
        revisions.extend(entry.migrations)
    return tuple(revisions)


def render(entry: Release) -> str:
    """One release as a plain block, for the terminal."""
    lines = [f"{entry.version}  {entry.released_on}", f"  {entry.summary}"]
    for change in entry.changes:
        lines.append(f"  - {change}")
    for note in entry.breaking:
        lines.append(f"  ! {note}")
    if entry.migrations:
        lines.append(f"  migrations: {', '.join(entry.migrations)}")
    return "\n".join(lines)


def render_history(entries: tuple[Release, ...] | None = None) -> str:
    """The whole history, newest first, which is how it gets read."""
    chosen = HISTORY if entries is None else entries
    return "\n\n".join(render(entry) for entry in reversed(chosen))
