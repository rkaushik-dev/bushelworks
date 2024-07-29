"""Who is allowed to call, and what they are allowed to do.

The scale house runs on a private network and for years the answer was "anybody
who can reach it". That stopped being true when the producer portal went up, so
keys carry a role and the routes that move money ask for one.

Three roles, and the split is by what a mistake costs rather than by job title:

* ``read`` can see anything but change nothing
* ``scale`` can write tickets, which is what the driveway needs
* ``office`` can settle, pay and amend

Keys are compared with :func:`secrets.compare_digest`. The timing difference on
a plain equality check is not exploitable over the office network, and it costs
nothing to not have to argue about that.
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from enum import Enum

from fastapi import Header, Request

from ..config import Settings
from ..errors import BushelworksError


class Role(str, Enum):
    READ = "read"
    SCALE = "scale"
    OFFICE = "office"

    @property
    def rank(self) -> int:
        return _RANK[self]


_RANK = {Role.READ: 0, Role.SCALE: 1, Role.OFFICE: 2}


class NotAuthorised(BushelworksError):
    """No usable key. Mapped to 401."""

    code = "not_authorised"


class Forbidden(BushelworksError):
    """A key that is real but not enough. Mapped to 403."""

    code = "forbidden"


@dataclass(frozen=True, slots=True)
class Caller:
    """Who is on the other end of this request."""

    key_id: str
    role: Role

    def can(self, needed: Role) -> bool:
        return self.role.rank >= needed.rank


ANONYMOUS = Caller(key_id="anonymous", role=Role.OFFICE)


def parse_keys(raw: str) -> dict[str, Role]:
    """Read ``id:role:secret`` entries out of the configured key list.

    A bare secret with no role is treated as an office key, which is what every
    key was before roles existed. Nobody has to reissue anything to upgrade.
    """
    keys: dict[str, Role] = {}
    for entry in raw.split(","):
        entry = entry.strip()
        if not entry:
            continue
        parts = entry.split(":")
        if len(parts) == 1:
            keys[parts[0]] = Role.OFFICE
            continue
        if len(parts) != 3:
            raise ValueError(f"cannot read an api key out of {entry!r}")
        _, role, secret = parts
        keys[secret] = Role(role)
    return keys


def resolve(settings: Settings, presented: str | None) -> Caller:
    """Turn a header into a caller, or refuse."""
    if not settings.requires_api_key:
        return ANONYMOUS
    if not presented:
        raise NotAuthorised("this house requires an X-API-Key header")
    for secret, role in settings.key_roles.items():
        if secrets.compare_digest(secret, presented):
            return Caller(key_id=secret[:4] + "...", role=role)
    raise NotAuthorised("that API key is not on file")


def require(role: Role):
    """A dependency that refuses a caller who is not senior enough."""

    def dependency(
        request: Request,
        x_api_key: str | None = Header(default=None, alias="X-API-Key"),
    ) -> Caller:
        settings: Settings = request.app.state.settings
        caller = resolve(settings, x_api_key)
        if not caller.can(role):
            raise Forbidden(
                f"this needs the {role.value} role and that key is {caller.role.value}"
            )
        request.state.caller = caller
        return caller

    return dependency


read_access = require(Role.READ)
scale_access = require(Role.SCALE)
office_access = require(Role.OFFICE)
