package io.github.rkaushikdev.bushelworks.reports.statement;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.WIDTH;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.amountRow;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.register;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.render;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.renderMany;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.summaryLine;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.totalRow;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementBuilder;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatementReportTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal PRICE = d("4.3500");

    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void fileTheHouse() {
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    Settlement statement() {
        return statement("S-2024-0417");
    }

    Settlement statement(String number) {
        return QuickStatements.statement(rules, cornTicket)
                .number(number)
                .unitPrice(PRICE)
                .settledOn(SETTLED)
                .advances(new Advance("2024-0912", d("1500.00"), LocalDate.of(2024, 9, 12)))
                .contractNumber("F-2024-118")
                .build();
    }

    static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    @Test
    void noLineRunsPastEightyColumns() {
        assertThat(lines(render(statement())))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(WIDTH));
    }

    @Test
    void theHeaderNamesTheStatementAndTheProducer() {
        String text = render(statement());
        assertThat(text).contains("SETTLEMENT STATEMENT", "S-2024-0417", "MERRICK", "2024-10-20");
    }

    @Test
    void theTicketsAndContractsAreListed() {
        assertThat(render(statement())).contains("A-10441", "F-2024-118");
    }

    @Test
    void sectionHeadingsAppearForTheKindsPresent() {
        String text = render(statement());
        assertThat(text).contains("GRAIN PURCHASED", "DRYING", "CHECKOFF WITHHELD", "ADVANCES RECOVERED");
        assertThat(text).doesNotContain("OTHER DEDUCTIONS");
    }

    @Test
    void reducingLinesPrintInBrackets() {
        assertThat(render(statement())).contains("(1,500.00)");
    }

    @Test
    void theNetIsTheLastThingOnThePage() {
        Settlement s = statement();
        List<String> page = lines(render(s));
        String last = page.get(page.size() - 1);
        assertThat(last).startsWith("  NET PAYABLE");
        assertThat(last.strip()).endsWith("2,293.39");
        assertThat(s.net()).isEqualTo(d("2293.39"));
    }

    @Test
    void aSectionWithOneLineGetsNoSubtotal() {
        assertThat(render(statement())).doesNotContain("DRYING TOTAL");
    }

    @Test
    void theHouseNameCanBeOverridden() {
        assertThat(render(statement(), "LURAY GRAIN CO")).contains("LURAY GRAIN CO");
    }

    @Test
    void severalStatementsAreSeparatedByAFormFeed() {
        String text = renderMany(List.of(statement("S-1"), statement("S-2")));
        assertThat(text.chars().filter(letter -> letter == '\f').count()).isEqualTo(1);
        assertThat(text).contains("S-1", "S-2");
    }

    @Test
    void theSummaryLineIsOneRowPerStatement() {
        String row = summaryLine(statement());
        assertThat(row.length()).isLessThanOrEqualTo(WIDTH);
        assertThat(row).contains("S-2024-0417", "MERRICK");
    }

    @Test
    void theRegisterTotalsWhatItLists() {
        String text = register(List.of(statement("S-1"), statement("S-2")), "20 October 2024");
        assertThat(text).contains("SETTLEMENT REGISTER", "2 statements", "4,586.78");
    }

    /**
     * The regression that printed a charge twice.
     *
     * <p>An amount over a million formats to thirteen characters and the amount column was twelve.
     * The line ran to eighty one, the dot matrix wrapped it, and the producer read the wrapped
     * remainder as a second charge.
     */
    @Test
    void aSevenFigureStatementStaysInsideTheForm() {
        SettlementBuilder builder = new SettlementBuilder("S-2024-0417", SETTLED, "MERRICK", "HOME");
        builder.addGross("GRAIN", "Corn, 3,000,000 bu", d("3000000"), d("4.3500"));
        builder.addCharge(LineKind.ADVANCE, "ADV", "advance of 12 September", d("2000000.00"));
        assertThat(lines(render(builder.build())))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(WIDTH));
    }

    @Test
    void aLongDescriptionIsTrimmedRatherThanWrapped() {
        assertThat(amountRow("TW", "x".repeat(200), d("18.66"))).hasSize(WIDTH);
    }

    @Test
    void aLongTotalLabelIsTrimmedToo() {
        assertThat(totalRow("y".repeat(200), d("-1234567890.12"))).hasSize(WIDTH);
    }

    @Test
    void aRegisterRowWithALongProducerStaysInsideTheForm() {
        Settlement statement = QuickStatements.statement(rules, cornTicket)
                .number("S-2024-0417")
                .build();
        Settlement longNamed = new Settlement(
                statement.number(),
                statement.settledOn(),
                "A".repeat(60),
                statement.locationCode(),
                statement.lines(),
                statement.ticketNumbers(),
                statement.contractNumbers());
        assertThat(summaryLine(longNamed).length()).isLessThanOrEqualTo(WIDTH);
    }

    @Test
    void anEmptyRegisterStillRenders() {
        String text = register(List.of(), "20 October 2024");
        assertThat(text).contains("0 statements", "0.00");
    }
}
