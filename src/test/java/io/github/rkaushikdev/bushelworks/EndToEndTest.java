package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlements;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.reports.statement.Statements;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.NothingToSettle;
import io.github.rkaushikdev.bushelworks.services.settlement.RunOptions;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One harvest day, from the driveway to the cheque.
 *
 * <p>This is the test that catches the cross layer mistakes the unit tests do not: a rate resolved as
 * of today instead of the ticket date, a discount extended over the wrong bushel count, a checkoff
 * levied per ticket instead of per statement. It works one producer through four loads and asserts
 * the numbers a grain accountant would tie out by hand.
 */
class EndToEndTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);
    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = d("4.3500");

    Database database;
    RuleBook house;

    @BeforeEach
    void freshHouse() {
        database = TestDatabase.fresh();
        house = Houses.houseRulebook();
    }

    /** A load of MERRICK's corn weighed on harvest day, to be changed as a test needs. */
    static ScaleTicket.Builder load(String number, String gross, String tare, String moisture) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("56.5"));
        sample.put(Factor.MOISTURE, d(moisture));
        sample.put(Factor.BCFM, d("1.2"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.0"));
        return ScaleTicket.builder()
                .ticketNumber(number)
                .scaleDate(HARVEST)
                .locationCode("HOME")
                .commodityCode("CORN")
                .grossPounds(d(gross))
                .tarePounds(d(tare))
                .disposition(Disposition.CASH_SALE)
                .splits(List.of(new SplitShare("MERRICK", d("100"))))
                .truckId("T-14")
                .sample(sample);
    }

    static PurchaseContract aForward() {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("2000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    /** Four loads against a two thousand bushel forward, committed. */
    void aDaysHauling() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aForward());
            String[][] loads = {
                {"A-10441", "78420", "26180", "18.2"},
                {"A-10442", "79100", "26180", "17.4"},
                {"A-10443", "77980", "26240", "15.8"},
                {"A-10444", "78650", "26180", "14.6"},
            };
            for (String[] one : loads) {
                ScaleHouse.recordLoad(uow, load(one[0], one[1], one[2], one[3]).build(), "MERRICK", house);
            }
            uow.commit();
        }
    }

    Settlement settleMerrick() {
        return settleMerrick(RunOptions.defaults());
    }

    Settlement settleMerrick(RunOptions options) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Settlement statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", house, SETTLED, SPOT, options);
            uow.commit();
            return statement;
        }
    }

    GradedLoad graded(UnitOfWork uow, String ticketNumber) {
        return Intake.receive(Mappers.ticketFromRow(uow.tickets().row("HOME", ticketNumber)), house);
    }

    @Test
    void allFourLoadsAreOnFile() {
        aDaysHauling();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().awaitingSettlement("HOME"))
                    .extracting(ScaleTicketRow::getTicketNumber)
                    .containsExactly("A-10441", "A-10442", "A-10443", "A-10444");
        }
    }

    @Test
    void theForwardFillsAndStopsTakingBushels() {
        aDaysHauling();
        BigDecimal applied;
        PurchaseContract contract;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            applied = uow.contracts().appliedBushels("F-2024-118");
            contract = uow.contracts().get("F-2024-118");
        }
        assertThat(applied).isEqualByComparingTo(contract.maximumBushels());
        assertThat(applied).isEqualTo(d("2040.0000"));
    }

    @Test
    void theDryLoadNeitherShrinksNorDries() {
        aDaysHauling();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            GradedLoad dry = graded(uow, "A-10444");
            assertThat(dry.shrinkPercent()).isEqualTo(d("0.0000"));
            assertThat(Intake.dryingCharge(dry, house)).isEqualTo(d("0.00"));
        }
    }

    @Test
    void theWettestLoadShrinksTheMost() {
        aDaysHauling();
        GradedLoad wet;
        GradedLoad dry;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            wet = graded(uow, "A-10441");
            dry = graded(uow, "A-10443");
        }
        assertThat(wet.shrinkPercent()).isGreaterThan(dry.shrinkPercent());
        assertThat(wet.moisturePoints()).isEqualByComparingTo(d("3.2"));
        assertThat(dry.moisturePoints()).isEqualByComparingTo(d("0.8"));
    }

    @Test
    void oneStatementCoversTheWholeDay() {
        aDaysHauling();
        Settlement statement = settleMerrick();
        assertThat(statement.number()).isEqualTo("S-2024-0001");
        assertThat(statement.ticketNumbers()).hasSize(4);
        assertThat(statement.ofKind(LineKind.GROSS)).hasSize(4);
        assertThatCode(statement::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void theStatementBalancesToTheCent() {
        aDaysHauling();
        Settlement statement = settleMerrick(RunOptions.defaults()
                .withAdvances(List.of(new Advance("2024-0912", d("5000.00"), LocalDate.of(2024, 9, 12)))));
        assertThat(statement.net()).isEqualByComparingTo(statement.gross().subtract(statement.deductions()));
        assertThat(statement.subtotal(LineKind.ADVANCE)).isEqualByComparingTo(d("5000.00"));
    }

    @Test
    void theCheckoffIsOneLineForFourLoads() {
        aDaysHauling();
        List<SettlementLine> levies = settleMerrick().ofKind(LineKind.ASSESSMENT);
        assertThat(levies).hasSize(1);
        assertThat(levies.get(0).code()).isEqualTo("KSCORN");
    }

    @Test
    void theContractedLoadsArePaidMoreThanTheSpotOnes() {
        aDaysHauling();
        BigDecimal firstPrice;
        BigDecimal lastPrice;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow first = uow.tickets().row("HOME", "A-10441");
            ScaleTicketRow last = uow.tickets().row("HOME", "A-10444");
            firstPrice = SettlementRun.priceOf(
                    uow, first, SPOT, graded(uow, "A-10441").settledBushels());
            lastPrice = SettlementRun.priceOf(
                    uow, last, SPOT, graded(uow, "A-10444").settledBushels());
        }
        assertThat(firstPrice).isEqualTo(d("4.6200"));
        assertThat(lastPrice).isEqualTo(d("4.3500"));
    }

    @Test
    void nothingSettlesTwice() {
        aDaysHauling();
        settleMerrick();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> SettlementRun.settleProducer(uow, "MERRICK", "HOME", house, SETTLED, SPOT))
                    .isInstanceOf(NothingToSettle.class);
        }
    }

    @Test
    void theStatementPrintsInsideEightyColumns() {
        aDaysHauling();
        String text = Statements.render(settleMerrick());
        assertThat(text.split("\n", -1))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
        assertThat(text).contains("A-10441").contains("NET PAYABLE");
    }

    @Test
    void aCropShareLoadSplitsTheNet() {
        aDaysHauling();
        Settlement statement = settleMerrick();
        List<SplitShare> shares = List.of(
                new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
                new SplitShare("MERRICK", d("75"), SplitRole.TENANT));
        Map<String, BigDecimal> parts = Settlements.split(statement, shares);
        assertThat(TestDecimals.sum(List.copyOf(parts.values()))).isEqualByComparingTo(statement.net());
    }

    @Test
    void aVoidedLoadIsNotSettled() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, load("A-10501", "78420", "26180", "16.0").build(), "MERRICK", house);
            ScaleHouse.recordLoad(uow, load("A-10502", "78420", "26180", "16.0").build(), "MERRICK", house);
            uow.tickets().voidTicket("HOME", "A-10502", "reweighed");
            statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", house, SETTLED, SPOT);
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).containsExactly("A-10501");
    }

    @Test
    void aSecondProducerGetsTheNextNumber() {
        aDaysHauling();
        Settlement first;
        Settlement second;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(
                    uow,
                    load("A-10601", "78420", "26180", "16.0")
                            .splits(List.of(new SplitShare("HOLLIS", d("100"))))
                            .build(),
                    "HOLLIS",
                    house);
            first = SettlementRun.settleProducer(uow, "MERRICK", "HOME", house, SETTLED, SPOT);
            second = SettlementRun.settleProducer(uow, "HOLLIS", "HOME", house, SETTLED, SPOT);
            uow.commit();
        }
        assertThat(first.number()).isEqualTo("S-2024-0001");
        assertThat(second.number()).isEqualTo("S-2024-0002");
        assertThat(first.net()).isNotEqualByComparingTo(second.net());
    }
}
