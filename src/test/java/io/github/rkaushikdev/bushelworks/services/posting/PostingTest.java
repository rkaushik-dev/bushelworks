package io.github.rkaushikdev.bushelworks.services.posting;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementBuilder;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A statement, turned into an entry, checked against the books.
 *
 * <p>The producer's net is the gross less everything that reduces it, so an entry that debits the
 * gross, credits each deduction to wherever it belongs and credits the net to the payable balances by
 * construction. That is why there is no plug leg anywhere in the service, and why a test that finds
 * one out of balance has found a real disagreement between the statement and the ledger rather than
 * a rounding nuisance.
 */
class PostingTest {

    static final LocalDate MAY = LocalDate.of(2025, 5, 12);
    static final LocalDate MAY_END = LocalDate.of(2025, 5, 31);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    /** 4,350.00 of corn, less a discount, drying, storage, checkoff and an advance. */
    static Settlement aStatement() {
        return aStatement("S-2025-0004");
    }

    static Settlement aStatement(String number) {
        SettlementBuilder builder = new SettlementBuilder(number, MAY, "MERRICK", "HOME");
        builder.referenceTicket("A-10441");
        builder.addGross("GRAIN", "corn 1000 bu at 4.3500", d("1000"), d("4.3500"));
        builder.addCharge(LineKind.DISCOUNT, "MOISTURE", "moisture discount", d("21.00"));
        builder.addCharge(LineKind.DRYING, "DRYING", "drying charge", d("118.75"));
        builder.addCharge(LineKind.STORAGE, "STORAGE", "storage", d("42.30"));
        builder.addCharge(LineKind.ASSESSMENT, "KSCORN", "Kansas corn", d("4.35"));
        builder.addCharge(LineKind.ADVANCE, "ADV-1", "advance recovered", d("500.00"));
        return builder.build();
    }

    static PeriodRow aPeriod(String code, LocalDate startsOn, LocalDate endsOn) {
        PeriodRow period = new PeriodRow();
        period.setCode(code);
        period.setStartsOn(startsOn);
        period.setEndsOn(endsOn);
        return period;
    }

    /** A unit of work over the standard chart, April filed and closed, May open. */
    UnitOfWork books() {
        UnitOfWork unit = UnitOfWork.open(database);
        unit.ledger().fileChart(standardChart());
        PeriodRow april = aPeriod("2025-04", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        april.setClosed(true);
        april.setClosedOn(LocalDate.of(2025, 5, 8));
        april.setClosedBy("office");
        unit.ledger().addPeriods(List.of(april, aPeriod("2025-05", LocalDate.of(2025, 5, 1), MAY_END)));
        unit.commit();
        return unit;
    }

    static TrialBalance mayBalance(UnitOfWork uow) {
        return trialBalance(
                uow.ledger().chart(), uow.ledger().entriesBetween(LocalDate.of(2025, 5, 1), MAY_END), MAY_END);
    }

    @Nested
    class TheMap {

        @Test
        void aDeductionIsCreditedAndTheGrainIsDebited() {
            assertThat(PostingMap.DEFAULT.sideFor(LineKind.GROSS)).isSameAs(Side.DEBIT);
            assertThat(PostingMap.DEFAULT.sideFor(LineKind.STORAGE)).isSameAs(Side.CREDIT);
            assertThat(PostingMap.DEFAULT.sideFor(LineKind.ADVANCE)).isSameAs(Side.CREDIT);
        }

        @Test
        void anAdjustmentThatAddsToTheNetIsADebit() {
            assertThat(PostingMap.DEFAULT.sideFor(LineKind.ADJUSTMENT)).isSameAs(Side.DEBIT);
        }

        @Test
        void aDiscountLandsAgainstTheCostOfTheGrain() {
            assertThat(PostingMap.DEFAULT.accountFor(LineKind.DISCOUNT)).isEqualTo(PostingMap.DEFAULT.inventory());
        }

        @Test
        void aHouseCanFileDiscountsToMarginInstead() {
            PostingMap theirs = PostingMap.DEFAULT.withDiscount("4400");
            assertThat(theirs.accountFor(LineKind.DISCOUNT)).isEqualTo("4400");
            assertThat(theirs.accountFor(LineKind.GROSS)).isEqualTo("1210");
        }

        @Test
        void everyAccountTheMapNamesIsPostable() {
            assertThatCode(() -> PostingMap.DEFAULT.checkAgainst(standardChart()))
                    .doesNotThrowAnyException();
        }

        @Test
        void aMapNamingAHeadingIsCaught() {
            assertThatThrownBy(() -> PostingMap.DEFAULT.withStorage("4000").checkAgainst(standardChart()))
                    .hasMessageContaining("post to one of");
        }

        @Test
        void everyKindOfLineHasAnAccount() {
            for (LineKind kind : LineKind.values()) {
                assertThat(PostingMap.DEFAULT.accountFor(kind)).as(kind.value()).isNotBlank();
            }
            assertThat(PostingMap.DEFAULT.withDrying("4210").accountFor(LineKind.DRYING))
                    .isEqualTo("4210");
            assertThat(PostingMap.DEFAULT.withAdjustment("4410").accountFor(LineKind.ADJUSTMENT))
                    .isEqualTo("4410");
            assertThatThrownBy(() -> new PostingMap(Map.of(LineKind.GROSS, "1210"), "2100", Map.of()))
                    .isInstanceOf(PostingError.class)
                    .hasMessageContaining("no account for discount");
        }

        @Test
        void aCheckoffWithItsOwnPayableLeavesTheRestOnTheSharedOne() {
            PostingMap theirs = PostingMap.DEFAULT.withLevyAccount("KSCORN", "2210");
            assertThat(theirs.levyAccounts()).containsEntry("KSCORN", "2210");
            assertThat(theirs.accounts()).contains("2200", "2210");
            assertThat(PostingMap.DEFAULT.levyAccounts()).isEmpty();
        }

        @Test
        void theAccountsComeBackSortedAndIncludeThePayable() {
            List<String> codes = PostingMap.DEFAULT.accounts();
            assertThat(codes).isSorted();
            assertThat(codes).contains(PostingMap.DEFAULT.payable());
        }
    }

    @Nested
    class BuildingTheEntry {

        @Test
        void theEntryBalances() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-202505-0001");
            assertThatCode(entry::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void theGrossIsTheOnlyDebit() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-202505-0001");
            List<Leg> debits = entry.legs().stream()
                    .filter(leg -> leg.side() == Side.DEBIT)
                    .toList();
            assertThat(debits).hasSize(1);
            assertThat(debits.get(0).accountCode()).isEqualTo("1210");
            assertThat(debits.get(0).dollars()).isEqualByComparingTo("4350.00");
        }

        @Test
        void theProducerIsCreditedTheNet() {
            Settlement statement = aStatement();
            JournalEntry entry = Posting.entryForSettlement(statement, "J-202505-0001");
            List<Leg> payable = entry.touching("2100");
            assertThat(payable).hasSize(1);
            assertThat(payable.get(0).side()).isSameAs(Side.CREDIT);
            assertThat(payable.get(0).dollars()).isEqualByComparingTo(statement.net());
            assertThat(payable.get(0).dollars()).isEqualByComparingTo("3663.60");
        }

        @Test
        void eachDeductionReachesItsOwnAccount() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-202505-0001");
            Map<String, BigDecimal> landed =
                    entry.legs().stream().collect(Collectors.toMap(Leg::accountCode, Leg::dollars, (a, b) -> b));
            assertThat(landed.get("4200")).isEqualByComparingTo("118.75");
            assertThat(landed.get("4100")).isEqualByComparingTo("42.30");
            assertThat(landed.get("2200")).isEqualByComparingTo("4.35");
            assertThat(landed.get("1300")).isEqualByComparingTo("500.00");
        }

        @Test
        void eachCommissionsCheckoffReachesItsOwnPayable() {
            SettlementBuilder builder = new SettlementBuilder("S-2025-0009", MAY, "MERRICK", "HOME");
            builder.addGross("GRAIN", "corn 1000 bu at 4.3500", d("1000"), d("4.3500"));
            builder.addCharge(LineKind.ASSESSMENT, "KSCORN", "Kansas corn", d("10.00"));
            builder.addCharge(LineKind.ASSESSMENT, "NECORN", "Nebraska corn", d("5.00"));
            builder.addCharge(LineKind.ASSESSMENT, "NCB", "National corn", d("1.25"));
            PostingMap theirs =
                    PostingMap.DEFAULT.withLevyAccount("KSCORN", "2210").withLevyAccount("NECORN", "2220");
            JournalEntry entry = Posting.entryForSettlement(builder.build(), "J-202505-0009", null, theirs, "");
            assertThatCode(entry::checkBalances).doesNotThrowAnyException();
            assertThat(entry.touching("2210")).extracting(Leg::dollars).containsExactly(d("10.00"));
            assertThat(entry.touching("2220")).extracting(Leg::memo).containsExactly("assessment NECORN");
            assertThat(entry.touching("2200")).extracting(Leg::memo).containsExactly("assessment");
            assertThat(entry.touching("2200")).extracting(Leg::dollars).containsExactly(d("1.25"));
        }

        @Test
        void theDiscountIsCreditedBackAgainstTheGrain() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-202505-0001");
            List<Leg> onGrain = entry.touching("1210");
            assertThat(onGrain).extracting(Leg::side).containsExactlyInAnyOrder(Side.DEBIT, Side.CREDIT);
            Leg creditLeg = onGrain.stream()
                    .filter(leg -> leg.side() == Side.CREDIT)
                    .findFirst()
                    .orElseThrow();
            assertThat(creditLeg.dollars()).isEqualByComparingTo("21.00");
        }

        @Test
        void itPointsBackAtTheStatement() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-202505-0001");
            assertThat(entry.source()).isEqualTo(Posting.SOURCE);
            assertThat(entry.sourceReference()).isEqualTo("S-2025-0004");
            assertThat(entry.memo()).contains("MERRICK");
        }

        @Test
        void itPostsOnTheSettlementDayUnlessToldOtherwise() {
            assertThat(Posting.entryForSettlement(aStatement(), "J-1").postedOn())
                    .isEqualTo(MAY);
            JournalEntry moved = Posting.entryForSettlement(aStatement(), "J-1", LocalDate.of(2025, 6, 2));
            assertThat(moved.postedOn()).isEqualTo(LocalDate.of(2025, 6, 2));
        }

        @Test
        void aLineKindWithNothingOnItGetsNoLeg() {
            JournalEntry entry = Posting.entryForSettlement(aStatement(), "J-1");
            assertThat(entry.touching("1400")).isEmpty();
        }

        @Test
        void aStatementTheProducerOwesOnDebitsThePayable() {
            SettlementBuilder builder = new SettlementBuilder("S-2025-0009", MAY, "MERRICK", "HOME");
            builder.addGross("GRAIN", "corn", d("100"), d("4.0000"));
            builder.addCharge(LineKind.ADVANCE, "ADV-2", "advance recovered", d("600.00"));
            Settlement statement = builder.build();
            assertThat(statement.net()).isEqualByComparingTo("-200.00");
            JournalEntry entry = Posting.entryForSettlement(statement, "J-1");
            Leg payable = entry.touching("2100").get(0);
            assertThat(payable.side()).isSameAs(Side.DEBIT);
            assertThat(payable.dollars()).isEqualByComparingTo("200.00");
            assertThatCode(entry::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void aStatementThatNetsToNothingStillBalances() {
            SettlementBuilder builder = new SettlementBuilder("S-2025-0010", MAY, "MERRICK", "HOME");
            builder.addGross("GRAIN", "corn", d("100"), d("4.0000"));
            builder.addCharge(LineKind.ADVANCE, "ADV-3", "advance recovered", d("400.00"));
            Settlement statement = builder.build();
            assertThat(statement.net()).isEqualByComparingTo("0.00");
            JournalEntry entry = Posting.entryForSettlement(statement, "J-1");
            assertThat(entry.touching("2100")).isEmpty();
            assertThatCode(entry::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void anEmptyStatementHasNothingToPost() {
            Settlement empty = new SettlementBuilder("S-2025-0011", MAY, "MERRICK", "HOME").build();
            assertThatThrownBy(() -> Posting.entryForSettlement(empty, "J-1"))
                    .isInstanceOf(PostingError.class)
                    .hasMessageContaining("nothing to post");
        }
    }

    @Nested
    class PostingToTheJournal {

        @Test
        void aStatementReachesTheJournal() {
            try (UnitOfWork uow = books()) {
                JournalEntry entry = Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                uow.commit();
                assertThat(uow.ledger().entry(entry.number()).balances()).isTrue();
                assertThat(uow.ledger().isPosted(Posting.SOURCE, "S-2025-0004")).isTrue();
            }
        }

        @Test
        void theSameStatementCannotPostTwice() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                assertThatThrownBy(() -> Posting.postSettlement(uow, aStatement(), "J-202505-0002"))
                        .isInstanceOf(AlreadyPosted.class)
                        .hasMessageContaining("reverse it rather");
            }
        }

        @Test
        void aClosedMonthWillNotTakeIt() {
            try (UnitOfWork uow = books()) {
                assertThatThrownBy(() -> Posting.postSettlement(uow, aStatement(), "J-1", LocalDate.of(2025, 4, 20)))
                        .isInstanceOf(PeriodClosed.class)
                        .hasMessageContaining("2025-04 was closed");
            }
        }

        @Test
        void aDayNoPeriodCoversIsRefused() {
            try (UnitOfWork uow = books()) {
                assertThatThrownBy(() -> Posting.requireOpenPeriod(uow, LocalDate.of(2025, 9, 1)))
                        .hasMessageContaining("no accounting period covers");
            }
        }

        @Test
        void anOpenMonthTakesIt() {
            try (UnitOfWork uow = books()) {
                assertThatCode(() -> Posting.requireOpenPeriod(uow, MAY_END)).doesNotThrowAnyException();
            }
        }

        @Test
        void theBooksBalanceAfterAPosting() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                uow.commit();
                TrialBalance balance = mayBalance(uow);
                assertThat(balance.balances()).isTrue();
                assertThat(balance.balanceOf("2100")).isEqualByComparingTo("3663.60");
                assertThat(balance.balanceOf("1210")).isEqualByComparingTo("4329.00");
                assertThat(balance.balanceOf("4200")).isEqualByComparingTo("118.75");
            }
        }

        /** 4,350 gross less the 21.00 discount, which the house did not pay. */
        @Test
        void theGrainIsCarriedAtWhatTheHousePaidForIt() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                uow.commit();
                assertThat(mayBalance(uow).balanceOf("1210"))
                        .isEqualByComparingTo(d("4350.00").subtract(d("21.00")));
            }
        }
    }

    @Nested
    class Planning {

        @Test
        void aPlanWritesNothing() {
            try (UnitOfWork uow = books()) {
                PostingPlan plan = Posting.planSettlement(uow, aStatement(), "J-202505-0001");
                assertThat(plan.balances()).isTrue();
                assertThat(plan.settlementNumber()).isEqualTo("S-2025-0004");
                assertThat(plan.net()).isEqualByComparingTo("3663.60");
                assertThat(uow.ledger().isPosted(Posting.SOURCE, "S-2025-0004")).isFalse();
            }
        }

        @Test
        void aPlanWarnsAboutOneAlreadyInTheJournal() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                PostingPlan plan = Posting.planSettlement(uow, aStatement(), "J-202505-0002");
                assertThat(plan.warnings()).containsExactly("S-2025-0004 is already in the journal");
            }
        }

        @Test
        void aCleanPlanCarriesNoWarnings() {
            try (UnitOfWork uow = books()) {
                assertThat(Posting.planSettlement(uow, aStatement(), "J-1").warnings())
                        .isEmpty();
            }
        }

        @Test
        void unpostedNamesTheOnesThatNeverLanded() {
            try (UnitOfWork uow = books()) {
                Settlement first = aStatement("S-2025-0100");
                Settlement second = aStatement("S-2025-0101");
                Posting.postSettlement(uow, first, "J-202505-0001");
                uow.commit();
                List<String> missing = Posting.unposted(uow, Map.of("S-2025-0100", first, "S-2025-0101", second));
                assertThat(missing).containsExactly("S-2025-0101");
            }
        }
    }

    @Nested
    class Reversing {

        @Test
        void aReversalUndoesTheBalances() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                Posting.reverseSettlement(uow, "S-2025-0004", "J-202505-0002", LocalDate.of(2025, 5, 20));
                uow.commit();
                TrialBalance balance = mayBalance(uow);
                assertThat(balance.balanceOf("2100")).isEqualByComparingTo("0.00");
                assertThat(balance.balanceOf("1210")).isEqualByComparingTo("0.00");
                assertThat(balance.balances()).isTrue();
            }
        }

        @Test
        void bothEntriesStayOnFile() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                Posting.reverseSettlement(uow, "S-2025-0004", "J-202505-0002", LocalDate.of(2025, 5, 20));
                uow.commit();
                assertThat(uow.ledger().forSource(Posting.SOURCE, "S-2025-0004"))
                        .hasSize(2);
            }
        }

        @Test
        void theReversalRecordsWhichEntryItUndid() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                Posting.reverseSettlement(uow, "S-2025-0004", "J-202505-0002", LocalDate.of(2025, 5, 20));
                uow.commit();
                assertThat(uow.ledger().reversalOf("J-202505-0001"))
                        .get()
                        .extracting(JournalEntryRow::getNumber)
                        .isEqualTo("J-202505-0002");
            }
        }

        @Test
        void reversingTwiceIsRefused() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                Posting.reverseSettlement(uow, "S-2025-0004", "J-202505-0002", LocalDate.of(2025, 5, 20));
                assertThatThrownBy(() -> Posting.reverseSettlement(
                                uow, "S-2025-0004", "J-202505-0003", LocalDate.of(2025, 5, 21)))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("already been reversed");
            }
        }

        @Test
        void somethingNeverPostedCannotBeReversed() {
            try (UnitOfWork uow = books()) {
                assertThatThrownBy(
                                () -> Posting.reverseSettlement(uow, "S-2025-9999", "J-1", LocalDate.of(2025, 5, 20)))
                        .isInstanceOf(PostingError.class)
                        .hasMessageContaining("was never posted");
            }
        }

        @Test
        void aReversalIntoAClosedMonthIsRefused() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                assertThatThrownBy(
                                () -> Posting.reverseSettlement(uow, "S-2025-0004", "J-2", LocalDate.of(2025, 4, 20)))
                        .isInstanceOf(PeriodClosed.class);
            }
        }

        @Test
        void aReversedStatementCanBePostedAgain() {
            try (UnitOfWork uow = books()) {
                Posting.postSettlement(uow, aStatement(), "J-202505-0001");
                Posting.reverseSettlement(uow, "S-2025-0004", "J-202505-0002", LocalDate.of(2025, 5, 20));
                uow.commit();
                Settlement corrected = aStatement();
                JournalEntry entry = Posting.entryForSettlement(corrected, "J-202505-0003", LocalDate.of(2025, 5, 21));
                uow.ledger().post(entry);
                uow.commit();
                assertThat(uow.ledger().forSource(Posting.SOURCE, "S-2025-0004"))
                        .hasSize(3);
            }
        }
    }
}
