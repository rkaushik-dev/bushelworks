package io.github.rkaushikdev.bushelworks.devices.moisture;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.devices.moisture.MoistureMeters.checkCalibration;
import static io.github.rkaushikdev.bushelworks.devices.moisture.MoistureMeters.parseBatch;
import static io.github.rkaushikdev.bushelworks.devices.moisture.MoistureMeters.parseReport;
import static io.github.rkaushikdev.bushelworks.devices.moisture.MoistureMeters.temperatureWarning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoistureMeterTest {

    static final String REPORT =
            """
            GAC2500-UGMA
            SAMPLE: A-10441-2
            CALIB: YELLOW CORN 12.4
            MOIST: 18.2 %
            TEST WT: 56.5 LB/BU
            TEMP: 14.2 C
            END
            """;

    @Test
    void aReportReadsIntoAReading() {
        MeterReading reading = parseReport(REPORT);
        assertThat(reading.sampleId()).isEqualTo("A-10441-2");
        assertThat(reading.commodityCode()).isEqualTo("CORN");
        assertThat(reading.moisture()).isEqualTo(d("18.2"));
        assertThat(reading.testWeight()).contains(d("56.5"));
        assertThat(reading.temperature()).contains(d("14.2"));
        assertThat(reading.instrument()).isEqualTo("GAC2500-UGMA");
    }

    @Test
    void theCalibrationNameIsKeptWithoutItsVersion() {
        assertThat(parseReport(REPORT).calibration()).isEqualTo("YELLOW CORN");
    }

    @Test
    void theReadingBecomesGradeFactors() {
        Map<Factor, BigDecimal> sample = parseReport(REPORT).asSample();
        assertThat(sample.get(Factor.MOISTURE)).isEqualTo(d("18.2"));
        assertThat(sample.get(Factor.TEST_WEIGHT)).isEqualTo(d("56.5"));
    }

    @Test
    void aReportWithoutATestWeightStillReads() {
        MeterReading reading = parseReport(REPORT.replace("TEST WT: 56.5 LB/BU\n", ""));
        assertThat(reading.testWeight()).isEmpty();
        assertThat(reading.asSample()).doesNotContainKey(Factor.TEST_WEIGHT);
    }

    @Test
    void aSampleIdWithAColonSurvives() {
        String text = REPORT.replace("A-10441-2", "A-10441:2");
        assertThat(parseReport(text).sampleId()).isEqualTo("A-10441:2");
    }

    @Test
    void aTruncatedReportIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("END\n", ""))).isInstanceOf(MeterError.class);
    }

    @Test
    void anEmptyReportIsRefused() {
        assertThatThrownBy(() -> parseReport("")).isInstanceOf(MeterError.class);
    }

    @Test
    void aReportMissingTheMoistureIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("MOIST: 18.2 %\n", "")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void aReportMissingTheCalibrationIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("CALIB: YELLOW CORN 12.4\n", "")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void aLineWithNoColonIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("TEMP: 14.2 C", "TEMP 14.2 C")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void anUnknownCalibrationIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("YELLOW CORN", "CANOLA")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void aMoistureThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("18.2 %", "WET %"))).isInstanceOf(MeterError.class);
    }

    @Test
    void anImpossibleMoistureIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("18.2 %", "84.0 %")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void anImplausibleTestWeightIsRefused() {
        assertThatThrownBy(() -> parseReport(REPORT.replace("56.5 LB/BU", "12.0 LB/BU")))
                .isInstanceOf(MeterError.class);
    }

    @Test
    void theReadingKnowsWhichCommodityItBelongsTo() {
        MeterReading reading = parseReport(REPORT);
        assertThat(reading.matches("CORN")).isTrue();
        assertThat(reading.matches("SOYB")).isFalse();
    }

    @Test
    void theWrongCalibrationIsCaught() {
        MeterReading reading = parseReport(REPORT);
        assertThatCode(() -> checkCalibration(reading, "CORN")).doesNotThrowAnyException();
        assertThatThrownBy(() -> checkCalibration(reading, "SOYB"))
                .isInstanceOf(MeterError.class)
                .hasMessageContaining("YELLOW CORN");
    }

    @Test
    void aBatchReadsEveryReport() {
        String text = REPORT + REPORT.replace("A-10441-2", "A-10442-1");
        assertThat(parseBatch(text)).extracting(MeterReading::sampleId).containsExactly("A-10441-2", "A-10442-1");
    }

    @Test
    void aBadReportInABatchDoesNotCostTheOthers() {
        String bad = REPORT.replace("YELLOW CORN", "CANOLA");
        String text = REPORT + bad + REPORT.replace("A-10441-2", "A-10443-1");
        assertThat(parseBatch(text)).extracting(MeterReading::sampleId).containsExactly("A-10441-2", "A-10443-1");
    }

    @Test
    void aSpoolWithCarriageReturnsAndFormFeedsReadsTheSame() {
        String printed = REPORT.replace("\n", "\r\n") + "\f" + REPORT.replace("A-10441-2", "A-10442-1");
        assertThat(parseBatch(printed)).extracting(MeterReading::sampleId).containsExactly("A-10441-2", "A-10442-1");
        assertThat(MoistureMeters.splitLines("a\rb\u2028c\n")).containsExactly("a", "b", "c");
    }

    @Test
    void anEmptySpoolReadsAsNothing() {
        assertThat(parseBatch("")).isEmpty();
    }

    @Test
    void aColdSampleIsFlagged() {
        MeterReading reading = parseReport(REPORT.replace("14.2 C", "-3.0 C"));
        assertThat(temperatureWarning(reading))
                .hasValueSatisfying(text -> assertThat(text).contains("too cold"));
    }

    @Test
    void aWarmSampleIsFlagged() {
        MeterReading reading = parseReport(REPORT.replace("14.2 C", "38.0 C"));
        assertThat(temperatureWarning(reading))
                .hasValueSatisfying(text -> assertThat(text).contains("too warm"));
    }

    @Test
    void aSampleInRangeIsNotFlagged() {
        assertThat(temperatureWarning(parseReport(REPORT))).isEmpty();
    }

    @Test
    void aReportWithNoTemperatureCannotBeFlagged() {
        String text = REPORT.replace("TEMP: 14.2 C\n", "");
        assertThat(temperatureWarning(parseReport(text))).isEmpty();
    }

    @Test
    void aSoybeanReportMapsToTheRightCode() {
        String text = REPORT.replace("YELLOW CORN 12.4", "SOYBEANS 11.0").replace("18.2 %", "12.8 %");
        assertThat(parseReport(text).commodityCode()).isEqualTo("SOYB");
    }
}
