"""Dependencies the routers ask for.

The session dependency yields a unit of work rather than a bare session, so a
route that raises halfway through leaves nothing behind. Routes commit
explicitly; nothing commits on their behalf.
"""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Request

from ..config import Settings
from ..domain.rulebook import RuleBook
from ..persistence.unitofwork import UnitOfWork
from ..services.houses import house_rulebook
from .security import NotAuthorised, office_access, read_access, scale_access

__all__ = [
    "NotAuthorised",
    "get_rules",
    "get_settings",
    "get_uow",
    "office_access",
    "paging",
    "read_access",
    "require_api_key",
    "scale_access",
]


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_rules(request: Request) -> RuleBook:
    """The house rule book, built once at start up and shared.

    It is immutable in practice: nothing in a request path files a schedule, and
    a schedule change is a deploy. Rebuilding it per request would cost nothing
    and would hide that fact.
    """
    book = getattr(request.app.state, "rules", None)
    if book is None:
        book = house_rulebook(request.app.state.settings)
        request.app.state.rules = book
    return book


def get_uow(request: Request) -> Iterator[UnitOfWork]:
    factory = request.app.state.session_factory
    with UnitOfWork(factory) as uow:
        yield uow


# Kept as the name the routers already import. The check itself moved to
# bushelworks.api.security, which knows about roles; this is the weakest of
# them and means "any key the house recognises".
require_api_key = read_access


def paging(
    offset: int = 0,
    limit: int = 50,
    settings: Settings = Depends(get_settings),
) -> tuple[int, int]:
    """Clamp paging rather than refusing it.

    A spreadsheet asking for ten thousand rows gets the house maximum and a
    ``total`` telling it there is more, which is friendlier than a 422 to
    somebody who is not going to read one.
    """
    if offset < 0:
        offset = 0
    if limit < 1:
        limit = 1
    return offset, min(limit, settings.max_page_size)
