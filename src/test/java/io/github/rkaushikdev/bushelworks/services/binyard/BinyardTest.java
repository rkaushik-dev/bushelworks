package io.github.rkaushikdev.bushelworks.services.binyard;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Capture;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinReadingRow;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Putting loads into the bins at one site, drawing them out, and watching the cables. */
class BinyardTest {

    static final OffsetDateTime TAKEN = OffsetDateTime.of(2024, 11, 27, 6, 0, 0, 0, ZoneOffset.UTC);
    static final LocalDate TURNED = LocalDate.of(2024, 11, 27);

    static final Map<Factor, BigDecimal> SAMPLE =
            Map.of(Factor.MOISTURE, d("14.0"), Factor.TEST_WEIGHT, d("56.0"), Factor.DAMAGE_TOTAL, d("1.0"));

    static final Map<Factor, BigDecimal> WET =
            Map.of(Factor.MOISTURE, d("18.0"), Factor.TEST_WEIGHT, d("54.0"), Factor.DAMAGE_TOTAL, d("3.0"));

    Database database;

    /** Three aerated bins at HOME: forty thousand, a hundred thousand and a quarter of a million. */
    @BeforeEach
    void yard() {
        database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin("B-01", "40000"));
            uow.session().persist(aBin("B-02", "100000"));
            uow.session().persist(aBin("B-03", "250000"));
            uow.commit();
        }
    }

    static BinRow aBin(String binId, String capacity) {
        BinRow row = new BinRow();
        row.setBinId(binId);
        row.setLocationCode("HOME");
        row.setCapacityBushels(d(capacity));
        row.setAerated(true);
        return row;
    }

    @Test
    void aMissingBinSaysSo() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> BinYard.binAt(uow, "HOME", "B-99"))
                    .isInstanceOf(BinNotFound.class)
                    .hasMessage("no bin B-99 at HOME");
        }
    }

    @Test
    void theYardListsItsBinsInOrder() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(BinYard.binsAt(uow, "HOME"))
                    .extracting(BinRow::getBinId)
                    .containsExactly("B-01", "B-02", "B-03");
        }
    }

    @Test
    void anEmptyBinHasWorkingHeadroom() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(BinYard.workingHeadroom(BinYard.binAt(uow, "HOME", "B-02")))
                    .isEqualTo(d("97000.0000"));
        }
    }

    @Test
    void aLoadGoesIntoTheSmallestEmptyBinThatTakesIt() {
        Placed placed;
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            placed = BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE);
            uow.commit();
            assertThat(log.find("bin.filled"))
                    .singleElement()
                    .asString()
                    .contains("bin=HOME/B-01", "commodity=CORN", "bushels=1000.0000", "holding=1000.0000");
        }
        assertThat(placed.binId()).isEqualTo("B-01");
        assertThat(placed.headroomLeft()).isEqualTo(d("37800.0000"));
    }

    @Test
    void theNextLoadFinishesTheBinAlreadyHoldingCorn() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE);
            Placed placed = BinYard.putAway(uow, "HOME", "CORN", d("500"), SAMPLE);
            uow.commit();
            assertThat(placed.binId()).isEqualTo("B-01");
        }
    }

    @Test
    void aSecondLoadBlendsWithTheFirst() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE, "B-02");
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), WET, "B-02");
            BinRow row = BinYard.binAt(uow, "HOME", "B-02");
            assertThat(row.getMoisture())
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("16.0000")));
            assertThat(row.getTestWeight())
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("55.0000")));
        }
    }

    @Test
    void theBlendIsWeightedByBushels() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("3000"), SAMPLE, "B-02");
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), WET, "B-02");
            assertThat(BinYard.binAt(uow, "HOME", "B-02").getMoisture())
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("15.0000")));
        }
    }

    @Test
    void aFactorTheLoadWasNotTestedForLeavesTheBlendAlone() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE, "B-02");
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), Map.of(Factor.MOISTURE, d("18.0")), "B-02");
            assertThat(BinYard.binAt(uow, "HOME", "B-02").getTestWeight())
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("56.0000")));
        }
    }

    @Test
    void aBinWillNotTakeAnotherCommodity() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE, "B-02");
            assertThatThrownBy(() -> BinYard.putAway(uow, "HOME", "SOYB", d("1000"), SAMPLE, "B-02"))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage("HOME/B-02 holds CORN, not SOYB");
        }
    }

    @Test
    void aBinOutOfServiceWillNotTakeAnything() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.takeOutOfService(uow, "HOME", "B-02", "fan motor", TURNED);
            assertThatThrownBy(() -> BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE, "B-02"))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage("HOME/B-02 is out of service: fan motor");
        }
    }

    @Test
    void aBinOutOfServiceIsNotChosen() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.takeOutOfService(uow, "HOME", "B-01", "fan motor", TURNED);
            assertThat(BinYard.chooseBin(uow, "HOME", "CORN", d("1000")))
                    .map(BinRow::getBinId)
                    .contains("B-02");
        }
    }

    @Test
    void takingABinOutTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.takeOutOfService(uow, "HOME", "B-01", "fan motor", TURNED);
            assertThatThrownBy(() -> BinYard.takeOutOfService(uow, "HOME", "B-01", "again", TURNED.plusDays(1)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void takingABinOutNeedsAReason() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> BinYard.takeOutOfService(uow, "HOME", "B-01", "", TURNED))
                    .isInstanceOf(BinYardError.class);
        }
    }

    @Test
    void aLoadBiggerThanTheYardHasNowhereToGo() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> BinYard.putAway(uow, "HOME", "CORN", d("500000"), SAMPLE))
                    .isInstanceOf(BinYardError.class)
                    .hasMessage("nowhere at HOME will take 500000.0000 bushels of CORN");
        }
    }

    @Test
    void puttingNothingAwayIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> BinYard.putAway(uow, "HOME", "CORN", d("0"), SAMPLE))
                    .isInstanceOf(BinYardError.class);
        }
    }

    @Test
    void aLoadPastTheWorkingFillIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("38000"), SAMPLE, "B-01");
            assertThatThrownBy(() -> BinYard.putAway(uow, "HOME", "CORN", d("2000"), SAMPLE, "B-01"))
                    .isInstanceOf(BinYardError.class)
                    .hasMessage("HOME/B-01: only 800.0000 bushels of room, asked for 2000.0000");
        }
    }

    @Test
    void drawingTakesGrainOut() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("10000"), SAMPLE, "B-02");
            BinRow row = BinYard.drawFrom(uow, "HOME", "B-02", d("4000"));
            assertThat(row.getBushels()).isEqualTo(d("6000.0000"));
            assertThat(row.getMoisture())
                    .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("14.0000")));
        }
    }

    @Test
    void emptyingABinClearsWhatItHeld() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("10000"), SAMPLE, "B-02");
            BinRow row = BinYard.drawFrom(uow, "HOME", "B-02", d("10000"));
            assertThat(row.getCommodityCode()).isEmpty();
            assertThat(row.getMoisture()).isEmpty();
        }
    }

    @Test
    void drawingMoreThanIsThereIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("1000"), SAMPLE, "B-02");
            assertThatThrownBy(() -> BinYard.drawFrom(uow, "HOME", "B-02", d("1001")))
                    .isInstanceOf(BinYardError.class)
                    .hasMessage("HOME/B-02: only 1000.0000 bushels in it, asked for 1001.0000");
        }
    }

    @Test
    void drawingNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> BinYard.drawFrom(uow, "HOME", "B-02", d("0")))
                    .isInstanceOf(BinYardError.class)
                    .hasMessage("HOME/B-02: nothing to draw");
        }
    }

    @Test
    void theYardTotalsWhatIsInIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "CORN", d("10000"), SAMPLE, "B-02");
            BinYard.putAway(uow, "HOME", "SOYB", d("5000"), SAMPLE, "B-03");
            assertThat(BinYard.yardTotal(uow, "HOME")).isEqualTo(d("15000.0000"));
            assertThat(BinYard.yardTotal(uow, "HOME", "CORN")).isEqualTo(d("10000.0000"));
        }
    }

    @Test
    void aReadingIsLogged() {
        BinReadingRow reading;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            reading = BinYard.recordReading(
                    uow,
                    "HOME",
                    "B-02",
                    TAKEN,
                    d("12.4"),
                    ReadingOptions.defaults().withFanRunning(true));
            uow.commit();
        }
        assertThat(reading.getCable()).isEqualTo(1);
        assertThat(reading.isWarm()).isFalse();
    }

    @Test
    void aWarmReadingPutsTheBinOnTheList() {
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.recordReading(uow, "HOME", "B-02", TAKEN, d("24.0"));
            uow.commit();
            assertThat(log.find("bin.warm"))
                    .singleElement()
                    .asString()
                    .contains("bin=HOME/B-02", "cable=1", "temperature=24.0", "fan=no");
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(BinYard.warmBins(uow, "HOME", TAKEN.minusDays(1)))
                    .extracting(BinRow::getBinId)
                    .containsExactly("B-02");
        }
    }

    @Test
    void aCoolYardHasNoWarmBins() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.recordReading(uow, "HOME", "B-02", TAKEN, d("12.4"));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(BinYard.warmBins(uow, "HOME", TAKEN.minusDays(1))).isEmpty();
        }
    }

    @Test
    void anOldWarmReadingFallsOffTheList() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.recordReading(uow, "HOME", "B-02", TAKEN, d("24.0"));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(BinYard.warmBins(uow, "HOME", TAKEN.plusDays(1))).isEmpty();
        }
    }
}
