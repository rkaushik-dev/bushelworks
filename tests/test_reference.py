"""The generated reference has to match what the code actually does.

The README used to carry a hand written command list and a hand written grade
ladder. Both went stale. These are generated now and this is what keeps the
worked example in the README honest.
"""

from pathlib import Path

import pytest

from bushelworks.cli.main import cli
from bushelworks.domain import commodity as cm
from bushelworks.reference import (
    command_reference,
    command_rows,
    commodity_reference,
    discount_reference,
    grade_reference,
    render_all,
    worked_example,
)

README = Path(__file__).resolve().parents[1] / "README.md"


def test_every_command_is_listed():
    listed = {name for name, _ in command_rows()}
    assert listed == set(cli.commands)


def test_every_command_has_a_line_of_help():
    for name, text in command_rows():
        assert text, name


def test_the_command_table_is_markdown():
    text = command_reference()
    assert text.split("\n")[0].startswith("| command")
    assert "settle" in text
    assert "position" in text


def test_the_commodity_table_lists_the_whole_master():
    text = commodity_reference()
    for code in cm.codes():
        assert code in text


def test_the_commodity_table_carries_the_test_weight():
    assert "| 56 |" in commodity_reference()


def test_the_grade_ladder_has_a_row_per_rung():
    text = grade_reference("CORN")
    rows = [line for line in text.split("\n") if line.startswith("| US")]
    assert len(rows) == 5


def test_the_grade_ladder_names_its_factors():
    text = grade_reference("CORN")
    assert "test weight" in text
    assert "bcfm" in text


def test_a_ladder_with_fewer_grades_has_fewer_rows():
    rows = [line for line in grade_reference("SOYB").split("\n") if line.startswith("| US")]
    assert len(rows) == 4


def test_the_discount_table_lists_the_filed_schedules():
    text = discount_reference("CORN")
    assert "test weight" in text
    assert "bcfm" in text


def test_a_commodity_with_no_schedules_says_so():
    assert discount_reference("MILO") == "no discount schedules filed"


def test_the_worked_example_is_the_load_everything_uses():
    text = worked_example()
    assert "78420 lb" in text
    assert "932.8571" in text
    assert "897.6324" in text


def test_the_readme_carries_the_same_worked_load():
    readme = README.read_text(encoding="utf-8")
    for line in worked_example().split("\n"):
        figure = line.split()[-1]
        assert figure in readme, figure


def test_rendering_everything_holds_each_section():
    text = render_all()
    for heading in (
        "## Commands",
        "## Commodities",
        "## Corn grade ladder",
        "## Corn discount schedules",
        "## A worked load",
    ):
        assert heading in text


def test_the_readme_names_the_packages_that_exist():
    readme = README.read_text(encoding="utf-8")
    for package in (
        "bushelworks.domain",
        "bushelworks.schedules",
        "bushelworks.services",
        "bushelworks.persistence",
        "bushelworks.api",
        "bushelworks.cli",
        "bushelworks.reports",
        "bushelworks.devices",
    ):
        assert package in readme


@pytest.mark.parametrize("commodity_code", ["CORN", "SOYB", "HRW", "MILO"])
def test_every_ladder_renders(commodity_code):
    assert grade_reference(commodity_code).startswith("| grade |")
