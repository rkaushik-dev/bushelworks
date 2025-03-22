"""The reference repository.

Parties and locations, which are the two things everything else points at. Read
mostly: the producer file changes a handful of times a year and the location
file changes when the house buys another elevator.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from ...errors import LookupFailure
from ..models import Location, Party


class PartyNotFound(LookupFailure):
    code = "party_not_found"


class PartyRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, code: str) -> Party:
        found = self.session.scalar(select(Party).where(Party.code == code))
        if found is None:
            raise PartyNotFound(f"no party {code}")
        return found

    def active(self) -> list[Party]:
        return list(
            self.session.scalars(
                select(Party).where(Party.is_active.is_(True)).order_by(Party.sort_name)
            )
        )

    def locations(self) -> list[Location]:
        return list(
            self.session.scalars(
                select(Location).where(Location.is_active.is_(True)).order_by(Location.code)
            )
        )
