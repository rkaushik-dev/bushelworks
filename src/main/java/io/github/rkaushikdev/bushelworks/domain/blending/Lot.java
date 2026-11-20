package io.github.rkaushikdev.bushelworks.domain.blending;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Bushels of one quality, as a bin or a load.
 *
 * @param label what the desk calls it
 * @param bushels how many
 * @param sample what it tests
 */
public record Lot(String label, BigDecimal bushels, Map<Factor, BigDecimal> sample) {

    public Lot {
        Objects.requireNonNull(label, "label");
        sample = Ordered.copyOf(sample);
        if (bushels.signum() <= 0) {
            throw new BlendError(label + ": a lot needs bushels");
        }
        if (sample.isEmpty()) {
            throw new BlendError(label + ": a lot needs a sample");
        }
    }
}
