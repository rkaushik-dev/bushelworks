"""The ACH file the bank wants instead of cheques.

NACHA format, PPD entries, one batch. Every record is exactly ninety four
characters and the file is padded with filler records until the block count is a
multiple of ten, because the bank's reader is older than anybody who works
there and it will reject a short block without saying why.

The two totals at the bottom are computed rather than passed in. A file whose
control record disagrees with its entries is rejected as a whole, and the only
way to be sure they agree is to add them here.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Sequence

from ..errors import ValidationError

RECORD_LENGTH = 94
BLOCKING_FACTOR = 10


class AchError(ValidationError):
    """Raised when a file cannot be built to the format."""

    code = "ach_error"


@dataclass(frozen=True, slots=True)
class Originator:
    """The house, as the bank knows it."""

    name: str
    company_id: str
    routing_number: str
    bank_name: str

    def __post_init__(self) -> None:
        if len(self.routing_number) != 9 or not self.routing_number.isdigit():
            raise AchError(f"{self.routing_number!r} is not a routing number")
        if len(self.company_id) != 10:
            raise AchError("a company id is ten characters")


@dataclass(frozen=True, slots=True)
class Credit:
    """One payment to one producer."""

    party_code: str
    name: str
    routing_number: str
    account_number: str
    dollars: Decimal
    reference: str

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise AchError(f"{self.party_code}: an ACH credit has to be positive")
        if len(self.routing_number) != 9 or not self.routing_number.isdigit():
            raise AchError(f"{self.party_code}: {self.routing_number!r} is not a routing number")
        if not self.account_number:
            raise AchError(f"{self.party_code}: no account number")

    @property
    def cents(self) -> int:
        return int((self.dollars * 100).to_integral_value())


def check_digit(routing_number: str) -> int:
    """The ninth digit of a routing number, from the first eight."""
    weights = (3, 7, 1, 3, 7, 1, 3, 7)
    total = sum(int(d) * w for d, w in zip(routing_number[:8], weights, strict=True))
    return (10 - (total % 10)) % 10


def routing_is_valid(routing_number: str) -> bool:
    if len(routing_number) != 9 or not routing_number.isdigit():
        return False
    return check_digit(routing_number) == int(routing_number[8])


def _pad(text: str, width: int) -> str:
    return text[:width].ljust(width)


def _num(value: int, width: int) -> str:
    text = str(value)
    if len(text) > width:
        raise AchError(f"{value} does not fit in {width} places")
    return text.zfill(width)


def file_header(originator: Originator, created: date, destination: str) -> str:
    return (
        "101"
        + f" {destination[:9]:>9}"
        + f" {originator.company_id[:9]:>9}"
        + created.strftime("%y%m%d")
        + "0000"
        + "A094101"
        + _pad(originator.bank_name, 23)
        + _pad(originator.name, 23)
        + " " * 8
    )


def batch_header(originator: Originator, effective: date) -> str:
    return (
        "5220"
        + _pad(originator.name, 16)
        + " " * 20
        + _pad(originator.company_id, 10)
        + "PPD"
        + _pad("GRAIN PAY", 10)
        + " " * 6
        + effective.strftime("%y%m%d")
        + " " * 3
        + "1"
        + originator.routing_number[:8]
        + "0000001"
    )


def entry(credit: Credit, originator: Originator, sequence: int) -> str:
    return (
        "622"
        + credit.routing_number[:8]
        + credit.routing_number[8]
        + _pad(credit.account_number, 17)
        + _num(credit.cents, 10)
        + _pad(credit.party_code, 15)
        + _pad(credit.name, 22)
        + "  "
        + "0"
        + originator.routing_number[:8]
        + _num(sequence, 7)
    )


def batch_control(
    originator: Originator, credits: Sequence[Credit], entry_hash: int
) -> str:
    cents = sum(credit.cents for credit in credits)
    return (
        "8220"
        + _num(len(credits), 6)
        + _num(entry_hash % 10_000_000_000, 10)
        + _num(0, 12)
        + _num(cents, 12)
        + _pad(originator.company_id, 10)
        + " " * 19
        + " " * 6
        + originator.routing_number[:8]
        + "0000001"
    )


def file_control(
    credits: Sequence[Credit], entry_hash: int, block_count: int
) -> str:
    cents = sum(credit.cents for credit in credits)
    # Header, batch header, entries, batch control, file control.
    records = 4 + len(credits)
    return (
        "9"
        + _num(1, 6)
        + _num(block_count, 6)
        + _num(records, 8)
        + _num(entry_hash % 10_000_000_000, 10)
        + _num(0, 12)
        + _num(cents, 12)
        + " " * 39
    )


def build(
    originator: Originator,
    credits: Sequence[Credit],
    created: date,
    effective: date,
    destination: str,
) -> str:
    """The whole file, blocked and padded, ready for the bank."""
    if not credits:
        raise AchError("an ACH file needs at least one credit")

    entry_hash = sum(int(credit.routing_number[:8]) for credit in credits)
    records = [
        file_header(originator, created, destination),
        batch_header(originator, effective),
    ]
    records.extend(
        entry(credit, originator, index + 1) for index, credit in enumerate(credits)
    )
    records.append(batch_control(originator, credits, entry_hash))

    block_count = -(-(len(records) + 1) // BLOCKING_FACTOR)
    records.append(file_control(credits, entry_hash, block_count))
    while len(records) % BLOCKING_FACTOR:
        records.append("9" * RECORD_LENGTH)

    for index, record in enumerate(records):
        if len(record) != RECORD_LENGTH:
            raise AchError(
                f"record {index + 1} is {len(record)} characters, not {RECORD_LENGTH}"
            )
    return "\n".join(records) + "\n"


def total_cents(credits: Sequence[Credit]) -> int:
    return sum(credit.cents for credit in credits)
