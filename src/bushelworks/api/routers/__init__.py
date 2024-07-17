"""Routers, one per thing the office asks about."""

from .contracts import router as contracts_router
from .positions import router as positions_router
from .settlements import router as settlements_router
from .storage import router as storage_router
from .tickets import router as tickets_router

__all__ = [
    "contracts_router",
    "positions_router",
    "settlements_router",
    "storage_router",
    "tickets_router",
]
