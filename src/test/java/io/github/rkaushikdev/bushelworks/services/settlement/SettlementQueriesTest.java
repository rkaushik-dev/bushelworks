package io.github.rkaushikdev.bushelworks.services.settlement;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Counter;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How many round trips a settlement takes.
 *
 * <p>Pricing used to query per ticket and then fetch each contract on its own. A nine load
 * settlement was nineteen statements for what is one join, and on the office box over a network
 * share that was most of the wall clock. These count the statements so it cannot quietly go back.
 */
class SettlementQueriesTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = d("4.3500");

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static PurchaseContract aContract(String number) {
        return PurchaseContract.builder()
                .number(number)
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("100000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    /** Nine loads for MERRICK, every one of them landing on one big forward contract, committed. */
    void nineLoads() {
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("F-2024-118"));
            for (int index = 0; index < 9; index++) {
                ScaleHouse.recordLoad(
                        uow,
                        cornTicket.toBuilder()
                                .ticketNumber("A-" + (10441 + index))
                                .build(),
                        "MERRICK",
                        rules);
            }
            uow.commit();
        }
    }

    static RuleBook rulesFor() {
        return Houses.houseRulebook();
    }

    static List<Integer> waitingIds(UnitOfWork uow) {
        return uow.tickets().awaitingSettlement("HOME").stream()
                .map(ScaleTicketRow::getId)
                .toList();
    }

    @Test
    void theApplicationsComeBackGroupedByTicket() {
        nineLoads();
        Map<Integer, List<LiveApplication>> grouped;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            grouped = SettlementRun.applicationsFor(uow, waitingIds(uow));
        }
        assertThat(grouped).hasSize(9);
        assertThat(grouped.values()).allSatisfy(items -> assertThat(items).hasSize(1));
    }

    @Test
    void askingForNoTicketsQueriesNothing() {
        nineLoads();
        try (UnitOfWork uow = UnitOfWork.open(database);
                Counter counter = Counter.start(database)) {
            assertThat(SettlementRun.applicationsFor(uow, List.of())).isEmpty();
            assertThat(counter.count()).isZero();
        }
    }

    @Test
    void theApplicationsAreFetchedInOnePass() {
        nineLoads();
        Counter counter;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<Integer> ticketIds = waitingIds(uow);
            counter = Counter.during(database, () -> SettlementRun.applicationsFor(uow, ticketIds));
        }
        // One for the applications, one for the contracts they landed on.
        assertThat(counter.selecting("contract_application")).isEqualTo(1);
        assertThat(counter.selecting("purchase_contract")).isEqualTo(1);
    }

    @Test
    void aTicketWithNoApplicationsPricesAtSpot() {
        assertThat(SettlementRun.priceFrom(List.of(), SPOT, d("900"))).isEqualTo(SPOT);
    }

    @Test
    void aWhollyContractedTicketPricesAtTheContract() {
        nineLoads();
        BigDecimal price;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<Integer> ticketIds = waitingIds(uow);
            Map<Integer, List<LiveApplication>> grouped = SettlementRun.applicationsFor(uow, ticketIds);
            price = SettlementRun.priceFrom(grouped.get(ticketIds.get(0)), SPOT, d("897.6324"));
        }
        assertThat(price).isEqualTo(d("4.6200"));
    }

    @Test
    void aPartlyContractedTicketBlends() {
        nineLoads();
        BigDecimal price;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<Integer> ticketIds = waitingIds(uow);
            Map<Integer, List<LiveApplication>> grouped = SettlementRun.applicationsFor(uow, ticketIds);
            price = SettlementRun.priceFrom(grouped.get(ticketIds.get(0)), SPOT, d("2000"));
        }
        assertThat(price).isGreaterThan(SPOT).isLessThan(d("4.6200"));
    }

    @Test
    void aStoredTicketIsPricedTheSameOnItsOwn() {
        nineLoads();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow row = uow.tickets().row("HOME", "A-10441");
            assertThat(SettlementRun.priceOf(uow, row, SPOT, d("897.6324"))).isEqualTo(d("4.6200"));
            assertThat(SettlementRun.priceOf(uow, row, SPOT, d("2000")))
                    .isGreaterThan(SPOT)
                    .isLessThan(d("4.6200"));
        }
    }

    @Test
    void settlingNineLoadsDoesNotQueryPerTicket() {
        nineLoads();
        Counter counter;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            counter = Counter.during(
                    database, () -> SettlementRun.settleProducer(uow, "MERRICK", "HOME", rulesFor(), SETTLED, SPOT));
            uow.rollback();
        }
        assertThat(counter.selecting("contract_application")).isLessThanOrEqualTo(2);
    }

    @Test
    void theSettlementStillComesOutRight() {
        nineLoads();
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", rulesFor(), SETTLED, SPOT);
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).hasSize(9);
        assertThatCode(statement::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void everyLoadIsPricedAtTheContract() {
        nineLoads();
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", rulesFor(), SETTLED, SPOT);
            uow.commit();
        }
        Set<BigDecimal> rates = statement.ofKind(LineKind.GROSS).stream()
                .map(line -> line.rate().orElseThrow())
                .collect(Collectors.toSet());
        assertThat(rates).containsExactly(d("4.6200"));
    }

    int positionStatements(int contracts) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (int index = 0; index < contracts; index++) {
                uow.contracts().add(aContract(String.format(Locale.ROOT, "F-2024-3%02d", index)));
            }
            uow.flush();
            return Counter.during(database, () -> Positions.contractedBushels(uow, "CORN", SETTLED))
                    .count();
        }
    }

    /**
     * The statement count does not move when the contract count does.
     *
     * <p>Two hundred open contracts used to mean two hundred and six statements for the daily
     * position. Asserting a fixed number would nail down whatever the mapping happens to do about
     * eager loading; asserting that it does not grow is the thing that actually matters.
     */
    @Test
    void thePositionDoesNotQueryPerContract() {
        int few = positionStatements(2);
        int many = positionStatements(12);
        assertThat(few).isPositive();
        assertThat(many).isEqualTo(few);
    }

    @Test
    void theApplicationsAreFetchedInOneGroupBy() {
        nineLoads();
        int statements;
        BigDecimal total;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Integer contractId = uow.contracts().row("F-2024-118").getId();
            Map<Integer, BigDecimal> applied;
            try (Counter counter = Counter.start(database)) {
                applied = Positions.appliedByContract(uow, List.of(contractId));
                statements = counter.selecting("contract_application");
            }
            total = applied.get(contractId);
        }
        assertThat(statements).isEqualTo(1);
        assertThat(total).isPositive();
    }

    @Test
    void askingAboutNoContractsQueriesNothing() {
        try (UnitOfWork uow = UnitOfWork.open(database);
                Counter counter = Counter.start(database)) {
            assertThat(Positions.appliedByContract(uow, List.of())).isEmpty();
            assertThat(counter.count()).isZero();
        }
    }

    @Test
    void aHouseWithNoOpenContractsQueriesTheApplicationsNotAtAll() {
        try (UnitOfWork uow = UnitOfWork.open(database);
                Counter counter = Counter.start(database)) {
            assertThat(Positions.contractedBushels(uow, "CORN", SETTLED)).isEqualTo(d("0.0000"));
            assertThat(counter.selecting("contract_application")).isZero();
            assertThat(counter.selecting("purchase_contract"))
                    .as("the counter saw the contract query")
                    .isEqualTo(1);
        }
    }

    @Test
    void theOutstandingBushelsAreStillRight() {
        nineLoads();
        BigDecimal applied;
        BigDecimal outstanding;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            applied = uow.contracts().appliedBushels("F-2024-118");
            outstanding = Positions.contractedBushels(uow, "CORN", SETTLED);
        }
        assertThat(outstanding).isEqualByComparingTo(d("100000").subtract(applied));
    }

    @Test
    void aContractDeliveredPastItsFaceDoesNotTurnIntoAShort() {
        nineLoads();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ContractRow row = uow.contracts().row("F-2024-118");
            row.setBushels(d("100"));
            uow.flush();
            assertThat(Positions.contractedBushels(uow, "CORN", SETTLED)).isEqualTo(d("0.0000"));
        }
    }

    @Test
    void aReversedApplicationGoesBackOnTheContract() {
        nineLoads();
        BigDecimal before;
        BigDecimal after;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            before = Positions.contractedBushels(uow, "CORN", SETTLED);
            for (ApplicationRow application : uow.contracts().row("F-2024-118").getApplications()) {
                application.setReversedOn(LocalDate.of(2024, 10, 6));
            }
            uow.flush();
            after = Positions.contractedBushels(uow, "CORN", SETTLED);
        }
        assertThat(after).isEqualTo(d("100000.0000"));
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void theLiveApplicationCarriesItsBushels() {
        nineLoads();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Optional<LiveApplication> first = SettlementRun.applicationsFor(uow, waitingIds(uow)).values().stream()
                    .flatMap(List::stream)
                    .findFirst();
            assertThat(first).hasValueSatisfying(application -> {
                assertThat(application.bushels()).isEqualTo(d("897.6324"));
                assertThat(application.contract().getNumber()).isEqualTo("F-2024-118");
            });
        }
    }
}
