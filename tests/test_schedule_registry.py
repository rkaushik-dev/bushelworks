from datetime import date

import pytest

from bushelworks.schedules import (
    NoScheduleInEffect,
    OverlappingVersions,
    ScheduleRegistry,
    Version,
)
from bushelworks.schedules.registry import COMPANY_WIDE, newest_first


def registry() -> ScheduleRegistry[str]:
    reg: ScheduleRegistry[str] = ScheduleRegistry("discount")
    reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="company-jan"))
    reg.file("CORN", Version(effective_from=date(2024, 9, 1), payload="company-sep"))
    reg.file(
        "CORN",
        Version(
            effective_from=date(2024, 3, 1),
            payload="dorrance-mar",
            location_code="DORRANCE",
        ),
    )
    return reg


def test_resolves_the_latest_version_not_after_the_date():
    reg = registry()
    assert reg.resolve("CORN", date(2024, 8, 31)) == "company-jan"
    assert reg.resolve("CORN", date(2024, 9, 1)) == "company-sep"


def test_a_branch_table_beats_a_newer_company_table():
    reg = registry()
    assert reg.resolve("CORN", date(2024, 10, 5), "DORRANCE") == "dorrance-mar"


def test_a_branch_with_no_table_of_its_own_falls_back_to_the_company():
    reg = registry()
    assert reg.resolve("CORN", date(2024, 10, 5), "HOME") == "company-sep"


def test_nothing_in_effect_before_the_first_filing():
    reg = registry()
    with pytest.raises(NoScheduleInEffect):
        reg.resolve("CORN", date(2023, 12, 31))


def test_an_unknown_commodity_raises_the_same_way():
    reg = registry()
    with pytest.raises(NoScheduleInEffect):
        reg.resolve("SOYB", date(2024, 5, 1))


def test_maybe_resolve_returns_none_instead_of_raising():
    reg = registry()
    assert reg.maybe_resolve("SOYB", date(2024, 5, 1)) is None
    assert reg.maybe_resolve("CORN", date(2024, 5, 1)) == "company-jan"


def test_a_closed_version_stops_applying_after_its_end_date():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file(
        "CORN",
        Version(
            effective_from=date(2024, 1, 1),
            effective_to=date(2024, 6, 30),
            payload="first-half",
        ),
    )
    assert reg.resolve("CORN", date(2024, 6, 30)) == "first-half"
    with pytest.raises(NoScheduleInEffect):
        reg.resolve("CORN", date(2024, 7, 1))


def test_a_later_filing_closes_the_open_one_the_day_before():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="a"))
    reg.file("CORN", Version(effective_from=date(2024, 5, 1), payload="b"))
    closed = reg.history("CORN")[0]
    assert closed.payload == "a"
    assert closed.effective_to == date(2024, 4, 30)
    assert reg.resolve("CORN", date(2024, 4, 30)) == "a"
    assert reg.resolve("CORN", date(2024, 5, 1)) == "b"


def test_two_versions_may_not_start_on_the_same_day():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="a"))
    with pytest.raises(OverlappingVersions):
        reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="b"))


def test_a_filing_cannot_be_backdated_under_an_open_one():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file("CORN", Version(effective_from=date(2024, 5, 1), payload="a"))
    with pytest.raises(OverlappingVersions):
        reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="b"))


def test_a_closed_version_leaves_room_for_the_next_one():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file(
        "CORN",
        Version(
            effective_from=date(2024, 1, 1),
            effective_to=date(2024, 6, 30),
            payload="a",
        ),
    )
    reg.file("CORN", Version(effective_from=date(2024, 7, 1), payload="b"))
    assert reg.resolve("CORN", date(2024, 7, 2)) == "b"


def test_versions_for_different_sites_do_not_collide():
    reg: ScheduleRegistry[str] = ScheduleRegistry("shrink")
    reg.file("CORN", Version(effective_from=date(2024, 1, 1), payload="company"))
    reg.file(
        "CORN",
        Version(effective_from=date(2024, 1, 1), payload="branch", location_code="LURAY"),
    )
    assert reg.resolve("CORN", date(2024, 2, 1)) == "company"
    assert reg.resolve("CORN", date(2024, 2, 1), "LURAY") == "branch"


def test_a_version_may_not_end_before_it_starts():
    with pytest.raises(OverlappingVersions):
        Version(
            effective_from=date(2024, 6, 1),
            effective_to=date(2024, 5, 1),
            payload="nope",
        )


def test_covers_is_inclusive_at_both_ends():
    version = Version(
        effective_from=date(2024, 1, 1),
        effective_to=date(2024, 1, 31),
        payload="jan",
    )
    assert version.covers(date(2024, 1, 1))
    assert version.covers(date(2024, 1, 31))
    assert not version.covers(date(2024, 2, 1))
    assert version.is_company_wide


def test_registry_reports_what_it_holds():
    reg = registry()
    assert reg.commodities() == ("CORN",)
    assert reg.locations("CORN") == ("*", "DORRANCE")
    assert len(reg.history("CORN")) == 3
    assert reg.kind == "discount"


def test_newest_first_orders_by_effective_date():
    reg = registry()
    ordered = newest_first(reg.history("CORN"))
    assert ordered[0].effective_from == date(2024, 9, 1)
    assert ordered[-1].effective_from == date(2024, 1, 1)
    assert COMPANY_WIDE == "*"
