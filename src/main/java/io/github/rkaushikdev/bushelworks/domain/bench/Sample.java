package io.github.rkaushikdev.bushelworks.domain.bench;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.Grading;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One probe, from the truck to the shelf.
 *
 * <p>A sample is drawn, split, tested and kept. The order is fixed and the keeping is the part
 * people skip: the retained half is what settles an argument three weeks later, and a bench that
 * has thrown it away has nothing to say.
 *
 * <p>A sample is identified by the probe number, not the ticket number. The ticket does not exist
 * yet when the probe is drawn, and tying the two together afterwards is what the bench does.
 *
 * @param probeId the probe number
 * @param commodityCode the commodity
 * @param drawnAt when the probe was drawn
 * @param drawnBy the grader
 * @param step where the sample is on the bench
 * @param ticketNumber the ticket it was graded onto, once it has been
 * @param readings what the instruments read, once tested
 * @param instrument the instrument that read it, once tested
 * @param retained whether half of it is on the shelf
 */
public record Sample(
        String probeId,
        String commodityCode,
        OffsetDateTime drawnAt,
        String drawnBy,
        Step step,
        Optional<String> ticketNumber,
        Map<Factor, BigDecimal> readings,
        Optional<String> instrument,
        boolean retained) {

    /** How long the retained half is kept: long enough for a buyer's complaint, short enough for the shelf. */
    public static final Duration RETENTION = Duration.ofDays(45);

    public Sample {
        Objects.requireNonNull(probeId, "probeId");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(drawnAt, "drawnAt");
        Objects.requireNonNull(drawnBy, "drawnBy");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(instrument, "instrument");
        readings = Ordered.copyOf(readings);
        if (probeId.isEmpty()) {
            throw new BenchError("a sample needs a probe number");
        }
        if (drawnBy.isEmpty()) {
            throw new BenchError(probeId + ": a sample needs a grader");
        }
        if (step == Step.TESTED && readings.isEmpty()) {
            throw new BenchError(probeId + ": tested but nothing was read");
        }
    }

    /** A probe just drawn. */
    public static Sample drawn(String probeId, String commodityCode, OffsetDateTime drawnAt, String drawnBy) {
        return new Sample(
                probeId,
                commodityCode,
                drawnAt,
                drawnBy,
                Step.DRAWN,
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                false);
    }

    /** The same sample at another step, nothing else changed; refused if the step does not hold. */
    public Sample atStep(Step value) {
        return new Sample(
                probeId, commodityCode, drawnAt, drawnBy, value, ticketNumber, readings, instrument, retained);
    }

    /** The day after which the retained half may go. */
    public LocalDate discardAfter() {
        return drawnAt.plus(RETENTION).toLocalDate();
    }

    public boolean isRetainedOn(LocalDate day) {
        return retained && !day.isAfter(discardAfter());
    }

    private void checkStep(Step to) {
        if (!step.next().contains(to)) {
            throw new ConflictError(probeId + " is " + step.value() + " and cannot go to " + to.value());
        }
    }

    /** Move the sample on one step. */
    public Sample advance(Step to) {
        checkStep(to);
        return atStep(to);
    }

    /** Halve the probe, keeping one half on the shelf. */
    public Sample split() {
        return split(true);
    }

    /** Halve the probe, keeping one half on the shelf when asked to. */
    public Sample split(boolean retain) {
        Sample halved = advance(Step.SPLIT);
        return new Sample(
                probeId, commodityCode, drawnAt, drawnBy, halved.step(), ticketNumber, readings, instrument, retain);
    }

    /**
     * Record what the instruments read. The step and the readings move together: a tested sample
     * with nothing on it is not a state the bench can be in.
     */
    public Sample test(Map<Factor, BigDecimal> values, String by) {
        checkStep(Step.TESTED);
        if (values.isEmpty()) {
            throw new BenchError(probeId + ": a test needs readings");
        }
        if (by.isEmpty()) {
            throw new BenchError(probeId + ": a test needs an instrument");
        }
        return new Sample(
                probeId, commodityCode, drawnAt, drawnBy, Step.TESTED, ticketNumber, values, Optional.of(by), retained);
    }

    /**
     * A graded sample and the grade it got.
     *
     * @param sample the sample, now tied to its ticket
     * @param result the grade
     */
    public record Graded(Sample sample, GradeResult result) {}

    /** Grade the sample and tie it to the ticket it belongs to. */
    public Graded grade(Commodity commodity, String ticket) {
        if (step != Step.TESTED) {
            throw new ConflictError(probeId + " is " + step.value() + " and has not been tested");
        }
        if (ticket.isEmpty()) {
            throw new BenchError(probeId + ": a grade needs a ticket to sit on");
        }
        if (!commodity.code().equals(commodityCode)) {
            throw new BenchError(probeId + " is " + commodityCode + ", not " + commodity.code());
        }
        GradeResult result = Grading.determine(commodity, readings);
        Sample advanced = advance(Step.GRADED);
        Sample graded = new Sample(
                probeId,
                commodityCode,
                drawnAt,
                drawnBy,
                advanced.step(),
                Optional.of(ticket),
                readings,
                instrument,
                retained);
        return new Graded(graded, result);
    }

    /** Throw the sample out. A retained half stays until its date has passed. */
    public Sample discard(LocalDate on) {
        if (!retained) {
            return advance(Step.DISCARDED);
        }
        if (!on.isAfter(discardAfter())) {
            throw new ConflictError(probeId + " is retained until " + discardAfter());
        }
        Sample thrown = advance(Step.DISCARDED);
        return new Sample(
                probeId, commodityCode, drawnAt, drawnBy, thrown.step(), ticketNumber, readings, instrument, false);
    }
}
