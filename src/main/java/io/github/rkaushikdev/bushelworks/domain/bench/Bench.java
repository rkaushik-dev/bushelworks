package io.github.rkaushikdev.bushelworks.domain.bench;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Every sample on the grading bench at one site. */
public final class Bench {

    private static final Set<Step> WAITING = EnumSet.of(Step.DRAWN, Step.SPLIT, Step.TESTED);

    private final String locationCode;
    private final Map<String, Sample> samples = new LinkedHashMap<>();

    public Bench(String locationCode) {
        this.locationCode = locationCode;
    }

    public String locationCode() {
        return locationCode;
    }

    public void draw(Sample sample) {
        if (samples.containsKey(sample.probeId())) {
            throw new ConflictError(sample.probeId() + " is already on the bench");
        }
        samples.put(sample.probeId(), sample);
    }

    public Sample get(String probeId) {
        Sample found = samples.get(probeId);
        if (found == null) {
            throw new BenchError(probeId + " is not on the bench");
        }
        return found;
    }

    /** Put back a sample that has moved on. It has to be on the bench already. */
    public void replace(Sample sample) {
        get(sample.probeId());
        samples.put(sample.probeId(), sample);
    }

    /** Samples drawn and not yet graded, oldest first. */
    public List<Sample> waiting() {
        return samples.values().stream()
                .filter(sample -> WAITING.contains(sample.step()))
                .sorted(Comparator.comparing(Sample::drawnAt).thenComparing(Sample::probeId))
                .toList();
    }

    public Optional<Sample> forTicket(String ticketNumber) {
        return samples.values().stream()
                .filter(sample ->
                        sample.ticketNumber().filter(ticketNumber::equals).isPresent())
                .findFirst();
    }

    public List<Sample> retainedOn(LocalDate day) {
        return samples.values().stream()
                .filter(sample -> sample.isRetainedOn(day))
                .toList();
    }

    /** Retained samples past their date, which is what clears the shelf. */
    public List<Sample> dueForDiscard(LocalDate day) {
        return samples.values().stream()
                .filter(sample -> sample.retained() && day.isAfter(sample.discardAfter()))
                .toList();
    }

    public int depth() {
        return waiting().size();
    }
}
