package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.util.List;

/**
 * What the grader ends up writing on the ticket.
 *
 * @param grade the grade the sample reached
 * @param limitingFactors the factors that kept it off the rung above
 */
public record GradeResult(NumericalGrade grade, List<Factor> limitingFactors) {

    public GradeResult {
        limitingFactors = List.copyOf(limitingFactors);
    }

    public boolean isSampleGrade() {
        return grade == NumericalGrade.SAMPLE;
    }
}
