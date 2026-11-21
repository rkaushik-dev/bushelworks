package io.github.rkaushikdev.bushelworks.api.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One reading off the grader's sample: a factor and what it read.
 *
 * @param factor the grade factor
 * @param value the reading
 */
public record SampleReading(Factor factor, BigDecimal value) {

    public SampleReading {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(value, "value");
    }

    /** A reading as a caller sends it. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SampleReading from(JsonNode json) {
        return Body.read(json, SampleReading::read);
    }

    /** A reading read out of a body. */
    public static SampleReading read(Body in) {
        Factor factor = in.choice("factor", Factor.class);
        BigDecimal value = in.quantity("value");
        return in.build(() -> new SampleReading(factor, value));
    }
}
