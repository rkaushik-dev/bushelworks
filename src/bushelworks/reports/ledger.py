"""The books, printed.

Three pages the office actually asks for. The trial balance is the working one
and the other two are what the accountant is sent in January.

Everything here is eighty columns because the ledger printer is the same dot
matrix the statements come off, and a report that wraps is a report somebody
retypes into a spreadsheet by hand.

Headings are indented by their depth in the chart rather than by a fixed rule,
so a house that files sub accounts three deep gets a readable page without
anybody changing this file.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from ..domain.ledger import (
    AccountType,
    ChartOfAccounts,
    TrialBalance,
    rollup,
)
from ..money import ZERO_AMOUNT, amount

WIDTH = 80


def _money(value: Decimal) -> str:
    """Cents, with a trailing minus the way a ledger prints one."""
    if value < 0:
        return f"{-value:,.2f}-"
    return f"{value:,.2f} "


def _rule(char: str = "-") -> str:
    return char * WIDTH


def _header(house_name: str, title: str, subtitle: str) -> list[str]:
    return [
        house_name.center(WIDTH).rstrip(),
        title.center(WIDTH).rstrip(),
        subtitle.center(WIDTH).rstrip(),
        _rule("="),
    ]


def trial_balance_page(
    balance: TrialBalance,
    house_name: str = "BUSHELWORKS ELEVATOR",
    include_empty: bool = False,
) -> str:
    """Every account, its two columns, and the pair that has to agree."""
    rows = balance.rows if include_empty else balance.nonempty()
    lines = _header(
        house_name,
        "TRIAL BALANCE",
        f"through {balance.through.isoformat()}",
    )
    lines.append(f"{'code':<8}{'account':<38}{'debits':>16}{'credits':>16}")
    lines.append(_rule())
    for row in rows:
        lines.append(
            f"{row.account.code:<8}{row.account.name[:37]:<38}"
            f"{_money(row.debits):>16}{_money(row.credits):>16}"
        )
    lines.append(_rule())
    lines.append(
        f"{'':<8}{'':<38}{_money(balance.debits):>16}{_money(balance.credits):>16}"
    )
    if not balance.balances:
        out = amount(balance.debits - balance.credits)
        lines.append(f"OUT OF BALANCE BY {_money(out)}")
    return "\n".join(lines)


def income_statement(
    chart: ChartOfAccounts,
    balance: TrialBalance,
    opens: date,
    house_name: str = "BUSHELWORKS ELEVATOR",
) -> str:
    """What the house earned in a span, revenue first then what it cost."""
    totals = rollup(chart, balance)
    lines = _header(
        house_name,
        "INCOME STATEMENT",
        f"{opens.isoformat()} to {balance.through.isoformat()}",
    )
    revenue = balance.subtotal(AccountType.REVENUE)
    expense = balance.subtotal(AccountType.EXPENSE)

    for label, type_ in (("REVENUE", AccountType.REVENUE), ("EXPENSE", AccountType.EXPENSE)):
        lines.append(label)
        for account in chart.of_type(type_):
            value = totals[account.code]
            if value == ZERO_AMOUNT and not chart.is_heading(account.code):
                continue
            depth = len(chart.lineage(account.code)) - 1
            name = ("  " * depth) + account.name
            lines.append(f"{account.code:<8}{name[:53]:<54}{_money(value):>18}")
        lines.append(_rule())

    lines.append(f"{'':<8}{'total revenue':<54}{_money(revenue):>18}")
    lines.append(f"{'':<8}{'total expense':<54}{_money(expense):>18}")
    lines.append(_rule("="))
    lines.append(f"{'':<8}{'margin':<54}{_money(amount(revenue - expense)):>18}")
    return "\n".join(lines)


def balance_sheet(
    chart: ChartOfAccounts,
    balance: TrialBalance,
    house_name: str = "BUSHELWORKS ELEVATOR",
) -> str:
    """What the house owns and what it owes, as at a day.

    The margin for the period is shown as its own line under equity rather than
    folded into retained earnings. Until the year is closed that money has not
    reached retained earnings, and showing it there would say it had.
    """
    totals = rollup(chart, balance)
    lines = _header(
        house_name,
        "BALANCE SHEET",
        f"as at {balance.through.isoformat()}",
    )
    assets = balance.subtotal(AccountType.ASSET)
    liabilities = balance.subtotal(AccountType.LIABILITY)
    equity = balance.subtotal(AccountType.EQUITY)
    margin = amount(
        balance.subtotal(AccountType.REVENUE) - balance.subtotal(AccountType.EXPENSE)
    )

    for label, type_ in (
        ("ASSETS", AccountType.ASSET),
        ("LIABILITIES", AccountType.LIABILITY),
        ("EQUITY", AccountType.EQUITY),
    ):
        lines.append(label)
        for account in chart.of_type(type_):
            value = totals[account.code]
            if value == ZERO_AMOUNT and not chart.is_heading(account.code):
                continue
            depth = len(chart.lineage(account.code)) - 1
            name = ("  " * depth) + account.name
            lines.append(f"{account.code:<8}{name[:53]:<54}{_money(value):>18}")
        if type_ is AccountType.EQUITY:
            lines.append(f"{'':<8}{'  margin for the period':<54}{_money(margin):>18}")
        lines.append(_rule())

    lines.append(f"{'':<8}{'total assets':<54}{_money(assets):>18}")
    claims = amount(liabilities + equity + margin)
    lines.append(f"{'':<8}{'liabilities and equity':<54}{_money(claims):>18}")
    lines.append(_rule("="))
    if assets != claims:
        lines.append(f"OUT OF BALANCE BY {_money(amount(assets - claims))}")
    else:
        lines.append(f"{'':<8}{'in balance':<54}")
    return "\n".join(lines)


def to_csv(balance: TrialBalance, include_empty: bool = False) -> str:
    """The trial balance as a file the accounting package will take.

    Amounts are unformatted and unsigned per column, because the package reads
    the two columns and works the sign out itself. Formatting them the way the
    printed page does is what broke the first import.
    """
    rows = balance.rows if include_empty else balance.nonempty()
    out = ["code,account,type,debits,credits,balance"]
    for row in rows:
        name = row.account.name.replace('"', '""')
        out.append(
            f'{row.account.code},"{name}",{row.account.type.value},'
            f"{row.debits},{row.credits},{row.balance}"
        )
    return "\n".join(out) + "\n"
