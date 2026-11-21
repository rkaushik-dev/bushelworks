package io.github.rkaushikdev.bushelworks.api.schemas;

import java.util.List;
import java.util.Objects;

/**
 * The grade a load made.
 *
 * @param grade the numerical grade, or {@code sample}
 * @param limitingFactors the factors that held it there
 */
public record GradeOut(String grade, List<String> limitingFactors) {

    public GradeOut {
        Objects.requireNonNull(grade, "grade");
        limitingFactors = List.copyOf(limitingFactors);
    }
}
