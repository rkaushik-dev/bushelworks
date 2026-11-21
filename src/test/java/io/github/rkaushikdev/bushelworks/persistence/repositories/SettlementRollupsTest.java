package io.github.rkaushikdev.bushelworks.persistence.repositories;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.TestDecimals;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Counter;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageLine;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageYear;
import io.github.rkaushikdev.bushelworks.services.patronage.Patronage;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import io.github.rkaushikdev.bushelworks.services.tax.PayeeYear;
import io.github.rkaushikdev.bushelworks.services.tax.Tax;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The window query and the two rollups the tax file and the board both use.
 *
 * <p>The counting tests are the point of this class. The patronage table used to ask the database how
 * many tickets were on a statement once per statement, which is invisible on the three settlements a
 * unit test makes and is four thousand round trips on a crop year. Counting the statements is the only
 * way that stays fixed.
 */
class SettlementRollupsTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);
    static final BigDecimal SPOT = d("4.35");
    static final LocalDate YEAR_OPENS = LocalDate.of(2024, 1, 1);
    static final LocalDate YEAR_CLOSES = LocalDate.of(2024, 12, 31);

    /** Who hauls what, where, and the day it is weighed and settled. */
    private record Haul(String partyCode, String locationCode, List<String> ticketNumbers, LocalDate day) {}

    Database database;

    /** Two producers, three statements, spread over two months. */
    @BeforeEach
    void settled() {
        database = TestDatabase.fresh();
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        List<Haul> plan = List.of(
                new Haul("MERRICK", "HOME", List.of("A-10441", "A-10442"), HARVEST),
                new Haul("MERRICK", "HOME", List.of("A-10451"), LocalDate.of(2024, 11, 4)),
                new Haul("DELACROIX", "HOME", List.of("A-10461", "A-10462", "A-10463"), LocalDate.of(2024, 11, 5)));
        for (Haul haul : plan) {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                for (String number : haul.ticketNumbers()) {
                    ScaleHouse.recordLoad(
                            uow,
                            cornTicket.toBuilder()
                                    .ticketNumber(number)
                                    .scaleDate(haul.day())
                                    .build(),
                            haul.partyCode(),
                            rules);
                }
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                SettlementRun.settleProducer(uow, haul.partyCode(), haul.locationCode(), rules, haul.day(), SPOT);
                uow.commit();
            }
        }
    }

    static List<SettlementRow> theYear(UnitOfWork uow) {
        return uow.settlements().inWindow(YEAR_OPENS, YEAR_CLOSES);
    }

    static List<Integer> idsOf(List<SettlementRow> rows) {
        return rows.stream().map(SettlementRow::getId).toList();
    }

    @Nested
    class Window {

        @Test
        void aSpanPicksUpEverythingSettledInIt() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(theYear(uow)).hasSize(3);
            }
        }

        @Test
        void aNarrowerSpanPicksUpLess() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.settlements().inWindow(LocalDate.of(2024, 11, 1), LocalDate.of(2024, 11, 30)))
                        .hasSize(2);
            }
        }

        @Test
        void itCanBeNarrowedToOneProducer() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<SettlementRow> rows = uow.settlements().inWindow(YEAR_OPENS, YEAR_CLOSES, "DELACROIX", null);
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).getPartyCode()).isEqualTo("DELACROIX");
            }
        }

        @Test
        void itCanBeNarrowedToOneSite() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.settlements().inWindow(YEAR_OPENS, YEAR_CLOSES, null, "HOME"))
                        .hasSize(3);
                assertThat(uow.settlements().inWindow(YEAR_OPENS, YEAR_CLOSES, null, "NORTH"))
                        .isEmpty();
            }
        }

        @Test
        void itComesBackInNumberOrder() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<String> numbers =
                        theYear(uow).stream().map(SettlementRow::getNumber).toList();
                assertThat(numbers).isSorted().hasSize(3);
            }
        }

        @Test
        void aVoidedStatementIsNeverInIt() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.settlements().row("S-2024-0001").setVoided(true);
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(theYear(uow)).extracting(SettlementRow::getNumber).doesNotContain("S-2024-0001");
            }
        }

        @Test
        void itIsOneQuery() {
            Counter counter;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                counter = Counter.during(database, () -> theYear(uow));
            }
            assertThat(counter.selecting("settlement")).isEqualTo(1);
        }
    }

    @Nested
    class LineTotals {

        @Test
        void nothingAskedForIsNothingBack() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.settlements().lineTotals(List.of())).isEmpty();
                assertThat(uow.settlements().totalsByKind(List.of())).isEmpty();
            }
        }

        @Test
        void oneRowPerStatementAndKind() {
            List<LineTotal> totals;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                totals =
                        uow.settlements().lineTotals(List.of(theYear(uow).get(0).getId()));
            }
            Set<String> pairs = new HashSet<>();
            totals.forEach(line -> pairs.add(line.settlementId() + "/" + line.kind()));
            assertThat(totals).isNotEmpty();
            assertThat(pairs).hasSameSizeAs(totals);
        }

        @Test
        void theGrossLineCarriesTheBushels() {
            List<LineTotal> totals;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                totals =
                        uow.settlements().lineTotals(List.of(theYear(uow).get(0).getId()));
            }
            LineTotal gross = totals.stream()
                    .filter(line -> line.kind().equals(LineKind.GROSS.value()))
                    .findFirst()
                    .orElseThrow();
            assertThat(gross.quantity()).isPositive();
            assertThat(gross.dollars()).isPositive();
        }

        @Test
        void theKindsAddBackToTheHeader() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                SettlementRow row = theYear(uow).get(0);
                Map<String, BigDecimal> totals = uow.settlements().totalsByKind(List.of(row.getId()));
                assertThat(totals.get(LineKind.GROSS.value())).isEqualByComparingTo(row.getGross());
            }
        }

        @Test
        void totalsByKindAddsAcrossStatements() {
            Map<String, BigDecimal> everything;
            Map<String, BigDecimal> oneAtATime = new LinkedHashMap<>();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<SettlementRow> rows = theYear(uow);
                everything = uow.settlements().totalsByKind(idsOf(rows));
                for (SettlementRow row : rows) {
                    uow.settlements()
                            .totalsByKind(List.of(row.getId()))
                            .forEach((kind, dollars) -> oneAtATime.merge(kind, dollars, BigDecimal::add));
                }
            }
            assertThat(everything).isNotEmpty();
            assertThat(everything.keySet()).isEqualTo(oneAtATime.keySet());
            everything.forEach(
                    (kind, dollars) -> assertThat(dollars).as(kind).isEqualByComparingTo(oneAtATime.get(kind)));
        }

        @Test
        void itIsOneQueryHoweverManyStatements() {
            Counter counter;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<Integer> ids = idsOf(theYear(uow));
                counter = Counter.during(database, () -> uow.settlements().lineTotals(ids));
            }
            assertThat(counter.selecting("settlement_line")).isEqualTo(1);
        }
    }

    @Nested
    class TicketCounts {

        @Test
        void eachStatementCarriesItsLoads() {
            Map<String, Integer> byNumber;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<SettlementRow> rows = theYear(uow);
                Map<Integer, Integer> counts = uow.settlements().ticketCounts(idsOf(rows));
                byNumber = rows.stream()
                        .collect(Collectors.toMap(SettlementRow::getNumber, row -> counts.get(row.getId())));
            }
            assertThat(byNumber)
                    .containsOnly(entry("S-2024-0001", 2), entry("S-2024-0002", 1), entry("S-2024-0003", 3));
        }

        @Test
        void everyIdAskedAboutComesBack() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.settlements().ticketCounts(List.of(9998, 9999)))
                        .containsExactly(entry(9998, 0), entry(9999, 0));
            }
        }

        @Test
        void nothingAskedForIsNothingBack() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.settlements().ticketCounts(List.of())).isEmpty();
            }
        }

        @Test
        void itIsOneQueryAndNotOneEach() {
            Counter counter;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                List<Integer> ids = idsOf(theYear(uow));
                counter = Counter.during(database, () -> uow.settlements().ticketCounts(ids));
            }
            assertThat(counter.selecting("settlement_ticket")).isEqualTo(1);
        }
    }

    @Nested
    class TheCallersStillAgree {

        PatronageYear theTable() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                return Patronage.buildYear(uow, YEAR_OPENS, YEAR_CLOSES);
            }
        }

        @Test
        void thePatronageTableReadsTheSameAsBefore() {
            Map<String, PatronageLine> byCode = theTable().lines().stream()
                    .collect(Collectors.toMap(PatronageLine::partyCode, Function.identity()));
            assertThat(byCode.get("MERRICK").loads()).isEqualTo(3);
            assertThat(byCode.get("DELACROIX").loads()).isEqualTo(3);
            assertThat(byCode.get("MERRICK").bushels()).isPositive();
            assertThat(byCode.get("MERRICK").charges()).isPositive();
        }

        @Test
        void thePatronageGrossTiesToTheSettlementHeaders() {
            PatronageYear year;
            BigDecimal expected;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                year = Patronage.buildYear(uow, YEAR_OPENS, YEAR_CLOSES);
                expected = TestDecimals.sum(theYear(uow).stream()
                        .filter(row -> row.getPartyCode().equals("MERRICK"))
                        .map(SettlementRow::getGross)
                        .toList());
            }
            PatronageLine found = year.lines().stream()
                    .filter(line -> line.partyCode().equals("MERRICK"))
                    .findFirst()
                    .orElseThrow();
            assertThat(found.gross()).isEqualByComparingTo(expected);
        }

        @Test
        void theTaxFileReadsTheSameAsBefore() {
            PayeeYear found;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                found = Tax.payeeYear(uow, "MERRICK", 2024);
            }
            assertThat(found.settlements()).isEqualTo(2);
            assertThat(found.gross()).isPositive();
        }

        @Test
        void theTaxFileListsBothProducers() {
            List<PayeeYear> everybody;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                everybody = Tax.payees(uow, 2024);
            }
            assertThat(everybody).extracting(PayeeYear::partyCode).containsExactlyInAnyOrder("MERRICK", "DELACROIX");
        }

        @Test
        void thePatronageTableNoLongerQueriesPerStatement() {
            Counter counter;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                counter = Counter.during(database, () -> Patronage.buildYear(uow, YEAR_OPENS, YEAR_CLOSES));
            }
            assertThat(counter.selecting("settlement_ticket")).isEqualTo(1);
            assertThat(counter.selecting("settlement_line")).isEqualTo(1);
        }
    }
}
