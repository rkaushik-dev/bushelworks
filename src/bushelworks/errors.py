"""One root for everything this package raises.

Callers were catching ``ValueError`` to mean "the scale house refused this",
which also swallows a genuine programming mistake three frames down. Every
error the package raises on purpose now descends from
:class:`BushelworksError`, and the ones that were already ``ValueError`` or
``KeyError`` keep that ancestry so existing handlers do not break.

Each error carries a ``code``. The API layer maps codes to responses and the
statement printer maps them to something a grain accountant can act on, and
neither has to match on message text.
"""

from __future__ import annotations


def _default_code(name: str) -> str:
    """Turn ``ContractError`` into ``contract_error``."""
    out: list[str] = []
    for index, char in enumerate(name):
        if char.isupper() and index:
            out.append("_")
        out.append(char.lower())
    return "".join(out)


class BushelworksError(Exception):
    """Base for every error raised on purpose.

    A subclass that does not name a code gets one derived from its class name,
    so a new error is never silently reported to the API as its parent's code.
    That was happening: every domain error came back as ``validation_error``
    because none of them set one, and the caller could not tell a bad contract
    from a bad ticket.
    """

    code = "bushelworks_error"

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if "code" not in cls.__dict__:
            cls.code = _default_code(cls.__name__)

    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code

    def __str__(self) -> str:
        return self.message

    def as_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


class ValidationError(BushelworksError, ValueError):
    """Something handed in does not hold together."""

    code = "validation_error"


class LookupFailure(BushelworksError, KeyError):
    """Something referred to is not on file."""

    code = "not_found"

    def __str__(self) -> str:
        return self.message


class ConflictError(BushelworksError):
    """The operation is legal but the current state will not take it."""

    code = "conflict"


class ConfigurationError(BushelworksError):
    """The house is set up in a way the package cannot work with."""

    code = "configuration_error"
