"""The registry that decides which published table a ticket settles on.

Two dimensions resolve here and they resolve in a fixed order:

1. **Location.** A schedule filed against a specific elevator beats the one
   filed for the whole house. There is no blending and no fallback beyond one
   step: either the branch has its own table or it uses the company one.
2. **Date.** Among the versions that apply to the resolved location, the one
   with the latest effective date not after the date being asked about wins.

The order matters. A branch table filed in March does not lose to a company
table filed in April, because location is decided before date is looked at. That
is the rule the grain accountants use and it is the one that surprises people.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, timedelta
from typing import Generic, Iterable, Sequence, TypeVar

T = TypeVar("T")

COMPANY_WIDE = "*"


class NoScheduleInEffect(LookupError):
    """Raised when nothing is filed for a key on the date asked about."""


class OverlappingVersions(ValueError):
    """Raised when two versions of one key claim the same day."""


@dataclass(frozen=True, slots=True)
class Version(Generic[T]):
    """One filed version of a schedule."""

    effective_from: date
    payload: T
    effective_to: date | None = None
    location_code: str = COMPANY_WIDE
    note: str = ""

    def __post_init__(self) -> None:
        if self.effective_to is not None and self.effective_to < self.effective_from:
            raise OverlappingVersions(
                f"version starting {self.effective_from} ends before it begins"
            )

    def covers(self, as_of: date) -> bool:
        if as_of < self.effective_from:
            return False
        if self.effective_to is not None and as_of > self.effective_to:
            return False
        return True

    @property
    def is_company_wide(self) -> bool:
        return self.location_code == COMPANY_WIDE


class ScheduleRegistry(Generic[T]):
    """Versions of one kind of schedule, keyed by commodity."""

    def __init__(self, kind: str) -> None:
        self.kind = kind
        self._versions: dict[str, list[Version[T]]] = {}

    def file(self, commodity_code: str, version: Version[T]) -> None:
        """Add a version.

        Filing a table that starts later than an open ended one closes the open
        one the day before, which is what the office means when it says a new
        schedule "takes over" on the first. Anything else that would put two
        payloads on one day is refused, because there is no rule that says which
        of them a ticket should settle on.
        """
        bucket = self._versions.setdefault(commodity_code, [])
        for index, existing in enumerate(bucket):
            if existing.location_code != version.location_code:
                continue
            if not _overlaps(existing, version):
                continue
            if existing.effective_to is None and existing.effective_from < version.effective_from:
                bucket[index] = replace(
                    existing,
                    effective_to=version.effective_from - timedelta(days=1),
                )
                continue
            raise OverlappingVersions(
                f"{self.kind}/{commodity_code} at {version.location_code}: "
                f"{version.effective_from} overlaps a version already filed"
            )
        bucket.append(version)
        bucket.sort(key=lambda v: (v.effective_from, v.location_code))

    def file_all(self, commodity_code: str, versions: Iterable[Version[T]]) -> None:
        for version in versions:
            self.file(commodity_code, version)

    def resolve(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> T:
        """The payload in effect, or raise saying exactly what was missing."""
        version = self.resolve_version(commodity_code, as_of, location_code)
        return version.payload

    def resolve_version(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> Version[T]:
        bucket = self._versions.get(commodity_code, [])
        local = [v for v in bucket if v.location_code == location_code and v.covers(as_of)]
        if local:
            return max(local, key=lambda v: v.effective_from)

        if location_code != COMPANY_WIDE:
            company = [v for v in bucket if v.is_company_wide and v.covers(as_of)]
            if company:
                return max(company, key=lambda v: v.effective_from)

        raise NoScheduleInEffect(
            f"no {self.kind} filed for {commodity_code} at {location_code} on {as_of}"
        )

    def maybe_resolve(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> T | None:
        try:
            return self.resolve(commodity_code, as_of, location_code)
        except NoScheduleInEffect:
            return None

    def history(self, commodity_code: str) -> tuple[Version[T], ...]:
        return tuple(self._versions.get(commodity_code, []))

    def commodities(self) -> tuple[str, ...]:
        return tuple(sorted(self._versions))

    def locations(self, commodity_code: str) -> tuple[str, ...]:
        seen = {v.location_code for v in self._versions.get(commodity_code, [])}
        return tuple(sorted(seen))


def _overlaps(left: Version[T], right: Version[T]) -> bool:
    left_end = left.effective_to
    right_end = right.effective_to
    if left_end is not None and right.effective_from > left_end:
        return False
    if right_end is not None and left.effective_from > right_end:
        return False
    return True


def newest_first(versions: Sequence[Version[T]]) -> list[Version[T]]:
    return sorted(versions, key=lambda v: v.effective_from, reverse=True)
