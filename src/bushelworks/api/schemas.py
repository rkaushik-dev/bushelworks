"""Request and response bodies.

Quantities cross the wire as strings, not as JSON numbers. A bushel count with
four decimals and a price with four decimals both survive a round trip through
a double, but the products do not, and the first symptom is a settlement that is
a cent out on one statement in three hundred. Strings in, Decimal inside,
strings out.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator

from ..domain.commodity import Factor
from ..domain.tickets import Disposition, SplitRole


def _as_decimal(value: Any) -> Decimal:
    if isinstance(value, Decimal):
        return value
    if isinstance(value, (int, str)):
        try:
            return Decimal(str(value))
        except InvalidOperation as exc:
            raise ValueError(f"{value!r} is not a number") from exc
    if isinstance(value, float):
        raise ValueError(
            "send quantities as strings, not as JSON numbers, so they do not "
            "lose precision on the way in"
        )
    raise ValueError(f"{value!r} is not a number")


Quantity = Annotated[Decimal, Field()]


class Money(BaseModel):
    """Base for every body that carries decimals, so they serialise as strings."""

    model_config = ConfigDict(populate_by_name=True)

    @field_serializer("*", when_used="json")
    def _decimals_as_strings(self, value: Any) -> Any:
        if isinstance(value, Decimal):
            return str(value)
        return value


class SampleReading(Money):
    factor: Factor
    value: Quantity

    @field_validator("value", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class SplitIn(Money):
    party_code: str = Field(min_length=1, max_length=24)
    percent: Quantity
    role: SplitRole = SplitRole.OWNER

    @field_validator("percent", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class TicketIn(Money):
    """A load as the scale house sends it."""

    ticket_number: str = Field(min_length=1, max_length=24)
    location_code: str = Field(min_length=1, max_length=12)
    scale_date: date
    commodity_code: str = Field(min_length=1, max_length=8)
    party_code: str = Field(min_length=1, max_length=24)
    gross_pounds: Quantity
    tare_pounds: Quantity
    disposition: Disposition
    dockage_percent: Quantity = Decimal("0")
    contract_number: str | None = None
    truck_id: str = ""
    driver: str = ""
    farm: str = ""
    field_name: str = ""
    remarks: str = ""
    splits: list[SplitIn] = Field(default_factory=list)
    sample: list[SampleReading] = Field(default_factory=list)

    @field_validator("gross_pounds", "tare_pounds", "dockage_percent", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)

    @field_validator("splits")
    @classmethod
    def _at_least_one_share(cls, splits: list[SplitIn]) -> list[SplitIn]:
        if not splits:
            raise ValueError("a ticket needs at least one share")
        return splits


class GradeOut(Money):
    grade: str
    limiting_factors: list[str] = Field(default_factory=list)


class TicketOut(Money):
    ticket_number: str
    location_code: str
    scale_date: date
    commodity_code: str
    party_code: str
    gross_pounds: Quantity
    tare_pounds: Quantity
    net_pounds: Quantity
    dockage_percent: Quantity
    disposition: str
    contract_number: str | None = None
    voided: bool = False
    settled: bool = False
    sample: list[SampleReading] = Field(default_factory=list)
    splits: list[SplitIn] = Field(default_factory=list)


class GradedLoadOut(Money):
    ticket_number: str
    commodity_code: str
    gross_bushels: Quantity
    clean_bushels: Quantity
    dry_bushels: Quantity
    shrink_bushels: Quantity
    shrink_percent: Quantity
    moisture_points: Quantity
    discount_rate: Quantity
    grade: GradeOut


class SettlementLineOut(Money):
    kind: str
    code: str
    description: str
    dollars: Quantity
    quantity: Quantity | None = None
    rate: Quantity | None = None


class SettlementOut(Money):
    number: str
    settled_on: date
    party_code: str
    location_code: str
    gross: Quantity
    deductions: Quantity
    net: Quantity
    ticket_numbers: list[str] = Field(default_factory=list)
    contract_numbers: list[str] = Field(default_factory=list)
    lines: list[SettlementLineOut] = Field(default_factory=list)


class Page(Money):
    """A slice of a listing, with enough to ask for the next one."""

    items: list[Any]
    total: int
    offset: int
    limit: int

    @property
    def has_more(self) -> bool:
        return self.offset + len(self.items) < self.total


class Problem(Money):
    code: str
    detail: str
    request_id: str | None = None
