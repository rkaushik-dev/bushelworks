"""Repositories, one module per group of tables.

Each one owns one aggregate and hands back domain objects rather than rows. The
query methods are named after what the office asks for rather than after the
columns they filter on, which is why there is an ``awaiting_settlement`` and not
a ``find(settled=False)``.

None of them commit. Committing is the unit of work's job, and a repository that
commits on its own makes a two step operation impossible to undo.

This was one file until the complaint register went in and took it past four
hundred lines. Same classes, same names, five modules, and everything still
imports from ``bushelworks.persistence.repositories``.
"""

from .contracts import ContractNotFound, ContractRepository
from .ledger import JournalEntryNotFound, LedgerRepository, PeriodNotFound
from .quality import ComplaintNotFound, ComplaintRepository
from .reference import PartyNotFound, PartyRepository
from .settlements import SettlementNotFound, SettlementRepository
from .tickets import TicketNotFound, TicketRepository

__all__ = [
    "ComplaintNotFound",
    "ComplaintRepository",
    "ContractNotFound",
    "ContractRepository",
    "JournalEntryNotFound",
    "LedgerRepository",
    "PartyNotFound",
    "PartyRepository",
    "PeriodNotFound",
    "SettlementNotFound",
    "SettlementRepository",
    "TicketNotFound",
    "TicketRepository",
]
