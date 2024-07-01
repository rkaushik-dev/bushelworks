"""Storage for the scale house.

The domain does not import anything from here. This package maps rows onto the
value objects in :mod:`bushelworks.domain` and nothing else; every rule stays on
the far side of that line so it can be tested without a database.
"""

from .base import Base, engine_for, metadata, session_factory

__all__ = ["Base", "metadata", "session_factory", "engine_for"]
