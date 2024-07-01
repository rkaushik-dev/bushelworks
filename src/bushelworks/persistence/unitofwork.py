"""One transaction, all the repositories.

Taking a load in touches four tables and settling one touches five. Either all
of it lands or none of it does, and the caller should not have to remember
which session each repository was built against.

The unit of work is a context manager. Leaving the block without calling
``commit`` rolls back, which is deliberate: the failure mode we care about is a
half written settlement, and defaulting to rollback makes forgetting safe
instead of expensive.
"""

from __future__ import annotations

from collections.abc import Callable
from types import TracebackType

from sqlalchemy.orm import Session, sessionmaker

from ..errors import ConflictError
from .repositories import (
    ContractRepository,
    PartyRepository,
    SettlementRepository,
    TicketRepository,
)


class UnitOfWork:
    """A transaction with the repositories bound to it."""

    def __init__(self, factory: Callable[[], Session]) -> None:
        self._factory = factory
        self.session: Session | None = None
        self.tickets: TicketRepository | None = None
        self.contracts: ContractRepository | None = None
        self.settlements: SettlementRepository | None = None
        self.parties: PartyRepository | None = None
        self._committed = False

    def __enter__(self) -> UnitOfWork:
        if self.session is not None:
            raise ConflictError("this unit of work is already open")
        session = self._factory()
        self.session = session
        self.tickets = TicketRepository(session)
        self.contracts = ContractRepository(session)
        self.settlements = SettlementRepository(session)
        self.parties = PartyRepository(session)
        self._committed = False
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        try:
            if exc_type is not None or not self._committed:
                self.rollback()
        finally:
            if self.session is not None:
                self.session.close()
            self.session = None
            self.tickets = None
            self.contracts = None
            self.settlements = None
            self.parties = None

    @property
    def is_open(self) -> bool:
        return self.session is not None

    @property
    def committed(self) -> bool:
        return self._committed

    def commit(self) -> None:
        if self.session is None:
            raise ConflictError("cannot commit a unit of work that is not open")
        self.session.commit()
        self._committed = True

    def rollback(self) -> None:
        if self.session is not None:
            self.session.rollback()
        self._committed = False

    def flush(self) -> None:
        if self.session is None:
            raise ConflictError("cannot flush a unit of work that is not open")
        self.session.flush()


def unit_of_work(factory: sessionmaker[Session]) -> UnitOfWork:
    """Build a unit of work over a session factory."""
    return UnitOfWork(factory)
