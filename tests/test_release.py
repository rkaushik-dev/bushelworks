"""The release history, kept honest by the tests that read it."""

import re
from pathlib import Path

import pytest
from click.testing import CliRunner

import bushelworks
from bushelworks.cli.main import cli
from bushelworks.release import (
    HISTORY,
    breaking_since,
    current,
    latest,
    migrations_since,
    render,
    render_history,
    since,
)

MIGRATIONS = Path(bushelworks.__file__).resolve().parents[2] / "migrations" / "versions"


@pytest.fixture
def runner():
    return CliRunner()


def test_this_build_is_in_the_history():
    assert current().version == bushelworks.__version__


def test_this_build_is_the_newest_entry():
    """A release added below the current version means somebody forgot to bump.

    It is a cheap check and it has already caught the reverse: a version bump
    with no entry, which reads as a release nobody can describe.
    """
    assert latest().version == bushelworks.__version__


def test_the_versions_go_up():
    numbers = [entry.numbers for entry in HISTORY]
    assert numbers == sorted(numbers)


def test_no_version_appears_twice():
    versions = [entry.version for entry in HISTORY]
    assert len(versions) == len(set(versions))


def test_the_dates_go_up():
    days = [entry.released_on for entry in HISTORY]
    assert days == sorted(days)


def test_every_version_is_three_numbers():
    assert all(re.fullmatch(r"\d+\.\d+\.\d+", entry.version) for entry in HISTORY)


def test_every_release_says_something():
    assert all(entry.summary for entry in HISTORY)


def test_every_release_lists_what_changed():
    empty = [entry.version for entry in HISTORY if not entry.changes]
    assert empty == []


def test_a_release_with_no_breaking_notes_is_not_breaking():
    assert not HISTORY[0].is_breaking


def test_a_release_with_a_breaking_note_says_so():
    breaking = [entry for entry in HISTORY if entry.breaking]
    assert breaking
    assert all(entry.is_breaking for entry in breaking)


def test_a_release_that_names_a_migration_needs_one():
    with_migrations = [entry for entry in HISTORY if entry.migrations]
    assert with_migrations
    assert all(entry.needs_a_migration for entry in with_migrations)


def test_every_named_migration_is_a_file_on_disk():
    """A revision in the history that does not exist is worse than none.

    The upgrade note tells the house how many migrations a jump will run, and
    a number that counts a revision nobody wrote is a number that makes the
    house plan for a longer outage than it needs.
    """
    on_disk = {
        path.stem.split("_")[1]
        for path in MIGRATIONS.glob("*.py")
        if "_" in path.stem
    }
    named = {revision for entry in HISTORY for revision in entry.migrations}
    assert named <= on_disk, named - on_disk


def test_every_migration_on_disk_is_named_by_a_release():
    on_disk = {
        path.stem.split("_")[1]
        for path in MIGRATIONS.glob("*.py")
        if "_" in path.stem
    }
    named = {revision for entry in HISTORY for revision in entry.migrations}
    assert on_disk <= named, on_disk - named


def test_nothing_was_released_after_the_newest():
    assert since(latest().version) == ()


def test_everything_comes_back_from_the_beginning():
    assert since("0.0.0") == HISTORY


def test_a_jump_of_two_returns_two():
    assert len(since(HISTORY[-3].version)) == 2


def test_a_version_nobody_shipped_still_answers():
    assert len(since("0.5.5")) == 3


def test_the_breaking_notes_carry_their_version():
    lines = breaking_since("0.0.0")
    assert lines
    assert all(re.match(r"\d+\.\d+\.\d+: ", line) for line in lines)


def test_an_upgrade_with_nothing_breaking_says_nothing():
    assert breaking_since(latest().version) == ()


def test_the_migrations_for_a_jump_add_up():
    revisions = migrations_since("0.0.0")
    assert len(revisions) == sum(len(entry.migrations) for entry in HISTORY)


def test_a_jump_from_the_newest_runs_no_migrations():
    assert migrations_since(latest().version) == ()


def test_a_release_that_is_not_in_the_history_is_refused(monkeypatch):
    monkeypatch.setattr("bushelworks.release.__version__", "9.9.9")
    with pytest.raises(ValueError):
        current()


def test_one_release_renders_as_a_block():
    text = render(latest())
    assert text.startswith(latest().version)
    assert latest().summary in text


def test_a_breaking_note_is_marked_in_the_block():
    loud = next(entry for entry in HISTORY if entry.breaking)
    text = render(loud)
    assert any(line.strip().startswith("!") for line in text.split("\n"))


def test_the_migrations_are_named_in_the_block():
    assert "migrations:" in render(latest())


def test_a_release_with_no_migrations_does_not_mention_them():
    quiet = next(entry for entry in HISTORY if not entry.migrations)
    assert "migrations:" not in render(quiet)


def test_the_history_prints_newest_first():
    text = render_history()
    assert text.index(HISTORY[-1].version) < text.index(HISTORY[0].version)


def test_the_command_prints_the_whole_history(runner):
    result = runner.invoke(cli, ["changes"], catch_exceptions=False)
    assert result.exit_code == 0
    for entry in HISTORY:
        assert entry.version in result.output


def test_the_command_can_be_asked_for_a_jump(runner):
    result = runner.invoke(
        cli, ["changes", "--since", HISTORY[-2].version], catch_exceptions=False
    )
    assert HISTORY[-1].version in result.output
    assert HISTORY[0].version not in result.output


def test_the_command_counts_the_migrations_a_jump_runs(runner):
    result = runner.invoke(
        cli, ["changes", "--since", "0.0.0"], catch_exceptions=False
    )
    assert "migrations" in result.output


def test_a_jump_from_the_newest_says_there_is_nothing(runner):
    result = runner.invoke(
        cli, ["changes", "--since", latest().version], catch_exceptions=False
    )
    assert "nothing since then" in result.output


def test_the_command_can_be_asked_for_only_the_breaking_lines(runner):
    result = runner.invoke(
        cli, ["changes", "--since", "0.0.0", "--breaking"], catch_exceptions=False
    )
    assert "settlement line codes" in result.output
    assert HISTORY[0].summary not in result.output


def test_a_clean_upgrade_says_nothing_is_breaking(runner):
    result = runner.invoke(
        cli,
        ["changes", "--since", latest().version, "--breaking"],
        catch_exceptions=False,
    )
    assert "nothing breaking" in result.output
