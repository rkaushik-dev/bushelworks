package io.github.rkaushikdev.bushelworks.domain.bench;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BenchTest {

    static final OffsetDateTime DRAWN = OffsetDateTime.of(2024, 10, 3, 8, 15, 0, 0, ZoneOffset.UTC);

    static Map<Factor, BigDecimal> readings() {
        Map<Factor, BigDecimal> readings = new LinkedHashMap<>();
        readings.put(Factor.TEST_WEIGHT, d("56.5"));
        readings.put(Factor.MOISTURE, d("18.2"));
        readings.put(Factor.BCFM, d("1.2"));
        readings.put(Factor.DAMAGE_TOTAL, d("1.0"));
        return readings;
    }

    static Sample aSample() {
        return Sample.drawn("P-4417", "CORN", DRAWN, "jvoss");
    }

    static Sample tested() {
        return aSample().split().test(readings(), "GAC2500");
    }

    static Bench aBench() {
        Bench bench = new Bench("HOME");
        bench.draw(aSample());
        bench.draw(Sample.drawn("P-4418", "CORN", DRAWN.plusMinutes(20), "jvoss"));
        return bench;
    }

    @Test
    void aSampleNeedsAProbeNumberAndAGrader() {
        assertThatThrownBy(() -> Sample.drawn("", "CORN", DRAWN, "jvoss")).isInstanceOf(BenchError.class);
        assertThatThrownBy(() -> Sample.drawn("P-4417", "CORN", DRAWN, "")).isInstanceOf(BenchError.class);
    }

    @Test
    void aTestedSampleNeedsReadings() {
        assertThatThrownBy(() -> aSample().atStep(Step.TESTED)).isInstanceOf(BenchError.class);
    }

    @Test
    void aNewSampleIsDrawn() {
        assertThat(aSample().step()).isSameAs(Step.DRAWN);
        assertThat(aSample().retained()).isFalse();
    }

    @Test
    void splittingKeepsAHalfOnTheShelf() {
        Sample split = aSample().split();
        assertThat(split.step()).isSameAs(Step.SPLIT);
        assertThat(split.retained()).isTrue();
    }

    @Test
    void aSplitCanBeToldNotToKeepAnything() {
        assertThat(aSample().split(false).retained()).isFalse();
    }

    @Test
    void aSampleCannotSkipAStep() {
        assertThatThrownBy(() -> aSample().advance(Step.TESTED)).isInstanceOf(ConflictError.class);
    }

    @Test
    void testingRecordsTheInstrument() {
        Sample tested = tested();
        assertThat(tested.step()).isSameAs(Step.TESTED);
        assertThat(tested.instrument()).contains("GAC2500");
        assertThat(tested.readings().get(Factor.MOISTURE)).isEqualTo(d("18.2"));
    }

    @Test
    void aTestWithNoReadingsIsRefused() {
        assertThatThrownBy(() -> aSample().split().test(Map.of(), "GAC2500")).isInstanceOf(BenchError.class);
    }

    @Test
    void aTestWithNoInstrumentIsRefused() {
        assertThatThrownBy(() -> aSample().split().test(readings(), "")).isInstanceOf(BenchError.class);
    }

    @Test
    void gradingTiesTheSampleToATicket() {
        Sample.Graded graded = tested().grade(Commodities.CORN, "A-10441");
        assertThat(graded.sample().step()).isSameAs(Step.GRADED);
        assertThat(graded.sample().ticketNumber()).contains("A-10441");
        assertThat(graded.result().grade()).isSameAs(NumericalGrade.US_1);
    }

    @Test
    void anUntestedSampleCannotBeGraded() {
        assertThatThrownBy(() -> aSample().split().grade(Commodities.CORN, "A-10441"))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void aGradeNeedsATicket() {
        assertThatThrownBy(() -> tested().grade(Commodities.CORN, "")).isInstanceOf(BenchError.class);
    }

    @Test
    void aSampleOfAnotherCommodityIsRefused() {
        assertThatThrownBy(() -> tested().grade(Commodities.SOYBEANS, "A-10441"))
                .isInstanceOf(BenchError.class);
    }

    @Test
    void aRetainedSampleIsKeptForTheRetentionPeriod() {
        Sample split = aSample().split();
        assertThat(split.discardAfter()).isEqualTo(DRAWN.plus(Sample.RETENTION).toLocalDate());
        assertThat(split.isRetainedOn(LocalDate.of(2024, 10, 20))).isTrue();
        assertThat(split.isRetainedOn(LocalDate.of(2025, 1, 1))).isFalse();
    }

    @Test
    void aRetainedSampleCannotBeDiscardedEarly() {
        Sample graded = tested().grade(Commodities.CORN, "A-10441").sample();
        assertThatThrownBy(() -> graded.discard(LocalDate.of(2024, 10, 20))).isInstanceOf(ConflictError.class);
    }

    @Test
    void aRetainedSampleCanBeDiscardedAfterItsDate() {
        Sample graded = tested().grade(Commodities.CORN, "A-10441").sample();
        Sample thrown = graded.discard(LocalDate.of(2025, 1, 1));
        assertThat(thrown.step()).isSameAs(Step.DISCARDED);
        assertThat(thrown.retained()).isFalse();
    }

    @Test
    void anUnretainedSampleCanGoAtAnyTime() {
        Sample graded = aSample()
                .split(false)
                .test(readings(), "GAC2500")
                .grade(Commodities.CORN, "A-1")
                .sample();
        assertThat(graded.discard(LocalDate.of(2024, 10, 4)).step()).isSameAs(Step.DISCARDED);
    }

    @Test
    void theBenchHoldsWhatIsDrawn() {
        assertThat(aBench().depth()).isEqualTo(2);
    }

    @Test
    void theSameProbeTwiceIsAConflict() {
        Bench bench = aBench();
        assertThatThrownBy(() -> bench.draw(aSample())).isInstanceOf(ConflictError.class);
    }

    @Test
    void aProbeNotOnTheBenchSaysSo() {
        assertThatThrownBy(() -> aBench().get("P-9999")).isInstanceOf(BenchError.class);
    }

    @Test
    void theWaitingListIsOldestFirst() {
        assertThat(aBench().waiting()).extracting(Sample::probeId).containsExactly("P-4417", "P-4418");
    }

    @Test
    void aGradedSampleLeavesTheWaitingList() {
        Bench bench = aBench();
        Sample graded = bench.get("P-4417")
                .split()
                .test(readings(), "GAC2500")
                .grade(Commodities.CORN, "A-10441")
                .sample();
        bench.replace(graded);
        assertThat(bench.waiting()).extracting(Sample::probeId).containsExactly("P-4418");
    }

    @Test
    void aSampleCanBeFoundByItsTicket() {
        Bench bench = aBench();
        Sample graded = bench.get("P-4417")
                .split()
                .test(readings(), "GAC2500")
                .grade(Commodities.CORN, "A-10441")
                .sample();
        bench.replace(graded);
        assertThat(bench.forTicket("A-10441").map(Sample::probeId)).contains("P-4417");
        assertThat(bench.forTicket("A-99999")).isEqualTo(Optional.empty());
    }

    @Test
    void theShelfListsWhatIsRetained() {
        Bench bench = aBench();
        bench.replace(bench.get("P-4417").split());
        assertThat(bench.retainedOn(LocalDate.of(2024, 10, 20)))
                .extracting(Sample::probeId)
                .containsExactly("P-4417");
    }

    @Test
    void nothingIsDueForDiscardInsideTheRetention() {
        Bench bench = aBench();
        bench.replace(bench.get("P-4417").split());
        assertThat(bench.dueForDiscard(LocalDate.of(2024, 10, 20))).isEmpty();
    }

    @Test
    void theShelfClearsAfterTheRetention() {
        Bench bench = aBench();
        bench.replace(bench.get("P-4417").split());
        assertThat(bench.dueForDiscard(LocalDate.of(2025, 1, 1)))
                .extracting(Sample::probeId)
                .containsExactly("P-4417");
    }
}
