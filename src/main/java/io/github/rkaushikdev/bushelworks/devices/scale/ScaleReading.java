package io.github.rkaushikdev.bushelworks.devices.scale;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One frame off the indicator.
 *
 * @param pounds the displayed weight in whole pounds, converted when the indicator sent kilograms
 * @param stability the status the frame carried
 * @param unit the unit the indicator sent, {@code lb} or {@code kg}
 */
public record ScaleReading(BigDecimal pounds, Stability stability, String unit) {

    public ScaleReading {
        Objects.requireNonNull(pounds, "pounds");
        Objects.requireNonNull(stability, "stability");
        Objects.requireNonNull(unit, "unit");
    }

    /** Only a stable frame is a weight worth writing on a ticket. */
    public boolean isUsable() {
        return stability == Stability.STABLE;
    }

    public boolean isOverCapacity() {
        return stability == Stability.OVER_CAPACITY;
    }
}
