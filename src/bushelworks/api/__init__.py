"""The HTTP interface.

The scale house runs a touch screen against this and the grain accountant runs a
spreadsheet against it. Neither of them should be able to reach a rule that is
not in the domain, so every route here is thin: parse, call a service, render.
"""

from .app import create_app

__all__ = ["create_app"]
