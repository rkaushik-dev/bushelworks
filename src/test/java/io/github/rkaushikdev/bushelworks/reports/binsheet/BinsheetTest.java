package io.github.rkaushikdev.bushelworks.reports.binsheet;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import io.github.rkaushikdev.bushelworks.services.binyard.BinYard;
import io.github.rkaushikdev.bushelworks.services.binyard.ReadingOptions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The clipboard sheet for a yard of two bins, one of them a fifth full of corn. */
class BinsheetTest {

    static final OffsetDateTime TAKEN = OffsetDateTime.of(2024, 11, 27, 6, 0, 0, 0, ZoneOffset.UTC);
    static final LocalDate TURNED = LocalDate.of(2024, 11, 27);

    static final Map<Factor, BigDecimal> SAMPLE =
            Map.of(Factor.MOISTURE, d("14.0"), Factor.TEST_WEIGHT, d("56.0"), Factor.DAMAGE_TOTAL, d("1.0"));

    Database database;

    /** B-01 empty at forty thousand, B-02 holding twenty thousand of corn out of a hundred thousand. */
    @BeforeEach
    void yard() {
        database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin("B-01", "40000"));
            uow.session().persist(aBin("B-02", "100000"));
            uow.flush();
            BinYard.putAway(uow, "HOME", "CORN", d("20000"), SAMPLE, "B-02");
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

    List<BinLine> sheet() {
        return sheet(null);
    }

    List<BinLine> sheet(OffsetDateTime since) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return BinYard.binsAt(uow, "HOME").stream()
                    .map(row -> BinSheet.lineFor(row, since))
                    .toList();
        }
    }

    Map<String, BinLine> byBin(List<BinLine> lines) {
        return lines.stream().collect(Collectors.toMap(BinLine::binId, Function.identity()));
    }

    void reading(String temperature, ReadingOptions options) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.recordReading(uow, "HOME", "B-02", TAKEN, d(temperature), options);
            uow.commit();
        }
    }

    void takeB01Out() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.takeOutOfService(uow, "HOME", "B-01", "fan motor", TURNED);
            uow.commit();
        }
    }

    @Test
    void anEmptyBinShowsNoCrop() {
        BinLine line = byBin(sheet()).get("B-01");
        assertThat(line.commodityCode()).isEmpty();
        assertThat(line.bushels()).isEqualByComparingTo(d("0.0000"));
    }

    @Test
    void aFilledBinShowsWhatIsInIt() {
        BinLine line = byBin(sheet()).get("B-02");
        assertThat(line.commodityCode()).isEqualTo("CORN");
        assertThat(line.moisture())
                .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("14.0000")));
    }

    @Test
    void thePercentageFullIsOfTheWholeBin() {
        assertThat(byBin(sheet()).get("B-02").percentFull()).isEqualTo(d("20.0"));
    }

    @Test
    void theRoomLeftIsToTheWorkingFill() {
        assertThat(byBin(sheet()).get("B-02").headroom()).isEqualByComparingTo(d("77000.0000"));
    }

    @Test
    void aBinWithNoReadingsHasNoTemperature() {
        assertThat(sheet()).allMatch(line -> line.warmest().isEmpty());
        assertThat(sheet()).noneMatch(BinLine::isWarm);
    }

    @Test
    void theWarmestReadingIsTheOneShown() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.recordReading(uow, "HOME", "B-02", TAKEN, d("12.0"));
            BinYard.recordReading(
                    uow,
                    "HOME",
                    "B-02",
                    TAKEN.plusHours(6),
                    d("24.0"),
                    ReadingOptions.defaults().withCable(2));
            uow.commit();
        }
        BinLine line = byBin(sheet()).get("B-02");
        assertThat(line.warmest()).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(d("24.0000")));
        assertThat(line.isWarm()).isTrue();
    }

    @Test
    void aWarmBinWithTheFanOffNeedsAttention() {
        reading("24.0", ReadingOptions.defaults().withFanRunning(false));
        assertThat(byBin(sheet()).get("B-02").needsAttention()).isTrue();
    }

    @Test
    void aWarmBinWithTheFanOnDoesNot() {
        reading("24.0", ReadingOptions.defaults().withFanRunning(true));
        BinLine line = byBin(sheet()).get("B-02");
        assertThat(line.isWarm()).isTrue();
        assertThat(line.needsAttention()).isFalse();
    }

    @Test
    void olderReadingsCanBeLeftOff() {
        reading("24.0", ReadingOptions.defaults());
        assertThat(byBin(sheet(TAKEN.plusDays(1))).get("B-02").warmest()).isEmpty();
    }

    @Test
    void theSheetFitsItsWidth() {
        String text = BinSheet.render(sheet(), "HOME");
        assertThat(Arrays.asList(text.split("\n", -1))).allMatch(line -> line.length() <= BinSheet.WIDTH);
    }

    @Test
    void theSheetNamesTheSiteAndTheBins() {
        String text = BinSheet.render(sheet(), "HOME");
        assertThat(text).contains("BIN SHEET  HOME", "B-01", "B-02");
    }

    @Test
    void theSheetTotalsTheYard() {
        String text = BinSheet.render(sheet(), "HOME");
        assertThat(text).contains("TOTAL");
        assertThat(text.contains("20,000") || text.contains("20000")).isTrue();
    }

    @Test
    void aBinOutOfServiceIsMarked() {
        takeB01Out();
        assertThat(BinSheet.render(sheet(), "HOME")).contains("OUT");
    }

    @Test
    void aBinThatNeedsLookingAtIsCalledOut() {
        reading("26.0", ReadingOptions.defaults());
        assertThat(BinSheet.render(sheet(), "HOME")).contains("LOOK AT TODAY: B-02");
    }

    @Test
    void aQuietYardCallsNothingOut() {
        assertThat(BinSheet.render(sheet(), "HOME")).doesNotContain("LOOK AT TODAY");
    }

    @Test
    void theCsvHasARowPerBin() {
        List<String> rows = List.of(BinSheet.toCsv(sheet()).strip().split("\n"));
        assertThat(rows.get(0)).startsWith("bin_id,commodity_code");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theCsvLeavesAnUnmeasuredFactorBlank() {
        assertThat(BinSheet.toCsv(sheet())).contains(",,");
    }

    @Test
    void theRoomForACommoditySkipsTheBinsHoldingSomethingElse() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinYard.putAway(uow, "HOME", "SOYB", d("1000"), SAMPLE, "B-01");
            uow.commit();
        }
        assertThat(BinSheet.roomFor(sheet(), "CORN")).isEqualByComparingTo(d("77000.0000"));
    }

    @Test
    void theRoomSkipsABinOutOfService() {
        takeB01Out();
        assertThat(BinSheet.roomFor(sheet(), "CORN")).isEqualByComparingTo(d("77000.0000"));
    }

    /** Two lines built by hand: an empty bin out of service just below freezing, and a warm one. */
    static List<BinLine> handMade() {
        return List.of(
                new BinLine(
                        "B-02",
                        "CORN",
                        d("20000.0000"),
                        d("100000.0000"),
                        Optional.of(d("14.0000")),
                        Optional.of(d("56.0000")),
                        d("77000.000000"),
                        Optional.of(d("24.0000")),
                        false,
                        false),
                new BinLine(
                        "B-01",
                        "",
                        d("0.0000"),
                        d("40000.0000"),
                        Optional.empty(),
                        Optional.empty(),
                        d("38800.000000"),
                        Optional.of(d("-0.4000")),
                        true,
                        true));
    }

    @Test
    void theSheetIsLaidOutColumnByColumn() {
        String rule = "-".repeat(96);
        assertThat(BinSheet.render(handMade(), "HOME"))
                .isEqualTo(String.join(
                        "\n",
                        " ".repeat(38) + "BUSHELWORKS ELEVATOR",
                        " ".repeat(40) + "BIN SHEET  HOME",
                        "=".repeat(96),
                        "BIN     CROP        BUSHELS    FULL   MOIST  TEST WT        ROOM    WARM   FAN      NOTE",
                        rule,
                        "B-01    -                 0    0.0%       -        -       38800      -0   yes       OUT",
                        "B-02    CORN          20000   20.0%      14       56       77000      24    no      LOOK",
                        rule,
                        "TOTAL                 20000                               115800",
                        "",
                        "LOOK AT TODAY: B-02"));
    }

    @Test
    void theCsvKeepsThePlacesEachFigureWasKeptAt() {
        assertThat(BinSheet.toCsv(handMade()))
                .isEqualTo("bin_id,commodity_code,bushels,capacity,percent_full,moisture,test_weight,headroom,"
                        + "warmest,fan_running,out_of_service,needs_attention\n"
                        + "B-01,,0.0000,40000.0000,0.0,,,38800.000000,-0.4000,yes,yes,no\n"
                        + "B-02,CORN,20000.0000,100000.0000,20.0,14.0000,56.0000,77000.000000,24.0000,no,no,yes\n");
    }
}
