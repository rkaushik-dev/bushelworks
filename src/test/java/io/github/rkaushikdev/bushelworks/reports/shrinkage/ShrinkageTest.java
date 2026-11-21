package io.github.rkaushikdev.bushelworks.reports.shrinkage;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.shrinkage.Shrinkage.againstTheHouse;
import static io.github.rkaushikdev.bushelworks.reports.shrinkage.Shrinkage.render;
import static io.github.rkaushikdev.bushelworks.reports.shrinkage.Shrinkage.toCsv;
import static io.github.rkaushikdev.bushelworks.reports.shrinkage.Shrinkage.totalGain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShrinkageTest {

    static final LocalDate OPENS = LocalDate.of(2024, 9, 1);
    static final LocalDate CLOSES = LocalDate.of(2024, 11, 30);

    static ShrinkPeriod.Builder aPeriod() {
        return ShrinkPeriod.builder()
                .commodityCode("CORN")
                .locationCode("HOME")
                .opens(OPENS)
                .closes(CLOSES)
                .wetBushelsIn(d("1000000"))
                .shrinkCharged(d("30000"))
                .measuredLoss(d("26000"))
                .driedBushels(d("600000"));
    }

    @Test
    void aPeriodThatClosesFirstIsRefused() {
        assertThatThrownBy(() -> aPeriod().opens(CLOSES).closes(OPENS).build())
                .isInstanceOf(ShrinkageError.class)
                .hasMessage("CORN: the period closes before it opens");
    }

    @Test
    void negativeBushelsAreRefused() {
        assertThatThrownBy(() -> aPeriod().wetBushelsIn(d("-1")).build())
                .isInstanceOf(ShrinkageError.class)
                .hasMessage("CORN: negative bushels");
        assertThatThrownBy(() -> aPeriod().shrinkCharged(d("-1")).build()).isInstanceOf(ShrinkageError.class);
    }

    @Test
    void theGainIsChargedLessMeasured() {
        assertThat(aPeriod().build().gain()).isEqualTo(d("4000.0000"));
    }

    @Test
    void aHouseLosingShrinkShowsANegativeGain() {
        ShrinkPeriod period = aPeriod().measuredLoss(d("34000")).build();
        assertThat(period.gain()).isEqualTo(d("-4000.0000"));
        assertThat(period.direction()).isEqualTo("against the house");
    }

    @Test
    void aFlatPeriodSaysSo() {
        assertThat(aPeriod().measuredLoss(d("30000")).build().direction()).isEqualTo("flat");
    }

    @Test
    void thePercentagesAreOfTheWetBushels() {
        ShrinkPeriod period = aPeriod().build();
        assertThat(period.chargedPercent()).isEqualTo(d("3.0000"));
        assertThat(period.measuredPercent()).isEqualTo(d("2.6000"));
    }

    @Test
    void aPeriodWithNothingInItHasNoPercentages() {
        ShrinkPeriod period = aPeriod()
                .wetBushelsIn(d("0"))
                .shrinkCharged(d("0"))
                .measuredLoss(d("0"))
                .build();
        assertThat(period.chargedPercent()).isEqualTo(d("0.0000"));
        assertThat(period.measuredPercent()).isEqualTo(d("0.0000"));
        assertThat(period.percentDried()).isEqualTo(d("0.0"));
        assertThat(period.isNotable()).isFalse();
    }

    @Test
    void aSmallGainIsNotNotable() {
        assertThat(aPeriod().measuredLoss(d("29900")).build().isNotable()).isFalse();
    }

    @Test
    void aLargeGainIsNotable() {
        assertThat(aPeriod().build().isNotable()).isTrue();
        assertThat(Shrinkage.NOTABLE).isEqualTo(d("0.002"));
    }

    @Test
    void aLargeLossIsNotableToo() {
        assertThat(aPeriod().measuredLoss(d("35000")).build().isNotable()).isTrue();
    }

    @Test
    void thePercentageDriedIsReported() {
        assertThat(aPeriod().build().percentDried()).isEqualTo(d("60.0"));
    }

    @Test
    void nothingIsDriedUntilSaid() {
        ShrinkPeriod period = ShrinkPeriod.builder()
                .commodityCode("SOYB")
                .locationCode("HOME")
                .opens(OPENS)
                .closes(CLOSES)
                .wetBushelsIn(d("1000"))
                .shrinkCharged(d("10"))
                .measuredLoss(d("10"))
                .build();
        assertThat(period.driedBushels()).isEqualTo(d("0"));
        assertThat(period.percentDried()).isEqualTo(d("0.0"));
    }

    @Test
    void theSheetListsTheLines() {
        String text = render(
                List.of(aPeriod().build(), aPeriod().commodityCode("SOYB").build()));
        assertThat(text).contains("CORN").contains("SOYB").contains("2 lines");
    }

    @Test
    void theSheetFitsItsWidth() {
        String text = render(List.of(aPeriod().build()));
        assertThat(text.split("\n", -1))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void theSheetExplainsTheNotableLines() {
        String text = render(List.of(aPeriod().build()));
        assertThat(text).contains("1 worth explaining").contains("in the house's favour");
    }

    @Test
    void aQuietPeriodNeedsNoExplanation() {
        assertThat(render(List.of(aPeriod().measuredLoss(d("29900")).build()))).contains("0 worth explaining");
    }

    /** Three periods whose figures round on a half, and two that round to nothing below zero. */
    static List<ShrinkPeriod> aRoundingBook() {
        ShrinkPeriod corn = aPeriod()
                .wetBushelsIn(d("1000000.5"))
                .measuredLoss(d("26000.5"))
                .build();
        ShrinkPeriod soybeans = corn.toBuilder()
                .commodityCode("SOYB")
                .locationCode("LURAY")
                .measuredLoss(d("30000.3"))
                .wetBushelsIn(d("250000"))
                .build();
        ShrinkPeriod milo = corn.toBuilder()
                .commodityCode("MILO")
                .measuredLoss(d("-0.2"))
                .shrinkCharged(d("0"))
                .wetBushelsIn(d("0"))
                .build();
        return List.of(corn, soybeans, milo);
    }

    @Test
    void theSheetRoundsHalvesToEvenAndKeepsTheSignOfANegativeNothing() {
        assertThat(render(aRoundingBook()))
                .isEqualTo(String.join(
                        "\n",
                        "                              BUSHELWORKS ELEVATOR",
                        "                             SHRINK RECONCILIATION",
                        "=".repeat(80),
                        "CROP   SITE            WET IN     CHARGED    MEASURED        GAIN   CHG %  MEA %",
                        "-".repeat(80),
                        "CORN   HOME           1000000       30000       26000        4000    3.00   2.60",
                        "MILO   HOME                 0           0          -0           0    0.00   0.00",
                        "SOYB   LURAY           250000       30000       30000          -0   12.00  12.00",
                        "-".repeat(80),
                        "3 lines, 1 worth explaining",
                        "  CORN at HOME: 4000 bushels in the house's favour"));
    }

    @Test
    void theCsvHasARowPerLine() {
        String text =
                toCsv(List.of(aPeriod().build(), aPeriod().commodityCode("SOYB").build()));
        String[] rows = text.strip().split("\n");
        assertThat(rows[0]).startsWith("commodity_code,location_code");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theCsvSaysWhichLinesAreNotable() {
        assertThat(toCsv(List.of(aPeriod().build()))).contains(",yes");
    }

    @Test
    void theCsvKeepsThePlacesItWasGiven() {
        assertThat(toCsv(aRoundingBook()))
                .isEqualTo("commodity_code,location_code,opens,closes,wet_bushels_in,dried_bushels,shrink_charged,"
                        + "measured_loss,gain,charged_percent,measured_percent,is_notable\n"
                        + "CORN,HOME,2024-09-01,2024-11-30,1000000.5,600000,30000,26000.5,3999.5000,3.0000,2.6000,yes\n"
                        + "MILO,HOME,2024-09-01,2024-11-30,0,600000,0,-0.2,0.2000,0.0000,0.0000,no\n"
                        + "SOYB,LURAY,2024-09-01,2024-11-30,250000,600000,30000,30000.3,-0.3000,12.0000,12.0001,no\n");
    }

    @Test
    void theTotalGainAddsTheLines() {
        List<ShrinkPeriod> periods =
                List.of(aPeriod().build(), aPeriod().commodityCode("SOYB").build());
        assertThat(totalGain(periods)).isEqualTo(d("8000.0000"));
    }

    @Test
    void theLinesAgainstTheHouseArePickedOut() {
        ShrinkPeriod losing =
                aPeriod().commodityCode("SOYB").measuredLoss(d("34000")).build();
        List<ShrinkPeriod> found = againstTheHouse(List.of(aPeriod().build(), losing));
        assertThat(found).extracting(ShrinkPeriod::commodityCode).containsExactly("SOYB");
    }

    @Test
    void aBookAllInTheHousesFavourHasNoneAgainst() {
        assertThat(againstTheHouse(List.of(aPeriod().build()))).isEmpty();
    }
}
