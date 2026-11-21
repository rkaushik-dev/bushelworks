package io.github.rkaushikdev.bushelworks.persistence.repositories;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.credit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.debit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.LedgerError;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.AccountRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalLegRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The chart, journal and period tables, and the repository over them.
 *
 * <p>The tests that matter most here are the ones that check something cannot be done: the journal
 * has no update path, an entry number cannot be reused, and a leg cannot carry a negative amount
 * even if the code that built it forgot to look. The last one is checked against the database rather
 * than the domain object, because a back fill that writes rows directly does not go through the
 * domain object at all.
 */
class LedgerRepositoryTest {

    Database database;
    UnitOfWork work;
    LedgerRepository repo;

    @BeforeEach
    void openASession() {
        database = TestDatabase.fresh();
        work = UnitOfWork.open(database);
        repo = new LedgerRepository(work.session());
    }

    @AfterEach
    void closeTheSession() {
        work.close();
    }

    LedgerRepository filed() {
        repo.fileChart(standardChart());
        work.commit();
        return repo;
    }

    static JournalEntry anEntry(String number) {
        return anEntry(number, LocalDate.of(2025, 5, 5));
    }

    static JournalEntry anEntry(String number, LocalDate on) {
        return new JournalEntry(
                number,
                on,
                "corn settlement",
                List.of(debit("1210", d("4350.00")), credit("4100", d("42.30")), credit("2100", d("4307.70"))),
                "settlement",
                "S-2025-0004");
    }

    static AccountRow anAccountRow(String code, String name, String type, String parentCode) {
        AccountRow row = new AccountRow();
        row.setCode(code);
        row.setName(name);
        row.setType(type);
        row.setParentCode(parentCode);
        return row;
    }

    static JournalEntryRow aHandWrittenEntry(String number, String side, String dollars) {
        JournalEntryRow row = new JournalEntryRow();
        row.setNumber(number);
        row.setPostedOn(LocalDate.of(2025, 5, 5));
        row.setMemo("hand written");
        JournalLegRow leg = new JournalLegRow();
        leg.setSequence(1);
        leg.setAccountCode("1210");
        leg.setSide(side);
        leg.setDollars(d(dollars));
        row.addLeg(leg);
        return row;
    }

    static PeriodRow aPeriod(String code, LocalDate startsOn, LocalDate endsOn) {
        PeriodRow period = new PeriodRow();
        period.setCode(code);
        period.setStartsOn(startsOn);
        period.setEndsOn(endsOn);
        return period;
    }

    @Nested
    class ChartTable {

        @Test
        void theFiledChartReadsBackAsTheSameTree() {
            ChartOfAccounts chart = filed().chart();
            assertThat(chart.size()).isEqualTo(standardChart().size());
            assertThat(chart.childrenOf("1200")).extracting(Account::code).containsExactly("1210", "1220");
            assertThat(chart.isHeading("4000")).isTrue();
        }

        @Test
        void aChildFiledBeforeItsParentStillBuilds() {
            work.session().persist(anAccountRow("1210", "Owned", "asset", "1200"));
            work.session().persist(anAccountRow("1200", "Grain", "asset", null));
            work.flush();
            assertThat(repo.chart().isHeading("1200")).isTrue();
        }

        @Test
        void anAccountWhoseParentIsMissingIsReported() {
            work.session().persist(anAccountRow("1210", "Owned", "asset", "9999"));
            work.flush();
            assertThatThrownBy(() -> repo.chart())
                    .isInstanceOf(ConflictError.class)
                    .hasMessage("these accounts name a parent that is not on file: 1210");
        }

        @Test
        void filingAChartTwiceIsRefused() {
            LedgerRepository filed = filed();
            assertThatThrownBy(() -> filed.fileChart(standardChart()))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("already has 25 accounts");
        }

        @Test
        void anAccountCanBeAddedToAFiledChart() {
            LedgerRepository filed = filed();
            filed.addAccount(Account.of("4600", "Trucking recovered", AccountType.REVENUE)
                    .withParent("4000"));
            work.commit();
            assertThat(filed.chart().contains("4600")).isTrue();
        }

        @Test
        void theSameCodeCannotBeAddedTwice() {
            LedgerRepository filed = filed();
            assertThatThrownBy(() -> filed.addAccount(Account.of("1100", "Cash", AccountType.ASSET)))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("already on file");
        }

        @Test
        void anInactiveAccountIsLeftOutUnlessAskedFor() {
            LedgerRepository filed = filed();
            AccountRow row = work.session()
                    .createQuery("select a from AccountRow a where a.code = '4500'", AccountRow.class)
                    .getSingleResult();
            row.setActive(false);
            work.commit();
            assertThat(filed.chart().contains("4500")).isFalse();
            assertThat(filed.chart(true).contains("4500")).isTrue();
        }

        @Test
        void theCodeIsUniqueInTheDatabase() {
            filed();
            assertIntegrityError(() -> {
                work.session().persist(anAccountRow("1100", "Cash again", "asset", null));
                work.flush();
            });
        }

        @Test
        void anUnknownTypeIsRefusedByTheDatabase() {
            assertIntegrityError(() -> {
                work.session().persist(anAccountRow("8000", "Odd", "wishful", null));
                work.flush();
            });
        }
    }

    @Nested
    class Posting {

        @Test
        void anEntryGoesInAndComesBackWhole() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-2025-0001"));
            work.commit();
            JournalEntry back = filed.entry("J-2025-0001");
            assertThat(back.balances()).isTrue();
            assertThat(back.debits()).isEqualByComparingTo("4350.00");
            assertThat(back.legs()).extracting(Leg::accountCode).containsExactly("1210", "4100", "2100");
            assertThat(back.sourceReference()).isEqualTo("S-2025-0004");
        }

        @Test
        void legsComeBackInTheOrderTheyWereWritten() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-2025-0002"));
            work.commit();
            JournalEntryRow row = filed.row("J-2025-0002");
            assertThat(row.getLegs()).extracting(JournalLegRow::getSequence).containsExactly(1, 2, 3);
            assertThat(row.getLegs()).extracting(JournalLegRow::getSide).containsExactly("debit", "credit", "credit");
        }

        @Test
        void anUnbalancedEntryNeverReachesTheTable() {
            LedgerRepository filed = filed();
            JournalEntry bad = new JournalEntry(
                    "J-2025-0003",
                    LocalDate.of(2025, 5, 5),
                    "wrong",
                    List.of(debit("1210", d("10.00")), credit("2100", d("9.00"))));
            assertThatThrownBy(() -> filed.post(bad))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("out by 1.00");
            work.rollback();
            assertThat(TestDatabase.count(work.session(), JournalEntryRow.class))
                    .isZero();
        }

        @Test
        void aNumberCannotBePostedTwice() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-2025-0004"));
            work.commit();
            assertThatThrownBy(() -> filed.post(anEntry("J-2025-0004")))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("already posted");
        }

        @Test
        void aMissingEntryIsALookupFailure() {
            LedgerRepository filed = filed();
            assertThatThrownBy(() -> filed.row("J-9"))
                    .isInstanceOf(JournalEntryNotFound.class)
                    .hasMessageContaining("no journal entry J-9");
        }

        @Test
        void aNegativeLegIsRefusedByTheDatabase() {
            filed();
            JournalEntryRow row = aHandWrittenEntry("J-2025-0005", "debit", "-1.00");
            assertIntegrityError(() -> {
                work.session().persist(row);
                work.flush();
            });
        }

        @Test
        void anUnknownSideIsRefusedByTheDatabase() {
            filed();
            // Short enough to fit the column, so it is the side check that refuses it.
            JournalEntryRow row = aHandWrittenEntry("J-2025-0006", "across", "1.00");
            assertIntegrityError(() -> {
                work.session().persist(row);
                work.flush();
            });
        }

        @Test
        void deletingAnEntryTakesItsLegsWithIt() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-2025-0007"));
            work.commit();
            work.session().remove(filed.row("J-2025-0007"));
            work.commit();
            assertThat(TestDatabase.count(work.session(), JournalLegRow.class)).isZero();
        }

        @Test
        void theRepositoryOffersNoWayToChangeAnEntry() {
            Set<String> offered = Arrays.stream(LedgerRepository.class.getMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .collect(Collectors.toSet());
            assertThat(offered).contains("post", "row", "entry");
            assertThat(offered).doesNotContainAnyElementsOf(List.of("update", "edit", "delete", "void", "amend"));
            assertThat(offered).noneMatch(name -> name.startsWith("set") || name.startsWith("remove"));
        }
    }

    @Nested
    class Reading {

        LedgerRepository journal;

        @BeforeEach
        void postTwoMonths() {
            journal = filed();
            journal.post(anEntry("J-2025-0010", LocalDate.of(2025, 5, 2)));
            journal.post(new JournalEntry(
                    "J-2025-0011",
                    LocalDate.of(2025, 6, 3),
                    "cheque run",
                    List.of(debit("2100", d("4307.70")), credit("1100", d("4307.70"))),
                    "payment",
                    "C-2025-0100"));
            work.commit();
        }

        @Test
        void aMonthReadsBackWithoutTheNextOne() {
            assertThat(journal.entriesBetween(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31)))
                    .extracting(JournalEntry::number)
                    .containsExactly("J-2025-0010");
        }

        @Test
        void aSpanReadsInDateOrder() {
            assertThat(journal.entriesBetween(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 6, 30)))
                    .extracting(JournalEntry::number)
                    .containsExactly("J-2025-0010", "J-2025-0011");
        }

        @Test
        void entriesCanBeFoundByTheDocumentTheyCameFrom() {
            assertThat(journal.forSource("settlement", "S-2025-0004"))
                    .extracting(JournalEntryRow::getNumber)
                    .containsExactly("J-2025-0010");
            assertThat(journal.isPosted("settlement", "S-2025-0004")).isTrue();
            assertThat(journal.isPosted("settlement", "S-2025-9999")).isFalse();
        }

        @Test
        void theLegsOnOneAccountComeBackInOrder() {
            assertThat(journal.legsOn("2100", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 6, 30)))
                    .extracting(JournalLegRow::getSide)
                    .containsExactly("credit", "debit");
        }

        @Test
        void aTrialBalanceOffTheTableBalances() {
            ChartOfAccounts chart = journal.chart();
            List<JournalEntry> entries = journal.entriesBetween(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 6, 30));
            TrialBalance balance = trialBalance(chart, entries, LocalDate.of(2025, 6, 30));
            assertThat(balance.balances()).isTrue();
            assertThat(balance.balanceOf("2100")).isEqualByComparingTo("0.00");
            assertThat(balance.balanceOf("4100")).isEqualByComparingTo("42.30");
        }
    }

    @Nested
    class Reversal {

        @Test
        void aReversalRecordsWhatItReversed() {
            LedgerRepository filed = filed();
            JournalEntry original = anEntry("J-2025-0020");
            filed.post(original);
            filed.post(original.reverse("J-2025-0021", LocalDate.of(2025, 5, 9)), "J-2025-0020");
            work.commit();
            assertThat(filed.reversalOf("J-2025-0020"))
                    .map(JournalEntryRow::getNumber)
                    .contains("J-2025-0021");
        }

        @Test
        void bothHalvesStayOnFile() {
            LedgerRepository filed = filed();
            JournalEntry original = anEntry("J-2025-0022");
            filed.post(original);
            filed.post(original.reverse("J-2025-0023", LocalDate.of(2025, 5, 9)));
            work.commit();
            assertThat(TestDatabase.count(work.session(), JournalEntryRow.class))
                    .isEqualTo(2);
        }

        @Test
        void nothingReversesAnEntryThatStands() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-2025-0024"));
            work.commit();
            assertThat(filed.reversalOf("J-2025-0024")).isEmpty();
        }
    }

    @Nested
    class Numbering {

        @Test
        void theFirstNumberOfAMonthIsOne() {
            assertThat(filed().nextNumber("J", LocalDate.of(2025, 5, 5))).isEqualTo("J-202505-0001");
        }

        @Test
        void theNextNumberFollowsTheHighestOnFile() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-202505-0001"));
            filed.post(anEntry("J-202505-0007").reverse("J-202505-0007", LocalDate.of(2025, 5, 6)));
            work.commit();
            assertThat(filed.nextNumber("J", LocalDate.of(2025, 5, 20))).isEqualTo("J-202505-0008");
        }

        @Test
        void eachMonthNumbersFromOne() {
            LedgerRepository filed = filed();
            filed.post(anEntry("J-202505-0001"));
            work.commit();
            assertThat(filed.nextNumber("J", LocalDate.of(2025, 6, 1))).isEqualTo("J-202506-0001");
        }
    }

    @Nested
    class Periods {

        LedgerRepository periods;

        @BeforeEach
        void fileTwoMonths() {
            periods = filed();
            PeriodRow april = aPeriod("2025-04", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
            april.setClosed(true);
            april.setClosedOn(LocalDate.of(2025, 5, 8));
            april.setClosedBy("office");
            periods.addPeriods(List.of(april, aPeriod("2025-05", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31))));
            work.commit();
        }

        @Test
        void aPeriodIsFoundByTheDayItCovers() {
            assertThat(periods.periodCovering(LocalDate.of(2025, 5, 12)).getCode())
                    .isEqualTo("2025-05");
            assertThat(periods.period("2025-04").isClosed()).isTrue();
        }

        @Test
        void aDayNothingCoversIsALookupFailure() {
            assertThatThrownBy(() -> periods.periodCovering(LocalDate.of(2025, 7, 1)))
                    .isInstanceOf(PeriodNotFound.class)
                    .hasMessage("no accounting period covers 2025-07-01");
            assertThatThrownBy(() -> periods.period("2025-07"))
                    .isInstanceOf(PeriodNotFound.class)
                    .hasMessage("no accounting period 2025-07");
        }

        @Test
        void onlyTheOpenPeriodsComeBack() {
            assertThat(periods.openPeriods()).extracting(PeriodRow::getCode).containsExactly("2025-05");
        }

        @Test
        void aPeriodCodeCannotBeFiledTwice() {
            assertThatThrownBy(() -> periods.addPeriods(
                            List.of(aPeriod("2025-05", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31)))))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("2025-05 is already on file");
        }

        @Test
        void aPeriodThatRunsBackwardsIsRefused() {
            assertIntegrityError(() -> {
                work.session().persist(aPeriod("2025-06", LocalDate.of(2025, 6, 30), LocalDate.of(2025, 6, 1)));
                work.flush();
            });
        }
    }

    @Nested
    class UnitOfWorkBlock {

        @Test
        void theLedgerIsReachableInsideTheBlock() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                uow.ledger().post(anEntry("J-2025-0030"));
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.ledger().entry("J-2025-0030").balances()).isTrue();
            }
        }

        @Test
        void leavingTheBlockWithoutCommittingWritesNothing() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                uow.ledger().post(anEntry("J-2025-0031"));
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> uow.ledger().row("J-2025-0031")).isInstanceOf(JournalEntryNotFound.class);
            }
        }

        @Test
        void reachingForTheLedgerOutsideTheBlockSaysSo() {
            UnitOfWork uow = new UnitOfWork(database);
            assertThatThrownBy(uow::ledger).isInstanceOf(ConflictError.class).hasMessageContaining("ledger repository");
        }
    }
}
