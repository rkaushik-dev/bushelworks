"""Effective dated lookup for everything the house publishes.

Discount tables, shrink factors, drying rates and storage rates all change, and
they change on a date rather than on a deploy. A ticket written in October has
to settle on October's table however many times it is reprinted, so nothing in
this package reads the wall clock. Every lookup takes the date it is asking
about.
"""

from .registry import (
    NoScheduleInEffect,
    OverlappingVersions,
    ScheduleRegistry,
    Version,
)

__all__ = [
    "NoScheduleInEffect",
    "OverlappingVersions",
    "ScheduleRegistry",
    "Version",
]
