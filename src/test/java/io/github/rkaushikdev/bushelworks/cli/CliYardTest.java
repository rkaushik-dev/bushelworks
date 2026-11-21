package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.deferred.Deferred;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CliYardTest {

    Database database;
    Shell shell;
    RuleBook rules;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        shell = Shell.on(database);
        rules = HouseFixtures.rules();
    }

    /** A storage lot for MERRICK and a deferred price lot for HOLLIS, both corn at HOME. */
    void stocked() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.store(uow, "L-1", "MERRICK", "CORN", "HOME", d("8000"), LocalDate.of(2024, 10, 3));
            Deferred.deliver(uow, "D-1", "HOLLIS", "CORN", "HOME", d("4000"), LocalDate.of(2024, 10, 8));
            uow.commit();
        }
    }

    /** One load each for MERRICK and HOLLIS, both settled on the twentieth. */
    void settled() {
        ScaleTicket corn = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, corn, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, corn.toBuilder().ticketNumber("A-10442").build(), "HOLLIS", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, LocalDate.of(2024, 10, 20), d("4.35"));
            SettlementRun.settleProducer(uow, "HOLLIS", "HOME", rules, LocalDate.of(2024, 10, 20), d("4.35"));
            uow.commit();
        }
    }

    @Test
    void theGroupListsTheYardCommands() {
        assertThat(Shell.bare().run("--help").output()).contains("lots", "on-hand", "accrue", "position", "cheques");
    }

    @Test
    void lotsListsWhatIsOpen() {
        stocked();
        Shell.Result result = shell.run("lots");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("L-1", "D-1", "2 open lots");
    }

    @Test
    void lotsMarksDeferredPriceSeparately() {
        stocked();
        assertThat(shell.run("lots").output()).contains("DP ", "STO");
    }

    @Test
    void lotsCanBeFilteredByProducer() {
        stocked();
        Shell.Result result = shell.run("lots", "--party", "HOLLIS");
        assertThat(result.output()).contains("1 open lots").doesNotContain("L-1");
    }

    @Test
    void onHandReportsWhatIsHeld() {
        stocked();
        assertThat(shell.run("on-hand", "CORN").output()).contains("12000.0000 bu held for producers");
    }

    @Test
    void aDryRunBillsNothing() {
        stocked();
        Shell.Result result = shell.run("accrue", "2024-12-14");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("would bill");
        Shell.Result second = shell.run("accrue", "2024-12-14");
        assertThat(second.output()).contains("would bill");
        assertThat(second.out()).as("the second dry run finds the same charges").isEqualTo(result.out());
    }

    @Test
    void committingTheAccrualBillsOnce() {
        stocked();
        assertThat(shell.run("accrue", "2024-12-14", "--commit").output()).contains("billed");
        assertThat(shell.run("accrue", "2024-12-14", "--commit").output()).contains("0 lots");
    }

    @Test
    void thePositionPrintsAsABoard() {
        settled();
        Shell.Result result = shell.run("position", "2024-11-01");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("POSITION AS OF 2024-11-01");
    }

    @Test
    void thePositionPrintsAsAHedgeSheet() {
        settled();
        assertThat(shell.run("position", "2024-11-01", "--format", "hedge").output())
                .contains("HEDGE SHEET");
    }

    @Test
    void thePositionPrintsAsACsv() {
        settled();
        assertThat(shell.run("position", "2024-11-01", "--format", "csv").output())
                .startsWith("as_of,commodity_code");
    }

    @Test
    void theChequeRunWritesOnePerProducer() {
        settled();
        Shell.Result result = shell.run("cheques", "--first", "010441", "--on", "2024-10-24");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("010441", "010442", "2 cheques");
    }

    @Test
    void theChequeRunCanBeLimitedToAProducer() {
        settled();
        Shell.Result result = shell.run("cheques", "--first", "010441", "--on", "2024-10-24", "--party", "MERRICK");
        assertThat(result.output()).contains("1 cheques", "MERRICK");
    }

    @Test
    void aSecondChequeRunFindsNothing() {
        settled();
        shell.run("cheques", "--first", "010441", "--on", "2024-10-24");
        Shell.Result result = shell.run("cheques", "--first", "010443", "--on", "2024-10-25");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("payment_error");
    }

    @Test
    void unpaidListsTheSettlementsWaiting() {
        settled();
        assertThat(shell.run("unpaid").output()).contains("2 unpaid");
    }

    @Test
    void voidingATicketFromTheCommandLine() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, HouseFixtures.cornTicket(), "MERRICK", rules);
            uow.commit();
        }
        Shell.Result result = shell.run("void", "HOME", "A-10441", "--reason", "reweighed");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("A-10441 voided");
    }

    @Test
    void voidingAMissingTicketExitsNonZero() {
        Shell.Result result = shell.run("void", "HOME", "A-99999", "--reason", "x");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("ticket_not_found");
    }
}
