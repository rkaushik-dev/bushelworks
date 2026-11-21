package io.github.rkaushikdev.bushelworks.reports.exports;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.LINE_COLUMNS;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.SETTLEMENT_COLUMNS;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.TICKET_COLUMNS;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.cell;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.checkRoundTrip;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.linesToCsv;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.settlementsToCsv;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.textField;
import static io.github.rkaushikdev.bushelworks.reports.exports.Exports.ticketsToCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExportsTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    /** Two loads for MERRICK, settled together as S-2024-0001, committed. */
    void busy() {
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, d("4.35"));
            uow.commit();
        }
    }

    String merricksTickets() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return ticketsToCsv(uow.tickets().forParty("MERRICK"));
        }
    }

    Settlement theStatement() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return uow.settlements().get("S-2024-0001");
        }
    }

    @Test
    void aPlainIdentifierIsWrittenAsItIs() {
        assertThat(textField("A-10441")).isEqualTo("A-10441");
        assertThat(textField(null)).isEmpty();
    }

    @Test
    void anIdentifierWithLeadingZeroesIsProtected() {
        assertThat(textField("0010441")).isEqualTo("=\"0010441\"");
    }

    @Test
    void aNumberWithoutLeadingZeroesIsLeftAlone() {
        assertThat(textField("10441")).isEqualTo("10441");
    }

    @Test
    void cellsRenderTheTypesTheOfficeReads() {
        assertThat(cell(null)).isEmpty();
        assertThat(cell(true)).isEqualTo("yes");
        assertThat(cell(false)).isEqualTo("no");
        assertThat(cell(d("897.6324"))).isEqualTo("897.6324");
        assertThat(cell(LocalDate.of(2024, 10, 3))).isEqualTo("2024-10-03");
    }

    @Test
    void theTicketExportHasAHeaderAndARowEach() {
        busy();
        assertThat(checkRoundTrip(merricksTickets(), TICKET_COLUMNS)).isEqualTo(2);
    }

    @Test
    void theTicketExportCarriesTheNetWeight() {
        busy();
        assertThat(merricksTickets()).contains("52240");
    }

    @Test
    void theTicketExportSaysWhetherATicketIsSettled() {
        busy();
        assertThat(merricksTickets()).contains(",yes");
    }

    @Test
    void anEmptyTicketExportStillHasAHeader() {
        assertThat(checkRoundTrip(ticketsToCsv(List.of()), TICKET_COLUMNS)).isZero();
    }

    @Test
    void theSettlementExportHasOneRowPerStatement() {
        busy();
        String text = settlementsToCsv(List.of(theStatement()));
        assertThat(checkRoundTrip(text, SETTLEMENT_COLUMNS)).isEqualTo(1);
    }

    @Test
    void theSettlementExportListsTheTicketsItCovers() {
        busy();
        String text;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRow row = uow.settlements().row("S-2024-0001");
            Settlement statement = uow.settlements().get("S-2024-0001");
            assertThat(row.getNumber()).isEqualTo(statement.number());
            text = settlementsToCsv(List.of(statement));
        }
        assertThat(text.split("\n")[0]).contains("gross");
    }

    @Test
    void theLineExportHasARowPerLine() {
        busy();
        Settlement statement = theStatement();
        assertThat(checkRoundTrip(linesToCsv(List.of(statement)), LINE_COLUMNS))
                .isEqualTo(statement.lines().size());
    }

    @Test
    void theLineExportCarriesBothSigns() {
        busy();
        String text = linesToCsv(List.of(theStatement()));
        assertThat(text).contains("gross").contains("-");
    }

    @Test
    void theLineExportNumbersTheLinesFromOne() {
        busy();
        String[] rows = linesToCsv(List.of(theStatement())).strip().split("\n");
        assertThat(rows[1].split(",")[1]).isEqualTo("1");
    }

    @Test
    void aRoundTripCheckOnAnEmptyStringIsRefused() {
        assertThatThrownBy(() -> checkRoundTrip("", TICKET_COLUMNS)).isInstanceOf(ExportError.class);
    }

    @Test
    void aRoundTripCheckCatchesTheWrongHeader() {
        assertThatThrownBy(() -> checkRoundTrip("a,b,c\n1,2,3\n", TICKET_COLUMNS))
                .isInstanceOf(ExportError.class);
    }

    @Test
    void aRoundTripCheckCatchesAShortRow() {
        String text = String.join(",", SETTLEMENT_COLUMNS) + "\nS-1,2024-10-20\n";
        assertThatThrownBy(() -> checkRoundTrip(text, SETTLEMENT_COLUMNS))
                .isInstanceOf(ExportError.class)
                .hasMessageContaining("row 2");
    }

    @Test
    void aRemarkWithACommaSurvivesTheRoundTrip() {
        busy();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().row("HOME", "A-10441").setRemarks("reweighed, driver had the wrong tare");
            uow.commit();
        }
        assertThat(checkRoundTrip(merricksTickets(), TICKET_COLUMNS)).isEqualTo(2);
    }
}
