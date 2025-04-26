"""The chart, journal and period tables, and the repository over them.

The tests that matter most here are the ones that check something cannot be
done: the journal has no update path, an entry number cannot be reused, and a
leg cannot carry a negative amount even if the code that built it forgot to
look. The last one is checked against the database rather than the domain
object, because a back fill that writes rows directly does not go through the
domain object at all.
"""

from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy.exc import IntegrityError

from bushelworks.domain.ledger import (
    Account,
    AccountType,
    JournalEntry,
    credit,
    debit,
    standard_chart,
    trial_balance,
)
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import (
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from bushelworks.persistence.models import (
    AccountRow,
    JournalEntryRow,
    JournalLegRow,
    PeriodRow,
)
from bushelworks.persistence.repositories import (
    JournalEntryNotFound,
    LedgerRepository,
    PeriodNotFound,
)
from bushelworks.persistence.unitofwork import UnitOfWork


@pytest.fixture
def engine():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield engine
    drop_all(engine)


@pytest.fixture
def session(engine):
    factory = session_factory(engine)
    with factory() as s:
        yield s


@pytest.fixture
def repo(session) -> LedgerRepository:
    return LedgerRepository(session)


@pytest.fixture
def filed(repo, session) -> LedgerRepository:
    repo.file_chart(standard_chart())
    session.commit()
    return repo


def an_entry(number: str, on: date = date(2025, 5, 5)) -> JournalEntry:
    return JournalEntry(
        number=number,
        posted_on=on,
        memo="corn settlement",
        legs=(
            debit("1210", Decimal("4350.00")),
            credit("4100", Decimal("42.30")),
            credit("2100", Decimal("4307.70")),
        ),
        source="settlement",
        source_reference="S-2025-0004",
    )


class TestChartTable:
    def test_the_filed_chart_reads_back_as_the_same_tree(self, filed) -> None:
        chart = filed.chart()
        assert len(chart) == len(standard_chart())
        assert [a.code for a in chart.children_of("1200")] == ["1210", "1220"]
        assert chart.is_heading("4000")

    def test_a_child_filed_before_its_parent_still_builds(self, repo, session) -> None:
        session.add(
            AccountRow(code="1210", name="Owned", type="asset", parent_code="1200")
        )
        session.add(AccountRow(code="1200", name="Grain", type="asset"))
        session.flush()
        chart = repo.chart()
        assert chart.is_heading("1200")

    def test_an_account_whose_parent_is_missing_is_reported(self, repo, session) -> None:
        session.add(
            AccountRow(code="1210", name="Owned", type="asset", parent_code="9999")
        )
        session.flush()
        with pytest.raises(ConflictError, match="1210"):
            repo.chart()

    def test_filing_a_chart_twice_is_refused(self, filed) -> None:
        with pytest.raises(ConflictError, match="already has 25 accounts"):
            filed.file_chart(standard_chart())

    def test_an_account_can_be_added_to_a_filed_chart(self, filed, session) -> None:
        filed.add_account(
            Account("4600", "Trucking recovered", AccountType.REVENUE, parent="4000")
        )
        session.commit()
        assert "4600" in filed.chart()

    def test_the_same_code_cannot_be_added_twice(self, filed) -> None:
        with pytest.raises(ConflictError, match="already on file"):
            filed.add_account(Account("1100", "Cash", AccountType.ASSET))

    def test_an_inactive_account_is_left_out_unless_asked_for(
        self, filed, session
    ) -> None:
        row = session.query(AccountRow).filter_by(code="4500").one()
        row.active = False
        session.commit()
        assert "4500" not in filed.chart()
        assert "4500" in filed.chart(include_inactive=True)

    def test_the_code_is_unique_in_the_database(self, filed, session) -> None:
        session.add(AccountRow(code="1100", name="Cash again", type="asset"))
        with pytest.raises(IntegrityError):
            session.flush()

    def test_an_unknown_type_is_refused_by_the_database(self, session) -> None:
        session.add(AccountRow(code="8000", name="Odd", type="wishful"))
        with pytest.raises(IntegrityError):
            session.flush()


class TestPosting:
    def test_an_entry_goes_in_and_comes_back_whole(self, filed, session) -> None:
        filed.post(an_entry("J-2025-0001"))
        session.commit()
        back = filed.entry("J-2025-0001")
        assert back.balances
        assert back.debits == Decimal("4350.00")
        assert [leg.account_code for leg in back.legs] == ["1210", "4100", "2100"]
        assert back.source_reference == "S-2025-0004"

    def test_legs_come_back_in_the_order_they_were_written(self, filed, session) -> None:
        filed.post(an_entry("J-2025-0002"))
        session.commit()
        row = filed.row("J-2025-0002")
        assert [leg.sequence for leg in row.legs] == [1, 2, 3]
        assert [leg.side for leg in row.legs] == ["debit", "credit", "credit"]

    def test_an_unbalanced_entry_never_reaches_the_table(self, filed, session) -> None:
        bad = JournalEntry(
            number="J-2025-0003",
            posted_on=date(2025, 5, 5),
            memo="wrong",
            legs=(debit("1210", Decimal("10.00")), credit("2100", Decimal("9.00"))),
        )
        with pytest.raises(Exception, match="out by 1.00"):
            filed.post(bad)
        session.rollback()
        assert session.query(JournalEntryRow).count() == 0

    def test_a_number_cannot_be_posted_twice(self, filed, session) -> None:
        filed.post(an_entry("J-2025-0004"))
        session.commit()
        with pytest.raises(ConflictError, match="already posted"):
            filed.post(an_entry("J-2025-0004"))

    def test_a_missing_entry_is_a_lookup_failure(self, filed) -> None:
        with pytest.raises(JournalEntryNotFound, match="no journal entry J-9"):
            filed.row("J-9")

    def test_a_negative_leg_is_refused_by_the_database(self, filed, session) -> None:
        row = JournalEntryRow(
            number="J-2025-0005", posted_on=date(2025, 5, 5), memo="hand written"
        )
        row.legs.append(
            JournalLegRow(
                sequence=1,
                account_code="1210",
                side="debit",
                dollars=Decimal("-1.00"),
            )
        )
        session.add(row)
        with pytest.raises(IntegrityError):
            session.flush()

    def test_an_unknown_side_is_refused_by_the_database(self, filed, session) -> None:
        row = JournalEntryRow(
            number="J-2025-0006", posted_on=date(2025, 5, 5), memo="hand written"
        )
        row.legs.append(
            JournalLegRow(
                sequence=1,
                account_code="1210",
                side="sideways",
                dollars=Decimal("1.00"),
            )
        )
        session.add(row)
        with pytest.raises(IntegrityError):
            session.flush()

    def test_deleting_an_entry_takes_its_legs_with_it(self, filed, session) -> None:
        filed.post(an_entry("J-2025-0007"))
        session.commit()
        session.delete(filed.row("J-2025-0007"))
        session.commit()
        assert session.query(JournalLegRow).count() == 0

    def test_the_repository_offers_no_way_to_change_an_entry(self) -> None:
        for forbidden in ("update", "edit", "delete", "void", "amend"):
            assert not hasattr(LedgerRepository, forbidden)


class TestReading:
    @pytest.fixture
    def journal(self, filed, session):
        filed.post(an_entry("J-2025-0010", on=date(2025, 5, 2)))
        filed.post(
            JournalEntry(
                number="J-2025-0011",
                posted_on=date(2025, 6, 3),
                memo="cheque run",
                legs=(
                    debit("2100", Decimal("4307.70")),
                    credit("1100", Decimal("4307.70")),
                ),
                source="payment",
                source_reference="C-2025-0100",
            )
        )
        session.commit()
        return filed

    def test_a_month_reads_back_without_the_next_one(self, journal) -> None:
        may = journal.entries_between(date(2025, 5, 1), date(2025, 5, 31))
        assert [e.number for e in may] == ["J-2025-0010"]

    def test_a_span_reads_in_date_order(self, journal) -> None:
        both = journal.entries_between(date(2025, 5, 1), date(2025, 6, 30))
        assert [e.number for e in both] == ["J-2025-0010", "J-2025-0011"]

    def test_entries_can_be_found_by_the_document_they_came_from(
        self, journal
    ) -> None:
        found = journal.for_source("settlement", "S-2025-0004")
        assert [row.number for row in found] == ["J-2025-0010"]
        assert journal.is_posted("settlement", "S-2025-0004")
        assert not journal.is_posted("settlement", "S-2025-9999")

    def test_the_legs_on_one_account_come_back_in_order(self, journal) -> None:
        legs = journal.legs_on("2100", date(2025, 5, 1), date(2025, 6, 30))
        assert [leg.side for leg in legs] == ["credit", "debit"]

    def test_a_trial_balance_off_the_table_balances(self, journal) -> None:
        chart = journal.chart()
        entries = journal.entries_between(date(2025, 5, 1), date(2025, 6, 30))
        balance = trial_balance(chart, entries, through=date(2025, 6, 30))
        assert balance.balances
        assert balance.balance_of("2100") == Decimal("0.00")
        assert balance.balance_of("4100") == Decimal("42.30")


class TestReversal:
    def test_a_reversal_records_what_it_reversed(self, filed, session) -> None:
        original = an_entry("J-2025-0020")
        filed.post(original)
        filed.post(
            original.reverse("J-2025-0021", date(2025, 5, 9)),
            reverses="J-2025-0020",
        )
        session.commit()
        found = filed.reversal_of("J-2025-0020")
        assert found is not None
        assert found.number == "J-2025-0021"

    def test_both_halves_stay_on_file(self, filed, session) -> None:
        original = an_entry("J-2025-0022")
        filed.post(original)
        filed.post(original.reverse("J-2025-0023", date(2025, 5, 9)))
        session.commit()
        assert session.query(JournalEntryRow).count() == 2

    def test_nothing_reverses_an_entry_that_stands(self, filed, session) -> None:
        filed.post(an_entry("J-2025-0024"))
        session.commit()
        assert filed.reversal_of("J-2025-0024") is None


class TestNumbering:
    def test_the_first_number_of_a_month_is_one(self, filed) -> None:
        assert filed.next_number("J", date(2025, 5, 5)) == "J-202505-0001"

    def test_the_next_number_follows_the_highest_on_file(self, filed, session) -> None:
        filed.post(an_entry("J-202505-0001"))
        filed.post(an_entry("J-202505-0007").reverse("J-202505-0007", date(2025, 5, 6)))
        session.commit()
        assert filed.next_number("J", date(2025, 5, 20)) == "J-202505-0008"

    def test_each_month_numbers_from_one(self, filed, session) -> None:
        filed.post(an_entry("J-202505-0001"))
        session.commit()
        assert filed.next_number("J", date(2025, 6, 1)) == "J-202506-0001"


class TestPeriods:
    @pytest.fixture
    def periods(self, filed, session):
        filed.add_periods(
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
        session.commit()
        return filed

    def test_a_period_is_found_by_the_day_it_covers(self, periods) -> None:
        assert periods.period_covering(date(2025, 5, 12)).code == "2025-05"

    def test_a_day_nothing_covers_is_a_lookup_failure(self, periods) -> None:
        with pytest.raises(PeriodNotFound, match="no accounting period covers"):
            periods.period_covering(date(2025, 7, 1))

    def test_only_the_open_periods_come_back(self, periods) -> None:
        assert [p.code for p in periods.open_periods()] == ["2025-05"]

    def test_a_period_code_cannot_be_filed_twice(self, periods) -> None:
        with pytest.raises(ConflictError, match="2025-05 is already on file"):
            periods.add_periods(
                [
                    PeriodRow(
                        code="2025-05",
                        starts_on=date(2025, 5, 1),
                        ends_on=date(2025, 5, 31),
                    )
                ]
            )

    def test_a_period_that_runs_backwards_is_refused(self, filed, session) -> None:
        session.add(
            PeriodRow(
                code="2025-06",
                starts_on=date(2025, 6, 30),
                ends_on=date(2025, 6, 1),
            )
        )
        with pytest.raises(IntegrityError):
            session.flush()


class TestUnitOfWork:
    def test_the_ledger_is_reachable_inside_the_block(self, engine) -> None:
        with UnitOfWork(session_factory(engine)) as uow:
            uow.ledger.file_chart(standard_chart())
            uow.ledger.post(an_entry("J-2025-0030"))
            uow.commit()
        with UnitOfWork(session_factory(engine)) as uow:
            assert uow.ledger.entry("J-2025-0030").balances

    def test_leaving_the_block_without_committing_writes_nothing(self, engine) -> None:
        with UnitOfWork(session_factory(engine)) as uow:
            uow.ledger.file_chart(standard_chart())
            uow.ledger.post(an_entry("J-2025-0031"))
        with UnitOfWork(session_factory(engine)) as uow:
            with pytest.raises(JournalEntryNotFound):
                uow.ledger.row("J-2025-0031")

    def test_reaching_for_the_ledger_outside_the_block_says_so(self, engine) -> None:
        uow = UnitOfWork(session_factory(engine))
        with pytest.raises(ConflictError, match="ledger repository"):
            uow.ledger
