package io.github.rkaushikdev.bushelworks.services.scalehouse;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Capture;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScalehouseTest {

    static final DeliveryWindow WINDOW = new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30));

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    static PurchaseContract.Builder aContract() {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(WINDOW)
                .flatPrice(d("4.6200"));
    }

    @Test
    void aLoadWithNoContractsIsRecordedAndLeftOpen() {
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.ticketNumber()).isEqualTo("A-10441");
        assertThat(recorded.applications()).isEmpty();
        assertThat(recorded.load().dryBushels()).isEqualTo(d("897.6324"));
    }

    @Test
    void aLoadLandsOnAnOpenContract() {
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.contractNumbers()).containsExactly("F-2024-118");
        assertThat(recorded.contractedBushels()).isEqualTo(d("897.6324"));
        assertThat(recorded.openBushels()).isEqualByComparingTo("0");
    }

    @Test
    void theApplicationRowIsWritten() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.contracts().appliedBushels("F-2024-118")).isEqualTo(d("897.6324"));
        }
    }

    @Test
    void aSecondLoadStacksOnTheSameContract() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.contracts().appliedBushels("F-2024-118")).isEqualTo(d("1795.2648"));
        }
    }

    @Test
    void aContractFillsAndTheRestGoesToStorage() {
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().bushels(d("500")).build());
            recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.contractedBushels()).isEqualTo(d("510.0000"));
        assertThat(recorded.openBushels()).isEqualTo(d("387.6324"));
    }

    @Test
    void aNamedContractIsTheOnlyOneConsidered() {
        ScaleTicket named = cornTicket.toBuilder()
                .disposition(Disposition.CONTRACT)
                .contractNumber("F-2024-119")
                .build();
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            uow.contracts()
                    .add(aContract().number("F-2024-119").flatPrice(d("4.9000")).build());
            recorded = ScaleHouse.recordLoad(uow, named, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.contractNumbers()).containsExactly("F-2024-119");
    }

    @Test
    void namingAContractThatIsNotOpenIsRefused() {
        ScaleTicket named = cornTicket.toBuilder()
                .disposition(Disposition.CONTRACT)
                .contractNumber("F-2024-999")
                .build();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> ScaleHouse.recordLoad(uow, named, "MERRICK", rules))
                    .isInstanceOf(ScaleHouseError.class);
        }
    }

    @Test
    void aStorageLoadIsNotAppliedToAnything() {
        ScaleTicket stored =
                cornTicket.toBuilder().disposition(Disposition.STORE).build();
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            recorded = ScaleHouse.recordLoad(uow, stored, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.applications()).isEmpty();
    }

    @Test
    void anotherProducersContractIsNotTouched() {
        RecordedLoad recorded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().partyId("HOLLIS").build());
            recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(recorded.applications()).isEmpty();
    }

    @Test
    void aLoadPricedOffOneContract() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().build());
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThat(ScaleHouse.priceFor(uow, recorded)).isEqualTo(d("4.6200"));
        }
    }

    @Test
    void aSplitLoadBlendsTheContractAndTheSpotPrice() {
        BigDecimal blended;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().bushels(d("500")).build());
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            blended = ScaleHouse.priceFor(uow, recorded, d("4.3500"));
        }
        assertThat(blended).isGreaterThan(d("4.3500")).isLessThan(d("4.6200"));
    }

    @Test
    void openBushelsWithNoSpotPriceIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract().bushels(d("500")).build());
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThatThrownBy(() -> ScaleHouse.priceFor(uow, recorded)).isInstanceOf(ScaleHouseError.class);
        }
    }

    @Test
    void anUnappliedLoadPricesAtSpot() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThat(ScaleHouse.priceFor(uow, recorded, d("4.3500"))).isEqualTo(d("4.3500"));
        }
    }

    @Test
    void anUnappliedLoadWithNoSpotPriceIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThatThrownBy(() -> ScaleHouse.priceFor(uow, recorded)).isInstanceOf(ScaleHouseError.class);
        }
    }

    @Test
    void recordingNeedsAnOpenUnitOfWork() {
        UnitOfWork uow = new UnitOfWork(database);
        assertThatThrownBy(() -> ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void theTouchScreenListsOnlyWhatTheSiteCanSettle() {
        List<String> handled = ScaleHouse.commoditiesHandled(rules, LocalDate.of(2024, 10, 3), "HOME");
        assertThat(handled).containsExactlyInAnyOrder("CORN", "HRW", "SOYB");
        assertThat(handled).doesNotContain("MILO");
    }

    @Test
    void recordingALoadLogsItAsOneLine() {
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            assertThat(log.find("ticket.recorded"))
                    .singleElement()
                    .asString()
                    .contains("ticket=A-10441", "party=MERRICK", "dry_bushels=897.6324", "grade=1", "contracts=-");
            assertThat(log.find("ticket.sample_grade")).isEmpty();
        }
    }
}
