package io.github.rkaushikdev.bushelworks.domain.certification;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CertificationTest {

    static final LocalDate TESTED = LocalDate.of(2024, 8, 15);

    static ScaleTest.Builder aTest() {
        return ScaleTest.builder()
                .scaleId("SCALE-1")
                .locationCode("HOME")
                .testedOn(TESTED)
                .testLoadPounds(d("60000"))
                .indicatedPounds(d("60000"))
                .testedBy("KS Weights and Measures")
                .certificateNumber("WM-2024-0912");
    }

    static ScaleLog aLog() {
        ScaleLog log = new ScaleLog("SCALE-1", "HOME");
        log.record(aTest().build());
        return log;
    }

    static ScaleTest aFailure() {
        return aTest().testedOn(LocalDate.of(2024, 11, 1))
                .indicatedPounds(d("59800"))
                .certificateNumber(null)
                .result(Result.FAIL)
                .build();
    }

    @Test
    void aTestNeedsAKnownLoadAndATester() {
        assertThatThrownBy(() -> aTest().testLoadPounds(d("0")).build()).isInstanceOf(CertificationError.class);
        assertThatThrownBy(() -> aTest().testedBy("").build()).isInstanceOf(CertificationError.class);
    }

    @Test
    void aPerfectTestHasNoError() {
        ScaleTest test = aTest().build();
        assertThat(test.errorPounds()).isEqualByComparingTo(d("0"));
        assertThat(test.isWithinTolerance()).isTrue();
    }

    @Test
    void aScaleReadingHeavyShowsAPositiveError() {
        ScaleTest test = aTest().indicatedPounds(d("60040")).build();
        assertThat(test.errorPounds()).isEqualTo(d("40"));
        assertThat(test.errorFraction()).isPositive();
    }

    @Test
    void aScaleReadingLightShowsANegativeError() {
        assertThat(aTest().indicatedPounds(d("59800")).build().errorPounds()).isEqualTo(d("-200"));
    }

    @Test
    void aSmallErrorIsInsideTolerance() {
        assertThat(aTest().indicatedPounds(d("60040")).build().isWithinTolerance())
                .isTrue();
        assertThat(ScaleTest.TOLERANCE).isEqualByComparingTo(d("0.001"));
    }

    @Test
    void aLargeErrorIsOutsideTolerance() {
        assertThat(aTest().indicatedPounds(d("59800")).build().isWithinTolerance())
                .isFalse();
    }

    @Test
    void anOfficialTestCarriesACertificate() {
        assertThat(aTest().build().isOfficial()).isTrue();
        assertThat(aTest().certificateNumber(null).build().isOfficial()).isFalse();
    }

    @Test
    void aCertificateStandsForAYear() {
        ScaleTest test = aTest().build();
        assertThat(test.expiresOn()).contains(TESTED.plusDays(ScaleTest.CERTIFICATION_LIFE_DAYS));
        assertThat(test.isCurrentOn(LocalDate.of(2024, 10, 3))).isTrue();
        assertThat(test.isCurrentOn(LocalDate.of(2026, 1, 1))).isFalse();
    }

    @Test
    void aHouseTestHasNoExpiry() {
        assertThat(aTest().certificateNumber(null).build().expiresOn()).isEmpty();
        assertThat(aTest().certificateNumber(null).build().isCurrentOn(TESTED)).isFalse();
    }

    @Test
    void aTestOfAnotherScaleIsRefused() {
        ScaleLog log = aLog();
        assertThatThrownBy(() -> log.record(aTest().scaleId("SCALE-2").build())).isInstanceOf(CertificationError.class);
    }

    @Test
    void theLogKeepsItsTestsInOrder() {
        ScaleLog log = aLog();
        log.record(aTest().testedOn(LocalDate.of(2024, 6, 1))
                .certificateNumber(null)
                .build());
        assertThat(log.tests()).extracting(ScaleTest::testedOn).containsExactly(LocalDate.of(2024, 6, 1), TESTED);
    }

    @Test
    void theLatestTestIsTheMostRecent() {
        ScaleLog log = aLog();
        log.record(aTest().testedOn(LocalDate.of(2024, 11, 1))
                .certificateNumber(null)
                .build());
        assertThat(log.latest().map(ScaleTest::testedOn)).contains(LocalDate.of(2024, 11, 1));
    }

    @Test
    void anEmptyLogHasNoLatest() {
        assertThat(new ScaleLog("S", "HOME").latest()).isEmpty();
    }

    @Test
    void theLastGoodTestSkipsTheFailures() {
        ScaleLog log = aLog();
        log.record(aFailure());
        assertThat(log.lastGoodBefore(LocalDate.of(2024, 12, 1)).map(ScaleTest::testedOn))
                .contains(TESTED);
    }

    @Test
    void aLogWithNoGoodTestHasNone() {
        ScaleLog log = new ScaleLog("SCALE-1", "HOME");
        log.record(aTest().indicatedPounds(d("59800")).build());
        assertThat(log.lastGoodBefore(LocalDate.of(2024, 12, 1))).isEmpty();
    }

    @Test
    void aScaleIsCertifiedWhileTheCertificateStands() {
        assertThat(aLog().certifiedOn(LocalDate.of(2024, 10, 3))).isTrue();
        assertThat(aLog().certifiedOn(LocalDate.of(2026, 1, 1))).isFalse();
    }

    @Test
    void aFailurePutsTheSpanSinceTheLastGoodTestInQuestion() {
        ScaleLog log = aLog();
        ScaleTest failed = aFailure();
        log.record(failed);
        assertThat(log.suspectWindow(failed))
                .contains(new ScaleLog.Window(TESTED.plusDays(1), LocalDate.of(2024, 11, 1)));
    }

    @Test
    void aPassingTestPutsNothingInQuestion() {
        ScaleLog log = aLog();
        assertThat(log.suspectWindow(aLog().latest().orElseThrow())).isEmpty();
    }

    @Test
    void aFailureWithNoGoodTestBeforeItSaysNothing() {
        ScaleLog log = new ScaleLog("SCALE-1", "HOME");
        ScaleTest failed = aTest().indicatedPounds(d("59800")).build();
        log.record(failed);
        assertThat(log.suspectWindow(failed)).isEmpty();
    }

    @Test
    void aScaleReadingLightUnderpaidAndTheCorrectionIsPositive() {
        ScaleTest test = aTest().indicatedPounds(d("59800")).build();
        assertThat(Certification.correctionFor(test, d("52240"))).isEqualTo(d("174"));
    }

    @Test
    void aScaleReadingHeavyOverpaidAndTheCorrectionIsNegative() {
        ScaleTest test = aTest().indicatedPounds(d("60200")).build();
        assertThat(Certification.correctionFor(test, d("52240"))).isNegative();
    }

    @Test
    void theExposureIsTheSizeOfTheCorrection() {
        ScaleTest test = aTest().indicatedPounds(d("59800")).build();
        assertThat(Certification.exposure(test, d("5000000"))).isEqualTo(d("16665"));
    }

    @Test
    void theScalesTheStateWouldShutAreFound() {
        ScaleLog good = aLog();
        ScaleLog stale = new ScaleLog("SCALE-2", "LURAY");
        stale.record(
                aTest().scaleId("SCALE-2").testedOn(LocalDate.of(2022, 1, 1)).build());
        assertThat(Certification.overdueCertifications(List.of(good, stale), LocalDate.of(2024, 10, 3)))
                .extracting(ScaleLog::scaleId)
                .containsExactly("SCALE-2");
    }
}
