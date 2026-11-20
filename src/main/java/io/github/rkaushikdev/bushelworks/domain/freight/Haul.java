package io.github.rkaushikdev.bushelworks.domain.freight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One truck, rated.
 *
 * @param reference the haul
 * @param rate the quote it was hauled on
 * @param bushels what it carried
 * @param pounds what it weighed
 * @param hauledOn when
 */
public record Haul(String reference, FreightRate rate, BigDecimal bushels, BigDecimal pounds, LocalDate hauledOn) {

    /** What a hopper bottom holds. Used only to sanity check a quoted load. */
    public static final BigDecimal TYPICAL_HOPPER_POUNDS = new BigDecimal("52000");

    private static final BigDecimal OVERWEIGHT_ALLOWANCE = new BigDecimal("1.15");

    public Haul {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(pounds, "pounds");
        Objects.requireNonNull(hauledOn, "hauledOn");
    }

    public BigDecimal lineHaul() {
        return rate.lineHaul(bushels, pounds);
    }

    public BigDecimal fuel() {
        return rate.fuel(bushels, pounds);
    }

    public BigDecimal total() {
        return rate.total(bushels, pounds);
    }

    /**
     * Whether the load is heavier than a hopper bottom usually carries.
     *
     * <p>Not an error. It flags a load worth looking at before the scale house argues with the
     * carrier's invoice.
     */
    public boolean isOverweight() {
        return pounds.compareTo(TYPICAL_HOPPER_POUNDS.multiply(OVERWEIGHT_ALLOWANCE)) > 0;
    }
}
