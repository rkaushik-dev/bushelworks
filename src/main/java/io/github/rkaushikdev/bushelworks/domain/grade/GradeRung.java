package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.commodity.FactorDirection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One rung of a grade ladder: a grade and the limits that reach it.
 *
 * <p>A limit is inclusive. A factor that is bad when high may sit exactly at its limit; one that is
 * bad when low, test weight say, may sit exactly at its floor.
 *
 * @param grade the grade this rung gives
 * @param limits each factor's limit, in the order the standard lists them
 */
public record GradeRung(NumericalGrade grade, Map<Factor, BigDecimal> limits) {

    public GradeRung {
        limits = Ordered.copyOf(limits);
    }

    /** Factors on this sample that this rung will not accept, in the order of their values. */
    public List<Factor> failures(Map<Factor, BigDecimal> sample) {
        List<Factor> bad = new ArrayList<>();
        limits.forEach((factor, limit) -> {
            BigDecimal value = sample.get(factor);
            if (value == null) {
                return;
            }
            if (factor.direction() == FactorDirection.LOW_IS_BAD) {
                if (value.compareTo(limit) < 0) {
                    bad.add(factor);
                }
            } else if (value.compareTo(limit) > 0) {
                bad.add(factor);
            }
        });
        bad.sort(Factor.BY_VALUE);
        return List.copyOf(bad);
    }

    public boolean accepts(Map<Factor, BigDecimal> sample) {
        return failures(sample).isEmpty();
    }
}
