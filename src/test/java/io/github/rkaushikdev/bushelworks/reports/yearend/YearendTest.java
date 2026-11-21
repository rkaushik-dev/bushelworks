package io.github.rkaushikdev.bushelworks.reports.yearend;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.yearend.YearEnd.build;
import static io.github.rkaushikdev.bushelworks.reports.yearend.YearEnd.busiestMonth;
import static io.github.rkaushikdev.bushelworks.reports.yearend.YearEnd.cropYearSpan;
import static io.github.rkaushikdev.bushelworks.reports.yearend.YearEnd.render;
import static io.github.rkaushikdev.bushelworks.reports.yearend.YearEnd.statementIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YearendTest {

    RuleBook rules;
    ScaleTicket cornTicket;
    List<Settlement> statements;

    @BeforeEach
    void settleTheYear() {
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
        statements = List.of(
                QuickStatements.statement(rules, cornTicket)
                        .number("S-2024-0417")
                        .settledOn(LocalDate.of(2024, 10, 20))
                        .build(),
                QuickStatements.statement(
                                rules,
                                cornTicket.toBuilder().ticketNumber("A-10442").build())
                        .number("S-2024-0418")
                        .settledOn(LocalDate.of(2024, 11, 4))
                        .advances(new Advance("A", d("500.00"), LocalDate.of(2024, 9, 12)))
                        .build(),
                QuickStatements.statement(
                                rules,
                                cornTicket.toBuilder().ticketNumber("A-10443").build())
                        .number("S-2025-0001")
                        .settledOn(LocalDate.of(2025, 1, 8))
                        .build());
    }

    static Settlement stub(LocalDate settledOn) {
        return stub(settledOn, "MERRICK");
    }

    static Settlement stub(LocalDate settledOn, String party) {
        return new Settlement(
                "S-X",
                settledOn,
                party,
                "HOME",
                List.of(new SettlementLine(LineKind.GROSS, "GRAIN", "corn", d("100.00"))));
    }

    @Test
    void aSummaryGathersOnlyTheYearAskedFor() {
        YearEndSummary summary = build("MERRICK", 2024, statements);
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.year()).isEqualTo(2024);
    }

    @Test
    void anotherProducersStatementsAreLeftOut() {
        Settlement other = QuickStatements.statement(rules, cornTicket)
                .number("S-2024-0500")
                .partyCode("HOLLIS")
                .build();
        List<Settlement> all = new ArrayList<>(statements);
        all.add(other);
        assertThat(build("MERRICK", 2024, all).count()).isEqualTo(2);
    }

    @Test
    void aStatementFromTheWrongYearIsRefused() {
        YearEndSummary summary = new YearEndSummary("MERRICK", 2024);
        assertThatThrownBy(() -> summary.add(stub(LocalDate.of(2025, 1, 8)))).isInstanceOf(YearEndError.class);
    }

    @Test
    void aStatementFromTheWrongProducerIsRefused() {
        YearEndSummary summary = new YearEndSummary("MERRICK", 2024);
        assertThatThrownBy(() -> summary.add(stub(LocalDate.of(2024, 10, 20), "HOLLIS")))
                .isInstanceOf(YearEndError.class);
    }

    @Test
    void theGrossAndTheNetAddTheStatementsUp() {
        YearEndSummary summary = build("MERRICK", 2024, statements);
        assertThat(summary.gross())
                .isEqualTo(statements.get(0).gross().add(statements.get(1).gross()));
        assertThat(summary.net())
                .isEqualTo(statements.get(0).net().add(statements.get(1).net()));
    }

    @Test
    void theTicketsAreListedOnceEach() {
        assertThat(build("MERRICK", 2024, statements).tickets()).containsExactly("A-10441", "A-10442");
    }

    @Test
    void theKindsAreTotalledInCanonicalOrder() {
        List<LineKind> kinds =
                List.copyOf(build("MERRICK", 2024, statements).byKind().keySet());
        assertThat(kinds.get(0)).isSameAs(LineKind.GROSS);
        assertThat(kinds).contains(LineKind.ADVANCE);
        assertThat(kinds.indexOf(LineKind.ADVANCE)).isGreaterThan(kinds.indexOf(LineKind.GROSS));
    }

    @Test
    void theSummaryBalancesAgainstTheStatements() {
        assertThatCode(() -> build("MERRICK", 2024, statements).checkBalances()).doesNotThrowAnyException();
    }

    @Test
    void aTamperedSummaryDoesNotBalance() {
        YearEndSummary summary = build("MERRICK", 2024, statements);
        YearEndSummary cut = new YearEndSummary(
                summary.partyCode(), summary.year(), summary.settlements().subList(0, 1));
        assertThatCode(cut::checkBalances).doesNotThrowAnyException();
        YearEndSummary padded = new YearEndSummary(
                summary.partyCode(), summary.year(), List.of(cut.settlements().get(0), statements.get(2)));
        assertThatThrownBy(() -> padded.add(statements.get(2))).isInstanceOf(YearEndError.class);
    }

    @Test
    void anEmptyYearSummarisesToNothing() {
        YearEndSummary summary = build("MERRICK", 2023, statements);
        assertThat(summary.count()).isZero();
        assertThat(summary.net()).isEqualTo(d("0.00"));
        assertThat(summary.byKind()).isEmpty();
    }

    @Test
    void thePageNamesTheProducerAndTheYear() {
        String text = render(build("MERRICK", 2024, statements));
        assertThat(text).contains("YEAR END SUMMARY 2024", "MERRICK", "NET PAID");
    }

    @Test
    void thePageFitsItsWidth() {
        String text = render(build("MERRICK", 2024, statements));
        assertThat(Arrays.asList(text.split("\n", -1)))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void reductionsPrintInBrackets() {
        assertThat(render(build("MERRICK", 2024, statements))).contains("(");
    }

    @Test
    void anEmptyYearSaysSoOnThePage() {
        assertThat(render(build("MERRICK", 2023, statements))).contains("Nothing settled in this year.");
    }

    @Test
    void theIndexListsEveryStatement() {
        String text = statementIndex(build("MERRICK", 2024, statements));
        assertThat(text).contains("S-2024-0417", "S-2024-0418", "2 statements");
    }

    @Test
    void theIndexIsInDateOrder() {
        String text = statementIndex(build("MERRICK", 2024, statements));
        assertThat(text.indexOf("S-2024-0417")).isLessThan(text.indexOf("S-2024-0418"));
    }

    @Test
    void theBusiestMonthIsTheOnePaidMost() {
        YearEnd.MonthPaid busiest =
                busiestMonth(build("MERRICK", 2024, statements)).orElseThrow();
        assertThat(busiest.month()).isEqualTo(10);
        assertThat(busiest.paid()).isEqualTo(statements.get(0).net());
    }

    @Test
    void anEmptyYearHasNoBusiestMonth() {
        assertThat(busiestMonth(build("MERRICK", 2023, statements))).isEmpty();
    }

    @Test
    void aCropYearRunsSeptemberToAugust() {
        YearEnd.CropYear span = cropYearSpan(2024);
        assertThat(span.opens()).isEqualTo(LocalDate.of(2023, 9, 1));
        assertThat(span.closes()).isEqualTo(LocalDate.of(2024, 8, 31));
    }
}
