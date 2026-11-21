package io.github.rkaushikdev.bushelworks.persistence.models;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Purchase contracts, the bushels applied to them, and settlements with their lines. */
class TradingModelsTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static ContractRow aContract() {
        return aContract(contract -> {});
    }

    static ContractRow aContract(Consumer<ContractRow> overrides) {
        ContractRow contract = new ContractRow();
        contract.setNumber("F-2024-118");
        contract.setKind("forward");
        contract.setPartyCode("MERRICK");
        contract.setCommodityCode("CORN");
        contract.setLocationCode("HOME");
        contract.setWrittenOn(LocalDate.of(2024, 5, 14));
        contract.setBushels(d("20000"));
        contract.setWindowOpens(LocalDate.of(2024, 10, 1));
        contract.setWindowCloses(LocalDate.of(2024, 11, 30));
        contract.setFlatPrice(d("4.6200"));
        overrides.accept(contract);
        return contract;
    }

    static ScaleTicketRow aTicket() {
        ScaleTicketRow ticket = new ScaleTicketRow();
        ticket.setTicketNumber("A-10441");
        ticket.setLocationCode("HOME");
        ticket.setScaleDate(LocalDate.of(2024, 10, 3));
        ticket.setCommodityCode("CORN");
        ticket.setPartyCode("MERRICK");
        ticket.setGrossPounds(d("78420"));
        ticket.setTarePounds(d("26180"));
        ticket.setDisposition("contract");
        return ticket;
    }

    static ApplicationRow anApplication(Integer ticketId, Integer contractId, String bushels) {
        ApplicationRow application = new ApplicationRow();
        application.setTicketId(ticketId);
        application.setContractId(contractId);
        application.setBushels(d(bushels));
        application.setAppliedOn(LocalDate.of(2024, 10, 3));
        return application;
    }

    static SettlementRow aSettlement(String number, String gross, String deductions, String net) {
        SettlementRow settlement = new SettlementRow();
        settlement.setNumber(number);
        settlement.setSettledOn(LocalDate.of(2024, 10, 20));
        settlement.setPartyCode("MERRICK");
        settlement.setLocationCode("HOME");
        settlement.setGross(d(gross));
        settlement.setDeductions(d(deductions));
        settlement.setNet(d(net));
        return settlement;
    }

    static SettlementLineRow aLine(int sequence, String kind, String code, String description, String dollars) {
        SettlementLineRow line = new SettlementLineRow();
        line.setSequence(sequence);
        line.setKind(kind);
        line.setCode(code);
        line.setDescription(description);
        line.setDollars(d(dollars));
        return line;
    }

    private static ContractRow contractNumbered(UnitOfWork uow, String number) {
        return uow.session()
                .createQuery("select c from ContractRow c where c.number = :number", ContractRow.class)
                .setParameter("number", number)
                .getSingleResult();
    }

    @Test
    void theTradingTablesExist() {
        assertThat(database.tableNames())
                .contains(
                        "purchase_contract",
                        "contract_application",
                        "settlement",
                        "settlement_line",
                        "settlement_ticket");
    }

    @Test
    void aContractRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aContract());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ContractRow found = contractNumbered(uow, "F-2024-118");
            assertThat(found.getStatus()).isEqualTo("open");
            assertThat(found.getTolerancePercent()).isEqualTo(d("2.0000"));
            assertThat(found.toString()).startsWith("F-2024-118 CORN");
        }
    }

    @Test
    void aContractNumberIsUniqueAcrossTheHouse() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aContract());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aContract(contract -> contract.setLocationCode("LURAY")));
                uow.commit();
            });
        }
    }

    @Test
    void aContractOfNoBushelsIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aContract(contract -> contract.setBushels(d("0"))));
                uow.commit();
            });
        }
    }

    @Test
    void aWindowThatClosesFirstIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aContract(contract -> {
                    contract.setWindowOpens(LocalDate.of(2024, 11, 30));
                    contract.setWindowCloses(LocalDate.of(2024, 10, 1));
                }));
                uow.commit();
            });
        }
    }

    @Test
    void anApplicationLinksATicketToAContract() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ContractRow contract = aContract();
            uow.session().persist(ticket);
            uow.session().persist(contract);
            uow.flush();
            uow.session().persist(anApplication(ticket.getId(), contract.getId(), "932.8571"));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ContractRow contract = contractNumbered(uow, "F-2024-118");
            assertThat(contract.getApplications()).hasSize(1);
            assertThat(contract.getApplications().get(0).getBushels()).isEqualTo(d("932.8571"));
            assertThat(contract.getApplications().get(0).getContract()).isSameAs(contract);
        }
    }

    @Test
    void oneTicketLandsOnOneContractOnlyOnce() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ContractRow contract = aContract();
            uow.session().persist(ticket);
            uow.session().persist(contract);
            uow.flush();
            assertIntegrityError(() -> {
                for (int copy = 0; copy < 2; copy++) {
                    uow.session().persist(anApplication(ticket.getId(), contract.getId(), "100"));
                }
                uow.commit();
            });
        }
    }

    @Test
    void anApplicationNeedsARealContract() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            uow.session().persist(ticket);
            uow.flush();
            assertIntegrityError(() -> {
                uow.session().persist(anApplication(ticket.getId(), 9999, "100"));
                uow.commit();
            });
        }
    }

    @Test
    void aSettlementKeepsItsLinesInSequence() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRow settlement = aSettlement("S-2024-0417", "3904.70", "111.31", "3793.39");
            settlement.setLines(List.of(
                    aLine(2, "drying", "DRY", "drying", "102.33"), aLine(1, "gross", "GRAIN", "corn", "3904.70")));
            uow.session().persist(settlement);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRow found = uow.session()
                    .createQuery("select s from SettlementRow s where s.number = 'S-2024-0417'", SettlementRow.class)
                    .getSingleResult();
            assertThat(found.getLines())
                    .extracting(SettlementLineRow::getSequence)
                    .containsExactly(1, 2);
        }
    }

    @Test
    void aSettlementLineMayNotBeStoredNegative() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRow settlement = aSettlement("S-1", "100", "0", "100");
            settlement.setLines(List.of(aLine(1, "discount", "TW", "nope", "-1.00")));
            assertIntegrityError(() -> {
                uow.session().persist(settlement);
                uow.commit();
            });
        }
    }

    @Test
    void deletingASettlementTakesItsLines() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRow settlement = aSettlement("S-1", "100", "0", "100");
            settlement.setLines(List.of(aLine(1, "gross", "GRAIN", "corn", "100.00")));
            uow.session().persist(settlement);
            uow.commit();
            uow.session().remove(settlement);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), SettlementLineRow.class)).isEmpty();
        }
    }

    @Test
    void aSettlementCanNameTheTicketsItCovers() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            SettlementRow settlement = aSettlement("S-1", "100", "0", "100");
            uow.session().persist(ticket);
            uow.session().persist(settlement);
            uow.flush();
            SettlementTicketRow covered = new SettlementTicketRow();
            covered.setSettlementId(settlement.getId());
            covered.setTicketId(ticket.getId());
            uow.session().persist(covered);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), SettlementTicketRow.class))
                    .hasSize(1);
        }
    }
}
