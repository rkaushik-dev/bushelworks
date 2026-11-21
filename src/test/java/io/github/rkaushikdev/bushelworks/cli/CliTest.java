package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    static final String[] SETTLE_MERRICK = {
        "settle", "MERRICK", "--location", "HOME", "--price", "4.3500", "--on", "2024-10-20", "--no-print"
    };

    @TempDir
    Path tmp;

    Database database;
    Shell shell;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        shell = Shell.on(database);
    }

    /** Two corn loads waiting for MERRICK at HOME. */
    void loaded() {
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket corn = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, corn, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, corn.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            uow.commit();
        }
    }

    @Test
    void theDecimalParameterParsesExactly() {
        assertThat(new Arguments.Exact().convert("4.3500")).isEqualTo(d("4.3500"));
    }

    @Test
    void theDecimalParameterRefusesNonsense() {
        Shell.Result result = Shell.bare().run("settle", "MERRICK", "--location", "HOME", "--price", "x");
        assertThat(result.code()).isNotZero();
        assertThat(result.output()).contains("is not a number");
    }

    @Test
    void helpListsTheCommands() {
        Shell.Result result = Shell.bare().run("--help");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("settle", "tickets", "grade", "register", "statement", "init-db");
    }

    @Test
    void initDbBuildsTheSchema() {
        String url = Shell.fileUrl(tmp, "fresh");
        Shell.Result result = Shell.at(url).run("init-db");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("schema built");
        try (Database built = Database.open(url)) {
            assertThat(built.tableNames()).contains("scale_ticket", "settlement", "ledger_account");
        }
    }

    @Test
    void ticketsListsWhatIsWaiting() {
        loaded();
        Shell.Result result = shell.run("tickets");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("A-10441", "A-10442", "2 tickets waiting");
    }

    @Test
    void ticketsCanBeFilteredByParty() {
        loaded();
        assertThat(shell.run("tickets", "--party", "HOLLIS").output()).contains("0 tickets waiting");
    }

    @Test
    void gradePrintsTheWorkedNumbers() {
        loaded();
        Shell.Result result = shell.run("grade", "HOME", "A-10441");
        assertThat(result.code()).isZero();
        assertThat(result.output())
                .contains("clean bushels 932.8571")
                .contains("dry bushels   897.6324")
                .contains("grade         US 1");
    }

    @Test
    void gradingAMissingTicketExitsNonZero() {
        loaded();
        Shell.Result result = shell.run("grade", "HOME", "A-99999");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("ticket_not_found");
    }

    @Test
    void settlePrintsTheStatement() {
        loaded();
        Shell.Result result =
                shell.run("settle", "MERRICK", "--location", "HOME", "--price", "4.3500", "--on", "2024-10-20");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("SETTLEMENT STATEMENT", "NET PAYABLE");
    }

    @Test
    void settleCanBeQuiet() {
        loaded();
        Shell.Result result = shell.run(SETTLE_MERRICK);
        assertThat(result.code()).isZero();
        assertThat(result.output().strip()).endsWith("S-2024-0001  net 7586.79");
    }

    @Test
    void settlingAProducerWithNothingWaitingExitsNonZero() {
        Shell.Result result = shell.run("settle", "HOLLIS", "--location", "HOME", "--price", "4.35");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("nothing_to_settle");
    }

    @Test
    void theRegisterPrintsAfterSettling() {
        loaded();
        shell.run(SETTLE_MERRICK);
        Shell.Result result = shell.run("register", "2024-10-20");
        assertThat(result.output()).contains("SETTLEMENT REGISTER", "1 statements");
    }

    @Test
    void aStatementCanBeReprinted() {
        loaded();
        shell.run(SETTLE_MERRICK);
        Shell.Result result = shell.run("statement", "S-2024-0001");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("S-2024-0001");
    }

    @Test
    void reprintingAMissingStatementExitsNonZero() {
        Shell.Result result = shell.run("statement", "S-9999");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("settlement_not_found");
    }

    @Test
    void theDatabaseCanBeOverriddenOnTheCommandLine() {
        String url = Shell.fileUrl(tmp, "other");
        Shell.Result result = Shell.plain().run("--database", url, "init-db");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains(url);
        try (Database built = Database.open(url)) {
            assertThat(built.tableNames()).contains("scale_ticket");
        }
    }
}
