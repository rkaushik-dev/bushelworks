package io.github.rkaushikdev.bushelworks.reports.position;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PositionReportTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 11, 1);

    static PositionLine.Builder line() {
        return PositionLine.builder()
                .asOf(AS_OF)
                .commodityCode("CORN")
                .locationCode("HOME")
                .onHand(d("1000000"))
                .storageObligation(d("400000"))
                .deferredPrice(d("150000"))
                .purchasedForward(d("250000"))
                .soldForward(d("600000"));
    }

    static PositionReport aReport() {
        PositionReport report = new PositionReport(AS_OF);
        report.add(line().build());
        report.add(line().commodityCode("SOYB")
                .onHand(d("900000"))
                .storageObligation(d("200000"))
                .deferredPrice(d("50000"))
                .purchasedForward(d("100000"))
                .soldForward(d("300000"))
                .build());
        return report;
    }

    static PositionReport aReportOf(PositionLine only) {
        PositionReport report = new PositionReport(AS_OF);
        report.add(only);
        return report;
    }

    @Test
    void bushelsArePrintedWholeWithSeparators() {
        assertThat(PositionBoard.bushels(d("1000000"))).isEqualTo("1,000,000");
        assertThat(PositionBoard.bushels(d("932.8571"))).isEqualTo("933");
    }

    @Test
    void aShortPrintsInBrackets() {
        assertThat(PositionBoard.bushels(d("-50000"))).isEqualTo("(50,000)");
    }

    @Test
    void theBoardFitsItsWidth() {
        String text = PositionBoard.render(aReport());
        assertThat(text.split("\n", -1))
                .allSatisfy(row -> assertThat(row.length()).isLessThanOrEqualTo(PositionBoard.WIDTH));
    }

    @Test
    void theBoardNamesTheDayAndTheHouse() {
        String text = PositionBoard.render(aReport(), "LURAY GRAIN CO");
        assertThat(text).contains("LURAY GRAIN CO");
        assertThat(text).contains("POSITION AS OF 2024-11-01");
    }

    @Test
    void everyCommodityGetsARow() {
        String text = PositionBoard.render(aReport());
        assertThat(text).contains("CORN");
        assertThat(text).contains("SOYB");
    }

    @Test
    void theBoardTotalsWhatTheHouseOwns() {
        assertThat(PositionBoard.render(aReport())).contains("TOTAL OWNED");
    }

    @Test
    void aShortIsCalledOutAtTheBottom() {
        String text =
                PositionBoard.render(aReportOf(line().soldForward(d("2000000")).build()));
        assertThat(text).contains("SHORT:");
        assertThat(text).contains("is short");
    }

    @Test
    void aFlatBoardSaysNothingAboutShorts() {
        assertThat(PositionBoard.render(aReport())).doesNotContain("SHORT:");
    }

    @Test
    void theHedgeSheetRoundsTowardZero() {
        String text = PositionBoard.hedgeSheet(aReport());
        assertThat(text).contains("HEDGE SHEET");
        assertThat(text).contains("CORN");
        assertThat(text).contains("CONTRACTS");
    }

    @Test
    void theHedgeSheetShowsTheResidual() {
        String text = PositionBoard.hedgeSheet(
                aReportOf(line().purchasedForward(d("252400")).build()));
        assertThat(text).contains("2,400");
    }

    @Test
    void aDifferentContractSizeChangesTheLots() {
        PositionReport report = aReportOf(line().build());
        String five = PositionBoard.hedgeSheet(report, d("5000"));
        String one = PositionBoard.hedgeSheet(report, d("1000"));
        assertThat(five).isNotEqualTo(one);
    }

    @Test
    void theCsvHasAHeaderAndARowPerLine() {
        List<String> rows = Arrays.asList(PositionBoard.toCsv(aReport()).strip().split("\n"));
        assertThat(rows.get(0)).startsWith("as_of,commodity_code,location_code");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theCsvKeepsTheUnroundedQuantities() {
        assertThat(PositionBoard.toCsv(aReportOf(line().onHand(d("932.8571")).build())))
                .contains("932.8571");
    }

    @Test
    void theCsvCarriesTheDerivedColumns() {
        String header = PositionBoard.toCsv(aReport()).split("\n")[0];
        assertThat(header).contains("owned");
        assertThat(header).contains("net_long");
    }

    @Test
    void comparingTwoDaysShowsTheMove() {
        List<PositionLine> yesterday = List.of(line().build());
        List<PositionLine> today = List.of(line().purchasedForward(d("300000")).build());
        Map<String, BigDecimal> moves = PositionBoard.compare(yesterday, today);
        assertThat(moves.get("CORN")).isEqualByComparingTo("50000");
    }

    @Test
    void aCommodityThatAppearedOvernightShowsItsWholePosition() {
        Map<String, BigDecimal> moves = PositionBoard.compare(List.of(), List.of(line().build()));
        assertThat(moves.get("CORN")).isEqualByComparingTo(line().build().netLong());
    }

    @Test
    void aCommodityThatWentAwayShowsTheOtherWay() {
        Map<String, BigDecimal> moves = PositionBoard.compare(List.of(line().build()), List.of());
        assertThat(moves.get("CORN"))
                .isEqualByComparingTo(line().build().netLong().negate());
    }

    @Test
    void comparingTwoIdenticalDaysShowsNothingMoved() {
        Map<String, BigDecimal> moves = PositionBoard.compare(List.of(line().build()), List.of(line().build()));
        assertThat(moves.get("CORN")).isEqualByComparingTo("0");
    }

    /** Two corn sites, one of them short by a fraction of a bushel past a round number, and beans. */
    static PositionReport aBusyReport() {
        PositionReport report = aReport();
        report.add(line().locationCode("LURAY").soldForward(d("2000000.5")).build());
        return report;
    }

    @Test
    void theBoardPrintsLineForLine() {
        assertThat(PositionBoard.render(aBusyReport()))
                .isEqualTo(String.join(
                        "\n",
                        "                                       BUSHELWORKS ELEVATOR",
                        "                                    POSITION AS OF 2024-11-01",
                        "==================================================================================================",
                        "COMMODITY   SITE           ON HAND       STORAGE          DP      BOUGHT        SOLD      NET LONG",
                        "--------------------------------------------------------------------------------------------------",
                        "CORN        HOME         1,000,000       400,000     150,000     250,000     600,000       250,000",
                        "CORN        LURAY        1,000,000       400,000     150,000     250,000   2,000,000   (1,150,000)",
                        "SOYB        HOME           900,000       200,000      50,000     100,000     300,000       500,000",
                        "--------------------------------------------------------------------------------------------------",
                        "TOTAL OWNED                    1,550,000",
                        "",
                        "SHORT:",
                        "  CORN at LURAY is short 1,150,000 bushels"));
    }

    @Test
    void theHedgeSheetAndTheCsvPrintLineForLine() {
        assertThat(PositionBoard.hedgeSheet(aBusyReport()))
                .isEqualTo(String.join(
                        "\n",
                        "                                      HEDGE SHEET 2024-11-01",
                        "==================================================================================================",
                        "COMMODITY           NET LONG     CONTRACTS        RESIDUAL",
                        "--------------------------------------------------------------------------------------------------",
                        "CORN               (900,000)          -180              -0",
                        "SOYB                 500,000           100               0"));
        assertThat(PositionBoard.toCsv(aBusyReport()))
                .isEqualTo(String.join(
                        "\n",
                        "as_of,commodity_code,location_code,on_hand,storage_obligation,deferred_price,purchased_forward,"
                                + "sold_forward,in_transit,owned,net_long",
                        "2024-11-01,CORN,HOME,1000000,400000,150000,250000,600000,0,450000.0000,250000.0000",
                        "2024-11-01,CORN,LURAY,1000000,400000,150000,250000,2000000.5,0,450000.0000,-1150000.5000",
                        "2024-11-01,SOYB,HOME,900000,200000,50000,100000,300000,0,650000.0000,500000.0000",
                        ""));
    }
}
