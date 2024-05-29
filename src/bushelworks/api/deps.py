"""Dependencies the routers ask for.

The session dependency yields a unit of work rather than a bare session, so a
route that raises halfway through leaves nothing behind. Routes commit
explicitly; nothing commits on their behalf.
"""

from __future__ import annotations

from typing import Iterator

from fastapi import Depends, Header, Request

from ..config import Settings
from ..errors import BushelworksError
from ..persistence.unitofwork import UnitOfWork


class NotAuthorised(BushelworksError):
    """Raised when a caller has no usable key. Mapped to 401 by the API."""

    code = "not_authorised"


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_uow(request: Request) -> Iterator[UnitOfWork]:
    factory = request.app.state.session_factory
    with UnitOfWork(factory) as uow:
        yield uow


def require_api_key(
    request: Request,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
) -> str:
    """Check the key, unless the house has not configured any.

    A house with no keys on file is running on a private network behind the
    scale house door, which is how most of them run. Turning the check on is a
    setting rather than a deploy.
    """
    settings: Settings = request.app.state.settings
    if not settings.requires_api_key:
        return "anonymous"
    if x_api_key is None:
        raise NotAuthorised("this house requires an X-API-Key header")
    if x_api_key not in settings.api_keys:
        raise NotAuthorised("that API key is not on file")
    return x_api_key


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
