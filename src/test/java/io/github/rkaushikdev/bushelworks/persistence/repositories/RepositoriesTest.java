package io.github.rkaushikdev.bushelworks.persistence.repositories;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.Location;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The ticket, contract, settlement and party repositories over one session. */
class RepositoriesTest {

    static final String PACKAGE = "io.github.rkaushikdev.bushelworks.persistence.repositories.";

    UnitOfWork work;
    ScaleTicket cornTicket;

    @BeforeEach
    void openASession() {
        Database database = TestDatabase.fresh();
        work = UnitOfWork.open(database);
        cornTicket = HouseFixtures.cornTicket();
    }

    @AfterEach
    void closeTheSession() {
        work.close();
    }

    static PurchaseContract aContract() {
        return aContract("F-2024-118").build();
    }

    static PurchaseContract.Builder aContract(String number) {
        return PurchaseContract.builder()
                .number(number)
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"));
    }

    Settlement statement(String number, LocalDate settledOn) {
        return QuickStatements.settlementFor(number, cornTicket, d("4.35"), HouseFixtures.rules(), "MERRICK", settledOn)
                .build();
    }

    TicketRepository tickets() {
        return new TicketRepository(work.session());
    }

    ContractRepository contracts() {
        return new ContractRepository(work.session());
    }

    SettlementRepository settlements() {
        return new SettlementRepository(work.session());
    }

    static Party aParty(String code, String name, boolean active) {
        Party party = new Party();
        party.setCode(code);
        party.setName(name);
        party.setSortName(code);
        party.setActive(active);
        return party;
    }

    @Test
    void aTicketCanBeWrittenAndReadBack() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        work.commit();
        ScaleTicket restored = repo.get("HOME", "A-10441");
        assertThat(restored.netPounds()).isEqualByComparingTo("52240");
    }

    @Test
    void writingTheSameTicketNumberTwiceIsAConflict() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        work.commit();
        assertThatThrownBy(() -> repo.add(cornTicket, "MERRICK"))
                .isInstanceOf(ConflictError.class)
                .hasMessage("ticket A-10441 already written at HOME");
    }

    @Test
    void theSameNumberAtAnotherSiteIsFine() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.add(cornTicket.toBuilder().locationCode("LURAY").build(), "MERRICK");
        work.commit();
        assertThat(repo.exists("LURAY", "A-10441")).isTrue();
    }

    @Test
    void aMissingTicketSaysSo() {
        assertThatThrownBy(() -> tickets().get("HOME", "A-99999"))
                .isInstanceOf(TicketNotFound.class)
                .hasMessage("no ticket A-99999 at HOME");
    }

    @Test
    void voidingMarksTheRowAndKeepsTheWeights() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        work.commit();
        ScaleTicketRow row = repo.voidTicket("HOME", "A-10441", "reweighed");
        assertThat(row.isVoided()).isTrue();
        assertThat(row.getRemarks()).contains("VOID: reweighed");
        assertThat(row.getNetPounds()).isEqualByComparingTo("52240");
    }

    @Test
    void aTicketCannotBeVoidedTwice() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.voidTicket("HOME", "A-10441", "reweighed");
        assertThatThrownBy(() -> repo.voidTicket("HOME", "A-10441", "again"))
                .isInstanceOf(ConflictError.class)
                .hasMessage("ticket A-10441 is already voided");
    }

    @Test
    void aSettledTicketCannotBeVoided() {
        TicketRepository repo = tickets();
        ScaleTicketRow row = repo.add(cornTicket, "MERRICK");
        row.setSettled(true);
        work.flush();
        assertThatThrownBy(() -> repo.voidTicket("HOME", "A-10441", "too late"))
                .isInstanceOf(ConflictError.class)
                .hasMessage("ticket A-10441 has already been settled");
    }

    @Test
    void ticketsAwaitingSettlementSkipVoids() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.add(cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK");
        repo.voidTicket("HOME", "A-10442", "reweighed");
        work.commit();
        assertThat(repo.awaitingSettlement())
                .extracting(ScaleTicketRow::getTicketNumber)
                .containsExactly("A-10441");
        assertThat(repo.awaitingSettlement("HOME"))
                .extracting(ScaleTicketRow::getTicketNumber)
                .containsExactly("A-10441");
        assertThat(repo.awaitingSettlement("LURAY")).isEmpty();
    }

    @Test
    void ticketsForAPartyCanBeLimitedByDate() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.add(
                cornTicket.toBuilder()
                        .ticketNumber("A-10500")
                        .scaleDate(LocalDate.of(2024, 11, 2))
                        .build(),
                "MERRICK");
        work.commit();
        assertThat(repo.forParty("MERRICK")).hasSize(2);
        assertThat(repo.forParty("MERRICK", LocalDate.of(2024, 11, 1))).hasSize(1);
    }

    @Test
    void netPoundsReceivedExcludesVoids() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.add(cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK");
        repo.voidTicket("HOME", "A-10442", "reweighed");
        work.commit();
        assertThat(repo.bushelsReceived("CORN", LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31)))
                .isEqualByComparingTo("52240");
        assertThat(repo.bushelsReceived("SOYB", LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31)))
                .isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void aContractCanBeWrittenAndReadBack() {
        ContractRepository repo = contracts();
        repo.add(aContract());
        work.commit();
        assertThat(repo.get("F-2024-118").unitPrice()).isEqualByComparingTo("4.6200");
    }

    @Test
    void aDuplicateContractNumberIsAConflict() {
        ContractRepository repo = contracts();
        repo.add(aContract());
        work.commit();
        assertThatThrownBy(() -> repo.add(aContract()))
                .isInstanceOf(ConflictError.class)
                .hasMessage("contract F-2024-118 already exists");
    }

    @Test
    void aMissingContractSaysSo() {
        assertThatThrownBy(() -> contracts().get("F-9999"))
                .isInstanceOf(ContractNotFound.class)
                .hasMessage("no contract F-9999");
    }

    @Test
    void anAmendedContractReplacesItsRow() {
        ContractRepository repo = contracts();
        repo.add(aContract());
        work.commit();
        PurchaseContract amended =
                repo.get("F-2024-118").toBuilder().tolerancePercent(d("5")).build();
        repo.replace(amended);
        work.commit();
        assertThat(repo.get("F-2024-118").tolerancePercent()).isEqualByComparingTo("5.0000");
    }

    @Test
    void openContractsAreFilteredByWindow() {
        ContractRepository repo = contracts();
        repo.add(aContract());
        repo.add(aContract("F-2024-119")
                .window(new DeliveryWindow(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)))
                .build());
        work.commit();
        assertThat(repo.openFor("MERRICK", "CORN", LocalDate.of(2024, 10, 15)))
                .extracting(PurchaseContract::number)
                .containsExactly("F-2024-118");
    }

    @Test
    void appliedBushelsIgnoresReversedApplications() {
        ScaleTicketRow ticketRow = tickets().add(cornTicket, "MERRICK");
        ContractRepository repo = contracts();
        ContractRow contractRow = repo.add(aContract());
        ApplicationRow application = new ApplicationRow();
        application.setTicketId(ticketRow.getId());
        application.setContractId(contractRow.getId());
        application.setBushels(d("900"));
        application.setAppliedOn(LocalDate.of(2024, 10, 3));
        application.setReversedOn(LocalDate.of(2024, 10, 4));
        work.session().persist(application);
        work.commit();
        assertThat(repo.appliedBushels("F-2024-118")).isEqualByComparingTo("0");
    }

    @Test
    void closingAContractTwiceIsAConflict() {
        ContractRepository repo = contracts();
        repo.add(aContract());
        assertThat(repo.close("F-2024-118").getStatus()).isEqualTo("filled");
        assertThatThrownBy(() -> repo.close("F-2024-118"))
                .isInstanceOf(ConflictError.class)
                .hasMessage("contract F-2024-118 is filled, not open");
    }

    @Test
    void aSettlementCanBeWrittenAndReadBack() {
        Settlement statement = statement("S-2024-0417", LocalDate.of(2024, 10, 20));
        SettlementRepository repo = settlements();
        repo.add(statement);
        work.commit();
        assertThat(repo.get("S-2024-0417").net()).isEqualByComparingTo(statement.net());
    }

    @Test
    void aSettlementCanNameItsTickets() {
        ScaleTicketRow row = tickets().add(cornTicket, "MERRICK");
        SettlementRow written = settlements().add(statement("S-1", LocalDate.of(2024, 10, 20)), List.of(row.getId()));
        work.commit();
        assertThat(settlements().ticketCounts(List.of(written.getId()))).containsEntry(written.getId(), 1);
    }

    @Test
    void aMissingSettlementSaysSo() {
        assertThatThrownBy(() -> settlements().get("S-9999"))
                .isInstanceOf(SettlementNotFound.class)
                .hasMessage("no settlement S-9999");
    }

    @Test
    void payingASettlementTwiceIsAConflict() {
        SettlementRepository repo = settlements();
        repo.add(statement("S-1", LocalDate.of(2024, 10, 20)));
        repo.markPaid("S-1", "10442", LocalDate.of(2024, 10, 21));
        work.commit();
        assertThatThrownBy(() -> repo.markPaid("S-1", "10443", LocalDate.of(2024, 10, 22)))
                .isInstanceOf(ConflictError.class)
                .hasMessage("settlement S-1 was already paid on 2024-10-21 by cheque 10442");
    }

    @Test
    void unpaidSettlementsAreListedOldestFirst() {
        SettlementRepository repo = settlements();
        repo.add(statement("S-2", LocalDate.of(2024, 10, 21)));
        repo.add(statement("S-1", LocalDate.of(2024, 10, 20)));
        work.commit();
        assertThat(repo.unpaid()).extracting(SettlementRow::getNumber).containsExactly("S-1", "S-2");
    }

    @Test
    void settlementsForADay() {
        SettlementRepository repo = settlements();
        repo.add(statement("S-1", LocalDate.of(2024, 10, 20)));
        work.commit();
        assertThat(repo.forDay(LocalDate.of(2024, 10, 20))).hasSize(1);
        assertThat(repo.forDay(LocalDate.of(2024, 10, 20), "HOME")).hasSize(1);
        assertThat(repo.forDay(LocalDate.of(2024, 10, 20), "LURAY")).isEmpty();
        assertThat(repo.forDay(LocalDate.of(2024, 10, 21))).isEmpty();
    }

    @Test
    void thePartyRepositoryFindsActiveParties() {
        work.session().persist(aParty("MERRICK", "Merrick Farms", true));
        work.session().persist(aParty("HOLLIS", "Hollis", false));
        Location home = new Location();
        home.setCode("HOME");
        home.setName("Home");
        home.setStateCode("KS");
        work.session().persist(home);
        work.commit();
        PartyRepository repo = new PartyRepository(work.session());
        assertThat(repo.active()).extracting(Party::getCode).containsExactly("MERRICK");
        assertThat(repo.get("HOLLIS").getName()).isEqualTo("Hollis");
        assertThat(repo.locations()).extracting(Location::getCode).containsExactly("HOME");
        assertThatThrownBy(() -> repo.get("NOBODY"))
                .isInstanceOf(PartyNotFound.class)
                .hasMessage("no party NOBODY");
    }

    /** Resolve a simple name in the repositories package, if it is a public top-level type there. */
    static Optional<Class<?>> exported(String name) {
        try {
            Class<?> type = Class.forName(PACKAGE + name);
            boolean visible = Modifier.isPublic(type.getModifiers()) && type.getEnclosingClass() == null;
            return visible ? Optional.of(type) : Optional.empty();
        } catch (ClassNotFoundException missing) {
            return Optional.empty();
        }
    }

    @Test
    void everyRepositoryIsStillReachableFromThePackage() {
        for (String name : List.of(
                "ComplaintNotFound",
                "ComplaintRepository",
                "ContractNotFound",
                "ContractRepository",
                "JournalEntryNotFound",
                "LedgerRepository",
                "PartyNotFound",
                "PartyRepository",
                "PeriodNotFound",
                "SettlementNotFound",
                "SettlementRepository",
                "TicketNotFound",
                "TicketRepository")) {
            assertThat(exported(name)).as(name).isPresent();
        }
        // The check can fail: a helper that is not exported, and a name that is not there.
        assertThat(exported("Queries")).isEmpty();
        assertThat(exported("SampleRepository")).isEmpty();
    }

    @Test
    void eachErrorLivesBesideTheRepositoryThatRaisesIt() {
        List<List<Class<?>>> pairs = List.of(
                List.of(TicketNotFound.class, TicketRepository.class),
                List.of(ContractNotFound.class, ContractRepository.class),
                List.of(SettlementNotFound.class, SettlementRepository.class),
                List.of(PartyNotFound.class, PartyRepository.class),
                List.of(ComplaintNotFound.class, ComplaintRepository.class),
                List.of(JournalEntryNotFound.class, LedgerRepository.class),
                List.of(PeriodNotFound.class, LedgerRepository.class));
        for (List<Class<?>> pair : pairs) {
            assertThat(pair.get(0).getPackage())
                    .as(pair.get(0).getSimpleName())
                    .isEqualTo(pair.get(1).getPackage());
            assertThat(pair.get(0).getEnclosingClass()).isNull();
            assertThat(LookupFailure.class).isAssignableFrom(pair.get(0));
        }
    }

    @Test
    void everyLookupFailureCarriesItsOwnCode() {
        Set<String> codes = Stream.of(
                        TicketNotFound.class,
                        ContractNotFound.class,
                        SettlementNotFound.class,
                        PartyNotFound.class,
                        ComplaintNotFound.class)
                .map(BushelworksError::codeOf)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(5).allMatch(code -> code.endsWith("_not_found"));
        assertThat(new TicketNotFound("no ticket").code()).isEqualTo("ticket_not_found");
    }

    @Test
    void aTicketNumberCanBeLookedUpAcrossTheSites() {
        TicketRepository repo = tickets();
        repo.add(cornTicket, "MERRICK");
        repo.add(cornTicket.toBuilder().locationCode("LURAY").build(), "MERRICK");
        work.flush();
        assertThat(repo.byNumber(cornTicket.ticketNumber()))
                .extracting(ScaleTicketRow::getLocationCode)
                .containsExactly("HOME", "LURAY");
    }

    @Test
    void aNumberNobodyWroteFindsNothing() {
        assertThat(tickets().byNumber("A-99999")).isEmpty();
    }
}
