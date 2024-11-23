"""Spreadsheet exports.

Everybody who asks for data asks for it as a CSV, and the two people who ask
most are the auditor and the lender. Neither of them wants the printed layout,
and both of them will open it in a spreadsheet that guesses at types, so the
rules here are about not being helpfully reinterpreted:

* dates are ISO, because a spreadsheet reads 03/10/2024 as March in one country
  and October in the other
* quantities are unrounded decimals as text
* a ticket number that looks like a number is still text, and it is written so
  that it stays that way

The last one is why every identifier goes through :func:`text_field`. A ticket
numbered 0010441 that loses its leading zeroes is a ticket nobody can find.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterable, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

from ..domain.settlement import Settlement
from ..errors import ValidationError

TICKET_COLUMNS = (
    "ticket_number",
    "location_code",
    "scale_date",
    "commodity_code",
    "party_code",
    "gross_pounds",
    "tare_pounds",
    "net_pounds",
    "dockage_percent",
    "disposition",
    "contract_number",
    "voided",
    "settled",
)

SETTLEMENT_COLUMNS = (
    "number",
    "settled_on",
    "party_code",
    "location_code",
    "gross",
    "deductions",
    "net",
    "tickets",
)

LINE_COLUMNS = (
    "settlement_number",
    "sequence",
    "kind",
    "code",
    "description",
    "dollars",
    "signed",
    "quantity",
    "rate",
)


class ExportError(ValidationError):
    """Raised when something cannot be written out."""

    code = "export_error"


def text_field(value: Any) -> str:
    """Write an identifier so a spreadsheet leaves it alone.

    The leading equals and quotes are what Excel needs to keep 0010441 as
    0010441. It looks odd in a text editor and it is the difference between a
    file the office can use and one it cannot.
    """
    if value is None:
        return ""
    text = str(value)
    if text and text.lstrip("0") != text and text.isdigit():
        return f'="{text}"'
    return text


def cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, date):
        return value.isoformat()
    return str(value)


def _writer(buffer: io.StringIO) -> Any:
    return csv.writer(buffer, lineterminator="\n")


def tickets_to_csv(rows: Iterable[Any]) -> str:
    """Scale tickets, one row each."""
    buffer = io.StringIO()
    writer = _writer(buffer)
    writer.writerow(TICKET_COLUMNS)
    for row in rows:
        writer.writerow(
            [
                text_field(row.ticket_number),
                cell(row.location_code),
                cell(row.scale_date),
                cell(row.commodity_code),
                cell(row.party_code),
                cell(row.gross_pounds),
                cell(row.tare_pounds),
                cell(row.net_pounds),
                cell(row.dockage_percent),
                cell(row.disposition),
                text_field(row.contract_number),
                cell(row.voided),
                cell(row.settled),
            ]
        )
    return buffer.getvalue()


def settlements_to_csv(settlements: Sequence[Settlement]) -> str:
    """One row per statement, with the tickets it covers in a cell."""
    buffer = io.StringIO()
    writer = _writer(buffer)
    writer.writerow(SETTLEMENT_COLUMNS)
    for statement in settlements:
        writer.writerow(
            [
                text_field(statement.number),
                cell(statement.settled_on),
                cell(statement.party_id),
                cell(statement.location_code),
                cell(statement.gross),
                cell(statement.deductions),
                cell(statement.net),
                " ".join(statement.ticket_numbers),
            ]
        )
    return buffer.getvalue()


def lines_to_csv(settlements: Sequence[Settlement]) -> str:
    """Every settlement line, which is what the auditor actually wants."""
    buffer = io.StringIO()
    writer = _writer(buffer)
    writer.writerow(LINE_COLUMNS)
    for statement in settlements:
        for index, line in enumerate(statement.lines, start=1):
            writer.writerow(
                [
                    text_field(statement.number),
                    index,
                    cell(line.kind.value),
                    cell(line.code),
                    cell(line.description),
                    cell(line.dollars),
                    cell(line.signed),
                    cell(line.quantity),
                    cell(line.rate),
                ]
            )
    return buffer.getvalue()


def check_round_trip(text: str, expected_columns: Sequence[str]) -> int:
    """Read an export back and say how many rows it holds.

    Run against every file the office sends out. A CSV with an embedded newline
    in a remark reads back with the wrong number of columns, and finding that
    out here is much cheaper than finding it out from the auditor.
    """
    reader = csv.reader(io.StringIO(text))
    rows = list(reader)
    if not rows:
        raise ExportError("the export is empty, not even a header")
    header = rows[0]
    if header != list(expected_columns):
        raise ExportError(
            f"header is {header}, expected {list(expected_columns)}"
        )
    for index, row in enumerate(rows[1:], start=2):
        if len(row) != len(header):
            raise ExportError(
                f"row {index} has {len(row)} columns, expected {len(header)}"
            )
    return len(rows) - 1
