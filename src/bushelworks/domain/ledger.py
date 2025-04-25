"""The general ledger the office keeps alongside the grain records.

Everything else in this package answers "how many bushels" and "what is the
producer owed". This answers "and what did that do to the books", which is a
different question with a different auditor asking it.

Two rules carry the whole module. A journal entry balances or it does not post,
and an account that has children is a heading rather than a place to put money.
The second one sounds like bookkeeping etiquette until somebody posts a
settlement to ``4000 revenue`` instead of ``4100 storage revenue`` and the
storage line on the year end comes out empty while the total is right.

Signs are not stored. A leg is a positive amount and a side, and what that does
to the account balance depends on which side the account normally carries. That
is more ceremony than a signed number, and it is the reason a debit to a revenue
account reads as a reduction on the trial balance instead of quietly adding.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ConflictError, LookupFailure, ValidationError
from ..money import ZERO_AMOUNT, amount, total


class LedgerError(ValidationError):
    """Raised when the books will not take what they were handed."""

    code = "ledger_error"


class AccountNotFound(LookupFailure):
    code = "account_not_found"


class Side(str, Enum):
    DEBIT = "debit"
    CREDIT = "credit"

    @property
    def opposite(self) -> Side:
        return Side.CREDIT if self is Side.DEBIT else Side.DEBIT


class AccountType(str, Enum):
    ASSET = "asset"
    LIABILITY = "liability"
    EQUITY = "equity"
    REVENUE = "revenue"
    EXPENSE = "expense"

    @property
    def normal_side(self) -> Side:
        """Which side increases this kind of account."""
        if self in (AccountType.ASSET, AccountType.EXPENSE):
            return Side.DEBIT
        return Side.CREDIT

    @property
    def on_balance_sheet(self) -> bool:
        return self in (AccountType.ASSET, AccountType.LIABILITY, AccountType.EQUITY)


@dataclass(frozen=True, slots=True)
class Account:
    """One line in the chart.

    ``parent`` is a code, not an object, because the chart is filed as a flat
    list and read back in whatever order the file happens to be in. Resolving
    parents at load time means a chart cannot be built out of order, and the
    office does add a sub account below one that is already there.
    """

    code: str
    name: str
    type: AccountType
    parent: str | None = None
    closes_to: str | None = None

    def __post_init__(self) -> None:
        if not self.code or self.code.strip() != self.code:
            raise LedgerError(f"account code {self.code!r} has to be a bare code")
        if not self.name.strip():
            raise LedgerError(f"{self.code}: an account needs a name")
        if self.parent == self.code:
            raise LedgerError(f"{self.code}: an account cannot be its own parent")

    @property
    def normal_side(self) -> Side:
        return self.type.normal_side


class ChartOfAccounts:
    """The accounts a house has filed, and which of them will take a posting."""

    def __init__(self, accounts: Iterable[Account] = ()) -> None:
        self._accounts: dict[str, Account] = {}
        self._children: dict[str, list[str]] = {}
        for account in accounts:
            self.add(account)

    def add(self, account: Account) -> None:
        if account.code in self._accounts:
            raise ConflictError(f"account {account.code} is already in the chart")
        if account.parent is not None:
            parent = self._accounts.get(account.parent)
            if parent is None:
                raise AccountNotFound(
                    f"{account.code}: parent {account.parent} is not in the chart yet"
                )
            if parent.type is not account.type:
                raise LedgerError(
                    f"{account.code} is a {account.type.value} under "
                    f"{parent.code}, which is a {parent.type.value}"
                )
            self._children.setdefault(account.parent, []).append(account.code)
        self._accounts[account.code] = account

    def __contains__(self, code: object) -> bool:
        return code in self._accounts

    def __len__(self) -> int:
        return len(self._accounts)

    def __iter__(self) -> Iterator[Account]:
        return iter(self.in_code_order())

    def get(self, code: str) -> Account:
        try:
            return self._accounts[code]
        except KeyError:
            raise AccountNotFound(f"no account {code} in the chart") from None

    def children_of(self, code: str) -> tuple[Account, ...]:
        self.get(code)
        return tuple(self._accounts[c] for c in sorted(self._children.get(code, ())))

    def is_heading(self, code: str) -> bool:
        """An account with children is a heading and holds no postings itself."""
        self.get(code)
        return bool(self._children.get(code))

    def postable(self, code: str) -> Account:
        """The account, if it is one a leg may name."""
        account = self.get(code)
        if self.is_heading(code):
            raise LedgerError(
                f"{code} {account.name} has sub accounts, so post to one of "
                f"{', '.join(c.code for c in self.children_of(code))} instead"
            )
        return account

    def in_code_order(self) -> tuple[Account, ...]:
        return tuple(self._accounts[c] for c in sorted(self._accounts))

    def of_type(self, type_: AccountType) -> tuple[Account, ...]:
        return tuple(a for a in self.in_code_order() if a.type is type_)

    def lineage(self, code: str) -> tuple[Account, ...]:
        """The account and every heading above it, nearest last."""
        chain: list[Account] = []
        seen: set[str] = set()
        current: str | None = code
        while current is not None:
            if current in seen:
                raise LedgerError(f"{code}: the chart loops back on {current}")
            seen.add(current)
            account = self.get(current)
            chain.append(account)
            current = account.parent
        return tuple(reversed(chain))


@dataclass(frozen=True, slots=True)
class Leg:
    """One side of one entry: an account, a side and a positive amount."""

    account_code: str
    side: Side
    dollars: Decimal
    memo: str = ""

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise LedgerError(
                f"{self.account_code}: a leg carries a positive amount and a "
                f"side, not {self.dollars}"
            )
        object.__setattr__(self, "dollars", amount(self.dollars))

    def effect_on(self, account: Account) -> Decimal:
        """What this leg does to the account balance, in its natural direction."""
        if self.side is account.normal_side:
            return amount(self.dollars)
        return amount(-self.dollars)


def debit(account_code: str, dollars: Decimal, memo: str = "") -> Leg:
    return Leg(account_code, Side.DEBIT, dollars, memo)


def credit(account_code: str, dollars: Decimal, memo: str = "") -> Leg:
    return Leg(account_code, Side.CREDIT, dollars, memo)


@dataclass(frozen=True, slots=True)
class JournalEntry:
    """A balanced set of legs, posted on a day, pointing back at its source."""

    number: str
    posted_on: date
    memo: str
    legs: Sequence[Leg]
    source: str = ""
    source_reference: str = ""

    def __post_init__(self) -> None:
        if len(self.legs) < 2:
            raise LedgerError(
                f"{self.number}: an entry needs at least two legs, got {len(self.legs)}"
            )
        object.__setattr__(self, "legs", tuple(self.legs))

    @property
    def debits(self) -> Decimal:
        return total(leg.dollars for leg in self.legs if leg.side is Side.DEBIT)

    @property
    def credits(self) -> Decimal:
        return total(leg.dollars for leg in self.legs if leg.side is Side.CREDIT)

    @property
    def balances(self) -> bool:
        return self.debits == self.credits

    def check_balances(self) -> None:
        if not self.balances:
            raise LedgerError(
                f"{self.number}: debits {self.debits} do not equal credits "
                f"{self.credits}, out by {amount(self.debits - self.credits)}"
            )

    def check_against(self, chart: ChartOfAccounts) -> None:
        """Balanced, and every leg names an account that will take a posting."""
        self.check_balances()
        for leg in self.legs:
            chart.postable(leg.account_code)

    def touching(self, account_code: str) -> tuple[Leg, ...]:
        return tuple(leg for leg in self.legs if leg.account_code == account_code)

    def reverse(self, number: str, posted_on: date, memo: str = "") -> JournalEntry:
        """The same entry with every side flipped.

        A wrong entry is corrected by posting its opposite, never by editing it.
        Both stay in the journal and the pair nets to nothing.
        """
        return JournalEntry(
            number=number,
            posted_on=posted_on,
            memo=memo or f"reversal of {self.number}",
            legs=tuple(
                Leg(leg.account_code, leg.side.opposite, leg.dollars, leg.memo)
                for leg in self.legs
            ),
            source=self.source,
            source_reference=self.source_reference,
        )


@dataclass(frozen=True, slots=True)
class TrialBalanceRow:
    account: Account
    debits: Decimal
    credits: Decimal

    @property
    def balance(self) -> Decimal:
        """Positive means the account sits on its normal side."""
        if self.account.normal_side is Side.DEBIT:
            return amount(self.debits - self.credits)
        return amount(self.credits - self.debits)

    @property
    def is_empty(self) -> bool:
        return self.debits == ZERO_AMOUNT and self.credits == ZERO_AMOUNT


@dataclass(slots=True)
class TrialBalance:
    """What the accounts add up to over a span of days."""

    through: date
    rows: tuple[TrialBalanceRow, ...] = field(default_factory=tuple)

    @property
    def debits(self) -> Decimal:
        return total(row.debits for row in self.rows)

    @property
    def credits(self) -> Decimal:
        return total(row.credits for row in self.rows)

    @property
    def balances(self) -> bool:
        return self.debits == self.credits

    def row(self, code: str) -> TrialBalanceRow:
        for row in self.rows:
            if row.account.code == code:
                return row
        raise AccountNotFound(f"{code} is not on this trial balance")

    def balance_of(self, code: str) -> Decimal:
        return self.row(code).balance

    def subtotal(self, type_: AccountType) -> Decimal:
        return total(row.balance for row in self.rows if row.account.type is type_)

    def nonempty(self) -> tuple[TrialBalanceRow, ...]:
        return tuple(row for row in self.rows if not row.is_empty)


def trial_balance(
    chart: ChartOfAccounts,
    entries: Iterable[JournalEntry],
    through: date,
    since: date | None = None,
) -> TrialBalance:
    """Add the entries up per account, headings excluded.

    Entries outside the window are skipped rather than refused, because the
    caller hands in the whole journal and asks for a month.
    """
    debits: dict[str, Decimal] = {}
    credits: dict[str, Decimal] = {}
    for entry in entries:
        if entry.posted_on > through:
            continue
        if since is not None and entry.posted_on < since:
            continue
        for leg in entry.legs:
            bucket = debits if leg.side is Side.DEBIT else credits
            bucket[leg.account_code] = amount(
                bucket.get(leg.account_code, ZERO_AMOUNT) + leg.dollars
            )

    rows = tuple(
        TrialBalanceRow(
            account=account,
            debits=debits.get(account.code, ZERO_AMOUNT),
            credits=credits.get(account.code, ZERO_AMOUNT),
        )
        for account in chart.in_code_order()
        if not chart.is_heading(account.code)
    )
    return TrialBalance(through=through, rows=rows)


def rollup(chart: ChartOfAccounts, balance: TrialBalance) -> Mapping[str, Decimal]:
    """Every account's balance with the headings filled in from below."""
    totals: dict[str, Decimal] = {a.code: ZERO_AMOUNT for a in chart}
    for row in balance.rows:
        for account in chart.lineage(row.account.code):
            totals[account.code] = amount(totals[account.code] + row.balance)
    return totals


def standard_chart() -> ChartOfAccounts:
    """The chart a country elevator of this size actually runs on.

    Short, and deliberately so. The headings exist because the year end is read
    by heading, and the leaves exist because somebody eventually asks what the
    dryer earned as against what the gas cost.
    """
    return ChartOfAccounts(
        [
            Account("1000", "Assets", AccountType.ASSET),
            Account("1100", "Cash in bank", AccountType.ASSET, parent="1000"),
            Account("1200", "Grain inventory", AccountType.ASSET, parent="1000"),
            Account("1210", "Grain inventory, company owned", AccountType.ASSET, parent="1200"),
            Account("1220", "Grain inventory, unpriced", AccountType.ASSET, parent="1200"),
            Account("1300", "Advances to producers", AccountType.ASSET, parent="1000"),
            Account("1400", "Accounts receivable", AccountType.ASSET, parent="1000"),
            Account("2000", "Liabilities", AccountType.LIABILITY),
            Account("2100", "Grain payable to producers", AccountType.LIABILITY, parent="2000"),
            Account("2200", "Checkoff payable", AccountType.LIABILITY, parent="2000"),
            Account("2300", "Deferred payment contracts", AccountType.LIABILITY, parent="2000"),
            Account("2400", "Storage billed in advance", AccountType.LIABILITY, parent="2000"),
            Account("3000", "Equity", AccountType.EQUITY),
            Account("3100", "Retained earnings", AccountType.EQUITY, parent="3000"),
            Account("4000", "Revenue", AccountType.REVENUE),
            Account("4100", "Storage revenue", AccountType.REVENUE, parent="4000"),
            Account("4200", "Drying revenue", AccountType.REVENUE, parent="4000"),
            Account("4300", "Handling and service", AccountType.REVENUE, parent="4000"),
            Account("4400", "Grain margin", AccountType.REVENUE, parent="4000"),
            Account("4500", "Demurrage recovered", AccountType.REVENUE, parent="4000"),
            Account("5000", "Expense", AccountType.EXPENSE),
            Account("5100", "Freight paid", AccountType.EXPENSE, parent="5000"),
            Account("5200", "Dryer fuel", AccountType.EXPENSE, parent="5000"),
            Account("5300", "Shrink and handling loss", AccountType.EXPENSE, parent="5000"),
            Account("5400", "Interest on advances", AccountType.EXPENSE, parent="5000"),
        ]
    )
