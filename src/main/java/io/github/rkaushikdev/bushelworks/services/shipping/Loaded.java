package io.github.rkaushikdev.bushelworks.services.shipping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One car or truck off the yard.
 *
 * @param saleNumber the sale it went against
 * @param reference the car or truck
 * @param bushels the bushels it carried, weighed at origin
 * @param shippedOn the day it went
 * @param remaining what the sale still wants, never below zero
 */
public record Loaded(
        String saleNumber, String reference, BigDecimal bushels, LocalDate shippedOn, BigDecimal remaining) {

    public Loaded {
        Objects.requireNonNull(saleNumber, "saleNumber");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(shippedOn, "shippedOn");
        Objects.requireNonNull(remaining, "remaining");
    }
}
