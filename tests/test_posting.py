"""A statement, turned into an entry, checked against the books.

The arithmetic here is worth stating once. The producer's net is the gross less
everything that reduces it, so an entry that debits the gross, credits each
deduction to wherever it belongs and credits the net to the payable balances by
construction. That is why there is no plug leg anywhere in the service, and why
a test that finds one out of balance has found a real disagreement between the
statement and the ledger rather than a rounding nuisance.
"""

from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.ledger import Side, standard_chart, trial_balance
from bushelworks.domain.settlement import LineKind, SettlementBuilder
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import (
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from bushelworks.persistence.models import PeriodRow
from bushelworks.persistence.unitofwork import UnitOfWork
from bushelworks.services.posting import (
    DEFAULT_MAP,
    SOURCE,
    AlreadyPosted,
    PeriodClosed,
    PostingError,
    PostingMap,
    entry_for_settlement,
    plan_settlement,
    post_settlement,
    require_open_period,
    reverse_settlement,
    unposted,
)

MAY = date(2025, 5, 12)


def a_statement(number="S-2025-0004", party="MERRICK"):
    """4,350.00 of corn, less a discount, drying, storage, checkoff and an advance."""
    builder = SettlementBuilder(number, MAY, party, "HOME")
    builder.reference_ticket("A-10441")
    builder.add_gross("GRAIN", "corn 1000 bu at 4.3500", Decimal("1000"), Decimal("4.3500"))
    builder.add_charge(
        LineKind.DISCOUNT, "MOISTURE", "moisture discount", Decimal("21.00")
    )
    builder.add_charge(LineKind.DRYING, "DRYING", "drying charge", Decimal("118.75"))
    builder.add_charge(LineKind.STORAGE, "STORAGE", "storage", Decimal("42.30"))
    builder.add_charge(LineKind.ASSESSMENT, "KSCORN", "Kansas corn", Decimal("4.35"))
    builder.add_charge(LineKind.ADVANCE, "ADV-1", "advance recovered", Decimal("500.00"))
    return builder.build()


@pytest.fixture
def engine():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield engine
    drop_all(engine)


@pytest.fixture
def uow(engine):
    with UnitOfWork(session_factory(engine)) as unit:
        unit.ledger.file_chart(standard_chart())
        unit.ledger.add_periods(
            [
                PeriodRow(
                    code="2025-04",
                    starts_on=date(2025, 4, 1),
                    ends_on=date(2025, 4, 30),
                    closed=True,
                    closed_on=date(2025, 5, 8),
                    closed_by="office",
                ),
                PeriodRow(
                    code="2025-05",
                    starts_on=date(2025, 5, 1),
                    ends_on=date(2025, 5, 31),
                ),
            ]
        )
        unit.commit()
        yield unit


class TestTheMap:
    def test_a_deduction_is_credited_and_the_grain_is_debited(self) -> None:
        assert DEFAULT_MAP.side_for(LineKind.GROSS) is Side.DEBIT
        assert DEFAULT_MAP.side_for(LineKind.STORAGE) is Side.CREDIT
        assert DEFAULT_MAP.side_for(LineKind.ADVANCE) is Side.CREDIT

    def test_an_adjustment_that_adds_to_the_net_is_a_debit(self) -> None:
        assert DEFAULT_MAP.side_for(LineKind.ADJUSTMENT) is Side.DEBIT

    def test_a_discount_lands_against_the_cost_of_the_grain(self) -> None:
        assert DEFAULT_MAP.account_for(LineKind.DISCOUNT) == DEFAULT_MAP.inventory

    def test_a_house_can_file_discounts_to_margin_instead(self) -> None:
        theirs = PostingMap(discount="4400")
        assert theirs.account_for(LineKind.DISCOUNT) == "4400"
        assert theirs.account_for(LineKind.GROSS) == "1210"

    def test_every_account_the_map_names_is_postable(self) -> None:
        DEFAULT_MAP.check_against(standard_chart())

    def test_a_map_naming_a_heading_is_caught(self) -> None:
        with pytest.raises(Exception, match="post to one of"):
            PostingMap(storage="4000").check_against(standard_chart())

    def test_the_accounts_come_back_sorted_and_include_the_payable(self) -> None:
        codes = DEFAULT_MAP.accounts()
        assert list(codes) == sorted(codes)
        assert DEFAULT_MAP.payable in codes


class TestBuildingTheEntry:
    def test_the_entry_balances(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-202505-0001")
        entry.check_balances()

    def test_the_gross_is_the_only_debit(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-202505-0001")
        debits = [leg for leg in entry.legs if leg.side is Side.DEBIT]
        assert len(debits) == 1
        assert debits[0].account_code == "1210"
        assert debits[0].dollars == Decimal("4350.00")

    def test_the_producer_is_credited_the_net(self) -> None:
        statement = a_statement()
        entry = entry_for_settlement(statement, "J-202505-0001")
        payable = entry.touching("2100")
        assert len(payable) == 1
        assert payable[0].side is Side.CREDIT
        assert payable[0].dollars == statement.net
        assert payable[0].dollars == Decimal("3663.60")

    def test_each_deduction_reaches_its_own_account(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-202505-0001")
        landed = {leg.account_code: leg.dollars for leg in entry.legs}
        assert landed["4200"] == Decimal("118.75")
        assert landed["4100"] == Decimal("42.30")
        assert landed["2200"] == Decimal("4.35")
        assert landed["1300"] == Decimal("500.00")

    def test_the_discount_is_credited_back_against_the_grain(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-202505-0001")
        on_grain = entry.touching("1210")
        assert {leg.side for leg in on_grain} == {Side.DEBIT, Side.CREDIT}
        credit_leg = next(leg for leg in on_grain if leg.side is Side.CREDIT)
        assert credit_leg.dollars == Decimal("21.00")

    def test_it_points_back_at_the_statement(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-202505-0001")
        assert entry.source == SOURCE
        assert entry.source_reference == "S-2025-0004"
        assert "MERRICK" in entry.memo

    def test_it_posts_on_the_settlement_day_unless_told_otherwise(self) -> None:
        assert entry_for_settlement(a_statement(), "J-1").posted_on == MAY
        moved = entry_for_settlement(a_statement(), "J-1", date(2025, 6, 2))
        assert moved.posted_on == date(2025, 6, 2)

    def test_a_line_kind_with_nothing_on_it_gets_no_leg(self) -> None:
        entry = entry_for_settlement(a_statement(), "J-1")
        assert entry.touching("1400") == ()

    def test_a_statement_the_producer_owes_on_debits_the_payable(self) -> None:
        builder = SettlementBuilder("S-2025-0009", MAY, "MERRICK", "HOME")
        builder.add_gross("GRAIN", "corn", Decimal("100"), Decimal("4.0000"))
        builder.add_charge(
            LineKind.ADVANCE, "ADV-2", "advance recovered", Decimal("600.00")
        )
        statement = builder.build()
        assert statement.net == Decimal("-200.00")
        entry = entry_for_settlement(statement, "J-1")
        payable = entry.touching("2100")[0]
        assert payable.side is Side.DEBIT
        assert payable.dollars == Decimal("200.00")
        entry.check_balances()

    def test_a_statement_that_nets_to_nothing_still_balances(self) -> None:
        builder = SettlementBuilder("S-2025-0010", MAY, "MERRICK", "HOME")
        builder.add_gross("GRAIN", "corn", Decimal("100"), Decimal("4.0000"))
        builder.add_charge(
            LineKind.ADVANCE, "ADV-3", "advance recovered", Decimal("400.00")
        )
        statement = builder.build()
        assert statement.net == Decimal("0.00")
        entry = entry_for_settlement(statement, "J-1")
        assert entry.touching("2100") == ()
        entry.check_balances()

    def test_an_empty_statement_has_nothing_to_post(self) -> None:
        empty = SettlementBuilder("S-2025-0011", MAY, "MERRICK", "HOME").build()
        with pytest.raises(PostingError, match="nothing to post"):
            entry_for_settlement(empty, "J-1")


class TestPosting:
    def test_a_statement_reaches_the_journal(self, uow) -> None:
        entry = post_settlement(uow, a_statement(), "J-202505-0001")
        uow.commit()
        assert uow.ledger.entry(entry.number).balances
        assert uow.ledger.is_posted(SOURCE, "S-2025-0004")

    def test_the_same_statement_cannot_post_twice(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        with pytest.raises(AlreadyPosted, match="reverse it rather"):
            post_settlement(uow, a_statement(), "J-202505-0002")

    def test_a_closed_month_will_not_take_it(self, uow) -> None:
        with pytest.raises(PeriodClosed, match="2025-04 was closed"):
            post_settlement(uow, a_statement(), "J-1", posted_on=date(2025, 4, 20))

    def test_a_day_no_period_covers_is_refused(self, uow) -> None:
        with pytest.raises(Exception, match="no accounting period covers"):
            require_open_period(uow, date(2025, 9, 1))

    def test_an_open_month_takes_it(self, uow) -> None:
        require_open_period(uow, date(2025, 5, 31))

    def test_the_books_balance_after_a_posting(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        uow.commit()
        chart = uow.ledger.chart()
        entries = uow.ledger.entries_between(date(2025, 5, 1), date(2025, 5, 31))
        balance = trial_balance(chart, entries, through=date(2025, 5, 31))
        assert balance.balances
        assert balance.balance_of("2100") == Decimal("3663.60")
        assert balance.balance_of("1210") == Decimal("4329.00")
        assert balance.balance_of("4200") == Decimal("118.75")

    def test_the_grain_is_carried_at_what_the_house_paid_for_it(self, uow) -> None:
        """4,350 gross less the 21.00 discount, which the house did not pay."""
        post_settlement(uow, a_statement(), "J-202505-0001")
        uow.commit()
        balance = trial_balance(
            uow.ledger.chart(),
            uow.ledger.entries_between(date(2025, 5, 1), date(2025, 5, 31)),
            through=date(2025, 5, 31),
        )
        assert balance.balance_of("1210") == Decimal("4350.00") - Decimal("21.00")


class TestPlanning:
    def test_a_plan_writes_nothing(self, uow) -> None:
        plan = plan_settlement(uow, a_statement(), "J-202505-0001")
        assert plan.balances
        assert plan.settlement_number == "S-2025-0004"
        assert plan.net == Decimal("3663.60")
        assert not uow.ledger.is_posted(SOURCE, "S-2025-0004")

    def test_a_plan_warns_about_one_already_in_the_journal(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        plan = plan_settlement(uow, a_statement(), "J-202505-0002")
        assert plan.warnings == ("S-2025-0004 is already in the journal",)

    def test_a_clean_plan_carries_no_warnings(self, uow) -> None:
        assert plan_settlement(uow, a_statement(), "J-1").warnings == ()

    def test_unposted_names_the_ones_that_never_landed(self, uow) -> None:
        first = a_statement("S-2025-0100")
        second = a_statement("S-2025-0101")
        post_settlement(uow, first, "J-202505-0001")
        uow.commit()
        missing = unposted(uow, {"S-2025-0100": first, "S-2025-0101": second})
        assert missing == ("S-2025-0101",)


class TestReversing:
    def test_a_reversal_undoes_the_balances(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        reverse_settlement(uow, "S-2025-0004", "J-202505-0002", date(2025, 5, 20))
        uow.commit()
        balance = trial_balance(
            uow.ledger.chart(),
            uow.ledger.entries_between(date(2025, 5, 1), date(2025, 5, 31)),
            through=date(2025, 5, 31),
        )
        assert balance.balance_of("2100") == Decimal("0.00")
        assert balance.balance_of("1210") == Decimal("0.00")
        assert balance.balances

    def test_both_entries_stay_on_file(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        reverse_settlement(uow, "S-2025-0004", "J-202505-0002", date(2025, 5, 20))
        uow.commit()
        assert len(uow.ledger.for_source(SOURCE, "S-2025-0004")) == 2

    def test_the_reversal_records_which_entry_it_undid(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        reverse_settlement(uow, "S-2025-0004", "J-202505-0002", date(2025, 5, 20))
        uow.commit()
        found = uow.ledger.reversal_of("J-202505-0001")
        assert found is not None
        assert found.number == "J-202505-0002"

    def test_reversing_twice_is_refused(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        reverse_settlement(uow, "S-2025-0004", "J-202505-0002", date(2025, 5, 20))
        with pytest.raises(ConflictError, match="already been reversed"):
            reverse_settlement(uow, "S-2025-0004", "J-202505-0003", date(2025, 5, 21))

    def test_something_never_posted_cannot_be_reversed(self, uow) -> None:
        with pytest.raises(PostingError, match="was never posted"):
            reverse_settlement(uow, "S-2025-9999", "J-1", date(2025, 5, 20))

    def test_a_reversal_into_a_closed_month_is_refused(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        with pytest.raises(PeriodClosed):
            reverse_settlement(uow, "S-2025-0004", "J-2", date(2025, 4, 20))

    def test_a_reversed_statement_can_be_posted_again(self, uow) -> None:
        post_settlement(uow, a_statement(), "J-202505-0001")
        reverse_settlement(uow, "S-2025-0004", "J-202505-0002", date(2025, 5, 20))
        uow.commit()
        corrected = a_statement()
        entry = entry_for_settlement(corrected, "J-202505-0003", date(2025, 5, 21))
        uow.ledger.post(entry)
        uow.commit()
        assert len(uow.ledger.for_source(SOURCE, "S-2025-0004")) == 3
