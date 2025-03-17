"""One transaction, all the repositories.

Taking a load in touches four tables and settling one touches five. Either all
of it lands or none of it does, and the caller should not have to remember
which session each repository was built against.

The unit of work is a context manager. Leaving the block without calling
``commit`` rolls back, which is deliberate: the failure mode we care about is a
half written settlement, and defaulting to rollback makes forgetting safe
instead of expensive.

The repositories are properties rather than attributes. They used to be
``X | None``, which meant every caller was either checking for ``None`` it could
not get or being told by the type checker that it should. Reaching for one
outside the block now raises with a sentence rather than failing three frames
down on ``NoneType has no attribute``.
"""

from __future__ import annotations

from collections.abc import Callable
from types import TracebackType

from sqlalchemy.orm import Session, sessionmaker

from ..errors import ConflictError
from .repositories import (
    ComplaintRepository,
    ContractRepository,
    PartyRepository,
    SettlementRepository,
    TicketRepository,
)


class UnitOfWork:
    """A transaction with the repositories bound to it."""

    def __init__(self, factory: Callable[[], Session]) -> None:
        self._factory = factory
        self._session: Session | None = None
        self._tickets: TicketRepository | None = None
        self._contracts: ContractRepository | None = None
        self._settlements: SettlementRepository | None = None
        self._parties: PartyRepository | None = None
        self._complaints: ComplaintRepository | None = None
        self._committed = False

    def __enter__(self) -> UnitOfWork:
        if self._session is not None:
            raise ConflictError("this unit of work is already open")
        session = self._factory()
        self._session = session
        self._tickets = TicketRepository(session)
        self._contracts = ContractRepository(session)
        self._settlements = SettlementRepository(session)
        self._parties = PartyRepository(session)
        self._complaints = ComplaintRepository(session)
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
            if self._session is not None:
                self._session.close()
            self._session = None
            self._tickets = None
            self._contracts = None
            self._settlements = None
            self._parties = None
            self._complaints = None

    def _require(self, thing: object, name: str) -> None:
        if thing is None:
            raise ConflictError(
                f"the {name} are only available inside an open unit of work"
            )

    @property
    def session(self) -> Session:
        self._require(self._session, "session")
        assert self._session is not None
        return self._session

    @property
    def tickets(self) -> TicketRepository:
        self._require(self._tickets, "ticket repository")
        assert self._tickets is not None
        return self._tickets

    @property
    def contracts(self) -> ContractRepository:
        self._require(self._contracts, "contract repository")
        assert self._contracts is not None
        return self._contracts

    @property
    def settlements(self) -> SettlementRepository:
        self._require(self._settlements, "settlement repository")
        assert self._settlements is not None
        return self._settlements

    @property
    def parties(self) -> PartyRepository:
        self._require(self._parties, "party repository")
        assert self._parties is not None
        return self._parties

    @property
    def complaints(self) -> ComplaintRepository:
        self._require(self._complaints, "complaint repository")
        assert self._complaints is not None
        return self._complaints

    @property
    def is_open(self) -> bool:
        return self._session is not None

    @property
    def committed(self) -> bool:
        return self._committed

    def commit(self) -> None:
        if self._session is None:
            raise ConflictError("cannot commit a unit of work that is not open")
        self._session.commit()
        self._committed = True

    def rollback(self) -> None:
        if self._session is not None:
            self._session.rollback()
        self._committed = False

    def flush(self) -> None:
        if self._session is None:
            raise ConflictError("cannot flush a unit of work that is not open")
        self._session.flush()


def unit_of_work(factory: sessionmaker[Session]) -> UnitOfWork:
    """Build a unit of work over a session factory."""
    return UnitOfWork(factory)
