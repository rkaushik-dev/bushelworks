package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What makes a load Sample Grade however well it does on the ladder.
 *
 * <p>The standards do not only grade down a ladder. A load with two pieces of glass in the work
 * portion is US Sample Grade even when every ladder factor would make it a No. 1, and so is one
 * with castor beans in it, or stones over a tenth of a percent of the sample's weight. These are
 * counts and percentages the grader reads off a thousand gram portion, and each one is a ceiling:
 * a reading at the figure here is still allowed, one above it is Sample Grade.
 *
 * @param maxima the most of each that a graded load may carry
 */
public record SampleGradeCriteria(Map<Factor, BigDecimal> maxima) {

    /** The factors these criteria are written in, which no ladder rung reads. */
    public static final Set<Factor> FACTORS = Set.of(
            Factor.STONES,
            Factor.GLASS,
            Factor.CROTALARIA,
            Factor.CASTOR_BEANS,
            Factor.UNKNOWN_FOREIGN,
            Factor.ANIMAL_FILTH);

    /**
     * The criteria the grain standards share: stones over 0.1 percent by weight, two or more pieces
     * of glass, three or more crotalaria seeds, two or more castor beans, four or more particles of
     * an unknown foreign substance, or animal filth over 0.2 percent.
     */
    public static final SampleGradeCriteria STANDARD = new SampleGradeCriteria(Map.of(
            Factor.STONES, new BigDecimal("0.1"),
            Factor.GLASS, new BigDecimal("1"),
            Factor.CROTALARIA, new BigDecimal("2"),
            Factor.CASTOR_BEANS, new BigDecimal("1"),
            Factor.UNKNOWN_FOREIGN, new BigDecimal("3"),
            Factor.ANIMAL_FILTH, new BigDecimal("0.2")));

    public SampleGradeCriteria {
        maxima = Ordered.copyOf(maxima);
        for (Map.Entry<Factor, BigDecimal> maximum : maxima.entrySet()) {
            if (!FACTORS.contains(maximum.getKey())) {
                throw new IllegalArgumentException(
                        maximum.getKey().value() + " is a ladder factor, not a sample grade criterion");
            }
            if (maximum.getValue().signum() < 0) {
                throw new IllegalArgumentException(maximum.getKey().value() + ": a criterion cannot be negative");
            }
        }
    }

    /** The criteria this sample breaks, in the order of their values; nothing when it breaks none. */
    public List<Factor> failures(Map<Factor, BigDecimal> sample) {
        List<Factor> broken = new ArrayList<>();
        maxima.forEach((factor, most) -> {
            BigDecimal reading = sample.get(factor);
            if (reading != null && factor.isPast(most, reading)) {
                broken.add(factor);
            }
        });
        broken.sort(Factor.BY_VALUE);
        return List.copyOf(broken);
    }
}
