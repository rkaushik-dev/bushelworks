"""Routers, one per thing the office asks about."""

from .contracts import router as contracts_router
from .tickets import router as tickets_router

__all__ = ["contracts_router", "tickets_router"]
