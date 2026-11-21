package io.github.rkaushikdev.bushelworks.domain.ledger;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.credit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.debit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.rollup;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The chart, the entry that has to balance, and the trial balance off both.
 *
 * <p>The numbers here are small enough to add in your head on purpose. A ledger test whose expected
 * value came out of the code it is testing proves the code has not changed, which is not the same as
 * proving it is right.
 */
class LedgerTest {

    static final LocalDate MAY = LocalDate.of(2025, 5, 12);
    static final LocalDate MONTH_END = LocalDate.of(2025, 5, 31);

    ChartOfAccounts chart;

    @BeforeEach
    void fileTheChart() {
        chart = standardChart();
    }

    static JournalEntry entry(String number, Leg... legs) {
        return entryOn(number, MAY, legs);
    }

    static JournalEntry entryOn(String number, LocalDate on, Leg... legs) {
        return new JournalEntry(number, on, "test", List.of(legs));
    }

    static List<String> codes(List<Account> accounts) {
        return accounts.stream().map(Account::code).toList();
    }

    @Nested
    class Sides {

        @Test
        void assetsAndExpensesIncreaseOnTheDebitSide() {
            assertThat(AccountType.ASSET.normalSide()).isSameAs(Side.DEBIT);
            assertThat(AccountType.EXPENSE.normalSide()).isSameAs(Side.DEBIT);
        }

        @Test
        void everythingElseIncreasesOnTheCreditSide() {
            assertThat(AccountType.LIABILITY.normalSide()).isSameAs(Side.CREDIT);
            assertThat(AccountType.EQUITY.normalSide()).isSameAs(Side.CREDIT);
            assertThat(AccountType.REVENUE.normalSide()).isSameAs(Side.CREDIT);
        }

        @Test
        void aSideKnowsItsOpposite() {
            assertThat(Side.DEBIT.opposite()).isSameAs(Side.CREDIT);
            assertThat(Side.CREDIT.opposite()).isSameAs(Side.DEBIT);
        }

        @Test
        void revenueAndExpenseAreNotOnTheBalanceSheet() {
            assertThat(AccountType.ASSET.onBalanceSheet()).isTrue();
            assertThat(AccountType.LIABILITY.onBalanceSheet()).isTrue();
            assertThat(AccountType.EQUITY.onBalanceSheet()).isTrue();
            assertThat(AccountType.REVENUE.onBalanceSheet()).isFalse();
            assertThat(AccountType.EXPENSE.onBalanceSheet()).isFalse();
        }
    }

    @Nested
    class Chart {

        @Test
        void theFiledChartHasTheAccountsASettlementNeeds() {
            for (String code : List.of("1210", "2100", "2200", "4100", "4200", "1300")) {
                assertThat(chart.contains(code)).as(code).isTrue();
            }
        }

        @Test
        void aParentHasToBeOnFileBeforeItsChild() {
            List<Account> orphan =
                    List.of(Account.of("1100", "Cash", AccountType.ASSET).withParent("1000"));
            assertThatThrownBy(() -> new ChartOfAccounts(orphan)).isInstanceOf(AccountNotFound.class);
        }

        @Test
        void aChildCannotChangeTypeFromItsParent() {
            List<Account> accounts = List.of(
                    Account.of("1000", "Assets", AccountType.ASSET),
                    Account.of("1100", "Storage", AccountType.REVENUE).withParent("1000"));
            assertThatThrownBy(() -> new ChartOfAccounts(accounts))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("which is a asset");
        }

        @Test
        void theSameCodeCannotBeFiledTwice() {
            assertThatThrownBy(() -> chart.add(Account.of("1100", "Cash again", AccountType.ASSET)))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("already in the chart");
        }

        @Test
        void anAccountCannotParentItself() {
            assertThatThrownBy(() ->
                            Account.of("1000", "Assets", AccountType.ASSET).withParent("1000"))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("its own parent");
        }

        @Test
        void anAccountNeedsAName() {
            assertThatThrownBy(() -> Account.of("1000", "   ", AccountType.ASSET))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("needs a name");
        }

        @Test
        void aCodeWithSpaceAroundItIsRefused() {
            assertThatThrownBy(() -> Account.of(" 1000 ", "Assets", AccountType.ASSET))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("bare code");
        }

        @Test
        void anAccountWithChildrenIsAHeading() {
            assertThat(chart.isHeading("1200")).isTrue();
            assertThat(chart.isHeading("1210")).isFalse();
        }

        @Test
        void aHeadingWillNotTakeAPostingAndSaysWhereToPutIt() {
            assertThatThrownBy(() -> chart.postable("1200"))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("post to one of 1210, 1220");
        }

        @Test
        void aLeafIsPostable() {
            assertThat(chart.postable("1210").name()).isEqualTo("Grain inventory, company owned");
        }

        @Test
        void anUnknownCodeIsALookupFailure() {
            assertThatThrownBy(() -> chart.get("9999"))
                    .isInstanceOf(AccountNotFound.class)
                    .hasMessageContaining("no account 9999");
        }

        @Test
        void childrenComeBackInCodeOrder() {
            assertThat(codes(chart.childrenOf("1200"))).containsExactly("1210", "1220");
        }

        @Test
        void lineageRunsFromTheTopDown() {
            assertThat(codes(chart.lineage("1210"))).containsExactly("1000", "1200", "1210");
        }

        @Test
        void ofTypePicksOneKindOut() {
            assertThat(codes(chart.ofType(AccountType.REVENUE)))
                    .containsExactly("4000", "4100", "4200", "4300", "4400", "4500");
        }

        @Test
        void iteratingTheChartGivesCodeOrder() {
            List<String> codes = StreamSupport.stream(chart.spliterator(), false)
                    .map(Account::code)
                    .toList();
            assertThat(codes).isSorted();
            assertThat(codes).hasSize(chart.size());
        }
    }

    @Nested
    class Legs {

        @Test
        void aLegCarriesAPositiveAmount() {
            assertThatThrownBy(() -> debit("1100", d("-5.00")))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("positive amount");
        }

        @Test
        void aZeroLegIsRefused() {
            assertThatThrownBy(() -> credit("1100", d("0.00")))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("positive amount");
        }

        @Test
        void aLegRoundsToCentsOnTheWayIn() {
            assertThat(debit("1100", d("10.005")).dollars()).isEqualTo(d("10.01"));
        }

        @Test
        void aDebitIncreasesAnAsset() {
            Account cash = chart.get("1100");
            assertThat(debit("1100", d("100.00")).effectOn(cash)).isEqualTo(d("100.00"));
        }

        @Test
        void aCreditReducesAnAsset() {
            Account cash = chart.get("1100");
            assertThat(credit("1100", d("40.00")).effectOn(cash)).isEqualTo(d("-40.00"));
        }

        @Test
        void aCreditIncreasesRevenue() {
            Account storage = chart.get("4100");
            assertThat(credit("4100", d("12.50")).effectOn(storage)).isEqualTo(d("12.50"));
        }

        @Test
        void aDebitReducesRevenue() {
            Account storage = chart.get("4100");
            assertThat(debit("4100", d("12.50")).effectOn(storage)).isEqualTo(d("-12.50"));
        }
    }

    @Nested
    class JournalEntries {

        @Test
        void anEntryNeedsTwoLegs() {
            assertThatThrownBy(() -> entry("J-1", debit("1210", d("100.00"))))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("at least two legs");
        }

        @Test
        void aBalancedEntryBalances() {
            JournalEntry posting = entry("J-2", debit("1210", d("4350.00")), credit("2100", d("4350.00")));
            assertThat(posting.balances()).isTrue();
            assertThat(posting.debits()).isEqualTo(d("4350.00"));
            assertThat(posting.credits()).isEqualTo(d("4350.00"));
            assertThatCode(posting::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void anUnbalancedEntrySaysWhatItIsOutBy() {
            JournalEntry posting = entry("J-3", debit("1210", d("4350.00")), credit("2100", d("4300.00")));
            assertThat(posting.balances()).isFalse();
            assertThatThrownBy(posting::checkBalances)
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("out by 50.00");
        }

        @Test
        void aManyLeggedEntryBalancesOnTheTotals() {
            JournalEntry posting = entry(
                    "J-4",
                    debit("1210", d("4350.00")),
                    credit("4100", d("42.30")),
                    credit("4200", d("118.75")),
                    credit("2200", d("4.35")),
                    credit("2100", d("4184.60")));
            assertThatCode(posting::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void checkingAgainstTheChartRefusesAHeading() {
            JournalEntry posting = entry("J-5", debit("1200", d("100.00")), credit("2100", d("100.00")));
            assertThatThrownBy(() -> posting.checkAgainst(chart))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("post to one of");
        }

        @Test
        void checkingAgainstTheChartRefusesAnUnknownAccount() {
            JournalEntry posting = entry("J-6", debit("9999", d("100.00")), credit("2100", d("100.00")));
            assertThatThrownBy(() -> posting.checkAgainst(chart)).isInstanceOf(AccountNotFound.class);
        }

        @Test
        void aGoodEntryPassesTheChartCheck() {
            JournalEntry posting = entry("J-7", debit("1210", d("100.00")), credit("2100", d("100.00")));
            assertThatCode(() -> posting.checkAgainst(chart)).doesNotThrowAnyException();
        }

        @Test
        void touchingFindsTheLegsOnOneAccount() {
            JournalEntry posting =
                    entry("J-8", debit("1210", d("100.00")), credit("2100", d("60.00")), credit("2100", d("40.00")));
            assertThat(posting.touching("2100")).hasSize(2);
            assertThat(posting.touching("4100")).isEmpty();
        }
    }

    @Nested
    class Reversal {

        @Test
        void aReversalFlipsEverySide() {
            JournalEntry first = entry("J-9", debit("1210", d("4350.00")), credit("2100", d("4350.00")));
            JournalEntry back = first.reverse("J-10", LocalDate.of(2025, 5, 16));
            assertThat(back.legs()).extracting(Leg::side).containsExactly(Side.CREDIT, Side.DEBIT);
            assertThat(back.debits()).isEqualTo(first.credits());
            assertThatCode(back::checkBalances).doesNotThrowAnyException();
        }

        @Test
        void aReversalKeepsTheSourceItCameFrom() {
            JournalEntry first = new JournalEntry(
                    "J-11",
                    MAY,
                    "settlement S-2025-0004",
                    List.of(debit("1210", d("10.00")), credit("2100", d("10.00"))),
                    "settlement",
                    "S-2025-0004");
            JournalEntry back = first.reverse("J-12", LocalDate.of(2025, 5, 16));
            assertThat(back.source()).isEqualTo("settlement");
            assertThat(back.sourceReference()).isEqualTo("S-2025-0004");
            assertThat(back.memo()).isEqualTo("reversal of J-11");
        }

        @Test
        void thePairNetsToNothing() {
            JournalEntry first = entry("J-13", debit("1210", d("4350.00")), credit("2100", d("4350.00")));
            JournalEntry back = first.reverse("J-14", LocalDate.of(2025, 5, 16));
            TrialBalance balance = trialBalance(chart, List.of(first, back), MONTH_END);
            assertThat(balance.balanceOf("1210")).isEqualTo(d("0.00"));
            assertThat(balance.balanceOf("2100")).isEqualTo(d("0.00"));
        }
    }

    @Nested
    class TrialBalances {

        List<JournalEntry> journal;

        @BeforeEach
        void postTheMonth() {
            journal = List.of(
                    entryOn(
                            "J-100",
                            LocalDate.of(2025, 5, 5),
                            debit("1210", d("4350.00")),
                            credit("4100", d("42.30")),
                            credit("2200", d("4.35")),
                            credit("2100", d("4303.35"))),
                    entryOn(
                            "J-101",
                            LocalDate.of(2025, 5, 20),
                            debit("2100", d("4303.35")),
                            credit("1100", d("4303.35"))));
        }

        @Test
        void itBalances() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            assertThat(balance.balances()).isTrue();
            assertThat(balance.debits()).isEqualTo(balance.credits());
        }

        @Test
        void thePayableIsClearedByTheCheque() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            assertThat(balance.balanceOf("2100")).isEqualTo(d("0.00"));
            assertThat(balance.balanceOf("1100")).isEqualTo(d("-4303.35"));
        }

        @Test
        void revenueReadsPositiveOnItsOwnSide() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            assertThat(balance.balanceOf("4100")).isEqualTo(d("42.30"));
        }

        @Test
        void anEntryAfterTheCutoffIsLeftOut() {
            TrialBalance balance = trialBalance(chart, journal, LocalDate.of(2025, 5, 10));
            assertThat(balance.balanceOf("2100")).isEqualTo(d("4303.35"));
            assertThat(balance.balanceOf("1100")).isEqualTo(d("0.00"));
        }

        @Test
        void aSinceDateMakesItAPeriodRatherThanARunningTotal() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END, LocalDate.of(2025, 5, 10));
            assertThat(balance.balanceOf("1210")).isEqualTo(d("0.00"));
            assertThat(balance.balanceOf("1100")).isEqualTo(d("-4303.35"));
        }

        @Test
        void headingsAreNotRowsOnIt() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            List<String> codes =
                    balance.rows().stream().map(row -> row.account().code()).toList();
            assertThat(codes).doesNotContain("1200").contains("1210");
        }

        @Test
        void nonemptyDropsTheAccountsNothingLandedIn() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            Set<String> touched =
                    balance.nonempty().stream().map(row -> row.account().code()).collect(Collectors.toSet());
            assertThat(touched).containsExactlyInAnyOrder("1210", "4100", "2200", "2100", "1100");
        }

        @Test
        void aSubtotalAddsOneKindOfAccount() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            assertThat(balance.subtotal(AccountType.REVENUE)).isEqualTo(d("42.30"));
            assertThat(balance.subtotal(AccountType.LIABILITY)).isEqualTo(d("4.35"));
        }

        @Test
        void askingForAnAccountThatIsNotOnItFails() {
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            assertThatThrownBy(() -> balance.row("1200"))
                    .isInstanceOf(AccountNotFound.class)
                    .hasMessageContaining("not on this trial balance");
        }
    }

    @Nested
    class Rollup {

        @Test
        void aHeadingCarriesWhatItsChildrenHold() {
            List<JournalEntry> journal = List.of(entry(
                    "J-200", debit("1210", d("100.00")), debit("1220", d("250.00")), credit("2100", d("350.00"))));
            TrialBalance balance = trialBalance(chart, journal, MONTH_END);
            Map<String, BigDecimal> totals = rollup(chart, balance);
            assertThat(totals.get("1210")).isEqualTo(d("100.00"));
            assertThat(totals.get("1220")).isEqualTo(d("250.00"));
            assertThat(totals.get("1200")).isEqualTo(d("350.00"));
            assertThat(totals.get("1000")).isEqualTo(d("350.00"));
        }

        @Test
        void everyAccountInTheChartAppears() {
            TrialBalance balance = trialBalance(chart, List.of(), MONTH_END);
            Map<String, BigDecimal> totals = rollup(chart, balance);
            assertThat(totals.keySet()).containsExactlyElementsOf(codes(chart.inCodeOrder()));
            assertThat(totals.values()).allSatisfy(value -> assertThat(value).isEqualTo(d("0.00")));
        }
    }
}
