"""Routers, one per thing the office asks about."""

from .tickets import router as tickets_router

__all__ = ["tickets_router"]
