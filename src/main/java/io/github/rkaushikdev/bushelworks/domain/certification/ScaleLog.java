package io.github.rkaushikdev.bushelworks.domain.certification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every test of one scale, oldest first.
 *
 * <p>A truck scale is certified annually by the state and tested by the house in between. When a
 * test comes back outside tolerance, every ticket written since the last good test is in question,
 * and the house has to be able to say which ones those are and what the error would have done to
 * them.
 */
public final class ScaleLog {

    private final String scaleId;
    private final String locationCode;
    private final List<ScaleTest> tests = new ArrayList<>();

    public ScaleLog(String scaleId, String locationCode) {
        this.scaleId = scaleId;
        this.locationCode = locationCode;
    }

    public String scaleId() {
        return scaleId;
    }

    public String locationCode() {
        return locationCode;
    }

    /** The tests, oldest first. */
    public List<ScaleTest> tests() {
        return List.copyOf(tests);
    }

    public void record(ScaleTest test) {
        if (!test.scaleId().equals(scaleId)) {
            throw new CertificationError(test.scaleId() + " does not belong on the " + scaleId + " log");
        }
        tests.add(test);
        tests.sort(Comparator.comparing(ScaleTest::testedOn));
    }

    public Optional<ScaleTest> latest() {
        return tests.isEmpty() ? Optional.empty() : Optional.of(tests.get(tests.size() - 1));
    }

    /** The most recent passing test on or before a day. */
    public Optional<ScaleTest> lastGoodBefore(LocalDate day) {
        ScaleTest found = null;
        for (ScaleTest test : tests) {
            if (!test.testedOn().isAfter(day) && test.isWithinTolerance()) {
                found = test;
            }
        }
        return Optional.ofNullable(found);
    }

    public boolean certifiedOn(LocalDate day) {
        return tests.stream().anyMatch(test -> test.isCurrentOn(day));
    }

    /**
     * The days whose tickets are in question after a failed test, both included.
     *
     * @param from the day after the last good test
     * @param to the day the failure was found
     */
    public record Window(LocalDate from, LocalDate to) {}

    /**
     * The span whose tickets are in question after a failed test: from the day after the last good
     * test to the day the failure was found. A failure with no good test before it puts the whole
     * log in question, and this says so by returning nothing.
     */
    public Optional<Window> suspectWindow(ScaleTest failed) {
        if (failed.isWithinTolerance()) {
            return Optional.empty();
        }
        return lastGoodBefore(failed.testedOn().minusDays(1))
                .map(good -> new Window(good.testedOn().plusDays(1), failed.testedOn()));
    }
}
