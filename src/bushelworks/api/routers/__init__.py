"""Routers, one per thing the office asks about."""

from .bins import router as bins_router
from .contracts import router as contracts_router
from .deferred import router as deferred_router
from .desk import router as desk_router
from .ops import router as ops_router
from .parties import router as parties_router
from .positions import router as positions_router
from .sales import router as sales_router
from .settlements import router as settlements_router
from .storage import router as storage_router
from .tickets import router as tickets_router

__all__ = [
    "bins_router",
    "contracts_router",
    "deferred_router",
    "desk_router",
    "ops_router",
    "parties_router",
    "positions_router",
    "sales_router",
    "settlements_router",
    "storage_router",
    "tickets_router",
]
