package io.github.rkaushikdev.bushelworks.services.positions;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.services.deferred.Deferred;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.shipping.Shipping;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PositionsTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 11, 1);

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    /**
     * A busy house: a 20,000 bushel forward contract, two corn loads applied to it, 4,000 bushels in
     * storage for MERRICK and 6,000 unpriced for HOLLIS.
     */
    @BeforeEach
    void busy() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("20000"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            Warehousing.store(uow, "L-1", "MERRICK", "CORN", "HOME", d("4000"), LocalDate.of(2024, 10, 5));
            Deferred.deliver(uow, "D-1", "HOLLIS", "CORN", "HOME", d("6000"), LocalDate.of(2024, 10, 8));
            uow.commit();
        }
    }

    static PurchaseContract aContract(String bushels) {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d(bushels))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    static SaleRow aSale() {
        SaleRow row = new SaleRow();
        row.setNumber("S-2024-0088");
        row.setKind("forward");
        row.setBuyer("Salina Terminal");
        row.setDestination("terminal");
        row.setCommodityCode("CORN");
        row.setOriginCode("HOME");
        row.setWrittenOn(LocalDate.of(2024, 9, 5));
        row.setBushels(d("10000"));
        row.setWindowOpens(LocalDate.of(2024, 10, 1));
        row.setWindowCloses(LocalDate.of(2024, 12, 31));
        row.setFlatPrice(d("4.7800"));
        row.setSpecMaxMoisture(d("15.0"));
        row.setSpecMinTestWeight(d("54.0"));
        row.setSpecMaxDamage(d("5.0"));
        row.setSpecMaxForeign(d("3.0"));
        return row;
    }

    static SaleRow aSaleOf(String bushels) {
        SaleRow row = aSale();
        row.setBushels(d(bushels));
        return row;
    }

    @Test
    void receivedBushelsCountsEveryLiveTicket() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Positions.receivedBushels(uow, "CORN", AS_OF)).isEqualTo(d("1865.7143"));
        }
    }

    @Test
    void receivedBushelsIgnoresVoids() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().voidTicket("HOME", "A-10442", "reweighed");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Positions.receivedBushels(uow, "CORN", AS_OF)).isEqualTo(d("932.8571"));
        }
    }

    @Test
    void receivedBushelsStopsAtTheDayAskedAbout() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Positions.receivedBushels(uow, "CORN", LocalDate.of(2024, 10, 2)))
                    .isEqualTo(d("0.0000"));
        }
    }

    @Test
    void aCommodityWithNoTicketsIsZero() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Positions.receivedBushels(uow, "SOYB", AS_OF)).isEqualTo(d("0.0000"));
        }
    }

    @Test
    void contractedBushelsIsWhatHasNotArrived() {
        BigDecimal applied;
        BigDecimal outstanding;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            applied = uow.contracts().appliedBushels("F-2024-118");
            outstanding = Positions.contractedBushels(uow, "CORN", AS_OF);
        }
        assertThat(outstanding).isEqualByComparingTo(d("20000.0000").subtract(applied));
    }

    @Test
    void anOverfilledContractDoesNotGoShort() {
        Database empty = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(empty)) {
            uow.contracts().add(aContract("500"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThat(Positions.contractedBushels(uow, "CORN", AS_OF)).isEqualTo(d("0.0000"));
        }
    }

    @Test
    void aClosedWindowDropsOffTheContractedTotal() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Positions.contractedBushels(uow, "CORN", LocalDate.of(2024, 12, 15)))
                    .isEqualTo(d("0.0000"));
        }
    }

    @Test
    void theLineSeparatesStorageFromDeferred() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.storageObligation()).isEqualTo(d("4000.0000"));
        assertThat(line.deferredPrice()).isEqualTo(d("6000.0000"));
    }

    @Test
    void ownedIsWhatIsLeftAfterTheObligations() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.owned()).isEqualByComparingTo(line.onHand().subtract(d("10000.0000")));
    }

    @Test
    void deferredBushelsComeBackIntoTheNetLong() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.netLong())
                .isEqualByComparingTo(line.owned().add(d("6000.0000")).add(line.purchasedForward()));
    }

    @Test
    void shippingTakesBushelsOffWhatIsOnHand() {
        PositionLine before;
        PositionLine after;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            before = Positions.lineFor(uow, "CORN", AS_OF);
            uow.session().persist(aSale());
            uow.flush();
            Shipping.loadOut(uow, "S-2024-0088", "CAR-1", d("56000"), LocalDate.of(2024, 10, 25));
            after = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(after.onHand()).isEqualByComparingTo(before.onHand().subtract(d("1000.0000")));
    }

    @Test
    void onHandNeverGoesNegative() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aSaleOf("100000"));
            uow.flush();
            Shipping.loadOut(uow, "S-2024-0088", "CAR-1", d("5600000"), LocalDate.of(2024, 10, 25));
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.onHand()).isEqualByComparingTo("0");
    }

    @Test
    void anOpenSaleMakesTheHouseShort() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aSaleOf("100000"));
            uow.flush();
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.soldForward()).isEqualTo(d("100000.0000"));
        assertThat(line.isShort()).isTrue();
    }

    @Test
    void aSaleOutsideTheWindowIsNotCounted() {
        PositionLine line;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow later = aSale();
            later.setWindowOpens(LocalDate.of(2025, 1, 1));
            later.setWindowCloses(LocalDate.of(2025, 3, 31));
            uow.session().persist(later);
            uow.flush();
            line = Positions.lineFor(uow, "CORN", AS_OF);
        }
        assertThat(line.soldForward()).isEqualTo(d("0.0000"));
    }

    @Test
    void theReportSkipsCommoditiesWithNothingInThem() {
        PositionReport report;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            report = Positions.buildReport(uow, AS_OF);
        }
        assertThat(report.commodities()).containsExactly("CORN");
    }

    @Test
    void theReportCanBeAskedForNamedCommodities() {
        PositionReport report;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            report = Positions.buildReport(uow, AS_OF, List.of("CORN", "SOYB"));
        }
        assertThat(report.commodities()).containsExactly("CORN");
    }

    @Test
    void theReportIsDated() {
        PositionReport report;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            report = Positions.buildReport(uow, AS_OF);
        }
        assertThat(report.asOf()).isEqualTo(AS_OF);
        assertThat(report.lines()).allSatisfy(line -> assertThat(line.asOf()).isEqualTo(AS_OF));
    }

    @Test
    void theUnhedgedGapIsThePositionLessWhatIsOn() {
        PositionReport report;
        Map<String, BigDecimal> gaps;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            report = Positions.buildReport(uow, AS_OF);
            gaps = Positions.unhedged(report, Map.of("CORN", d("5000")));
        }
        assertThat(gaps.get("CORN"))
                .isEqualByComparingTo(report.byCommodity("CORN").netLong().subtract(d("5000")));
    }

    @Test
    void aCommodityWithNoHedgeShowsTheWholePosition() {
        PositionReport report;
        Map<String, BigDecimal> gaps;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            report = Positions.buildReport(uow, AS_OF);
            gaps = Positions.unhedged(report, Map.of());
        }
        assertThat(gaps.get("CORN"))
                .isEqualByComparingTo(report.byCommodity("CORN").netLong());
    }

    @Test
    void aSiteFilterNarrowsTheLine() {
        PositionLine home;
        PositionLine luray;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            home = Positions.lineFor(uow, "CORN", AS_OF, "HOME");
            luray = Positions.lineFor(uow, "CORN", AS_OF, "LURAY");
        }
        assertThat(home.onHand()).isPositive();
        assertThat(luray.onHand()).isEqualByComparingTo("0");
    }
}
