package io.github.rkaushikdev.bushelworks.services.deferred;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * What the house charges for carrying an unpriced obligation.
 *
 * <p>The fee runs on its own clock from delivery, usually with no free period at all, and is prorated
 * by the day over a thirty day month. A state that caps how long the arrangement can run is a {@code
 * maximumDays}.
 *
 * @param ratePerBushelPerMonth the fee per bushel per thirty days
 * @param freeDays days after delivery that are not charged
 * @param maximumDays the longest the arrangement may run; empty for no limit
 * @throws DeferredPriceError when the rate or the free days are negative, or the term is under a day
 */
public record ServiceTerms(BigDecimal ratePerBushelPerMonth, int freeDays, Optional<Integer> maximumDays) {

    /** Three cents a bushel a month, nothing free, a year at most. */
    public static final ServiceTerms DEFAULT = new ServiceTerms(new BigDecimal("0.0300"), 0, Optional.of(365));

    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30");

    public ServiceTerms {
        Objects.requireNonNull(ratePerBushelPerMonth, "ratePerBushelPerMonth");
        Objects.requireNonNull(maximumDays, "maximumDays");
        if (ratePerBushelPerMonth.signum() < 0) {
            throw new DeferredPriceError("a service rate cannot be negative");
        }
        if (freeDays < 0) {
            throw new DeferredPriceError("free days cannot be negative");
        }
        if (maximumDays.isPresent() && maximumDays.get() < 1) {
            throw new DeferredPriceError("a maximum term has to be at least a day");
        }
    }

    /** A rate with no free days and no limit on the term. */
    public static ServiceTerms of(BigDecimal ratePerBushelPerMonth) {
        return new ServiceTerms(ratePerBushelPerMonth, 0, Optional.empty());
    }

    public ServiceTerms withFreeDays(int value) {
        return new ServiceTerms(ratePerBushelPerMonth, value, maximumDays);
    }

    /** Null means no limit. */
    public ServiceTerms withMaximumDays(Integer value) {
        return new ServiceTerms(ratePerBushelPerMonth, freeDays, Optional.ofNullable(value));
    }

    /**
     * Days charged between delivery and {@code through}, the free days off the front, never below zero.
     *
     * @throws DeferredPriceError when {@code through} is before delivery
     */
    public int chargeableDays(LocalDate deliveredOn, LocalDate through) {
        if (through.isBefore(deliveredOn)) {
            throw new DeferredPriceError("cannot charge back before delivery");
        }
        return Math.max((int) ChronoUnit.DAYS.between(deliveredOn, through) - freeDays, 0);
    }

    /** Whether grain delivered on {@code deliveredOn} has run past the term by {@code on}. */
    public boolean isOverdue(LocalDate deliveredOn, LocalDate on) {
        return maximumDays
                .map(limit -> ChronoUnit.DAYS.between(deliveredOn, on) > limit)
                .orElse(false);
    }

    /**
     * The service fee owed on {@code bushels}, prorated by the day over a thirty day month.
     *
     * @throws DeferredPriceError when {@code through} is before delivery
     */
    public BigDecimal charge(BigDecimal bushels, LocalDate deliveredOn, LocalDate through) {
        int days = chargeableDays(deliveredOn, through);
        if (days <= 0) {
            return Money.amount("0.00");
        }
        BigDecimal months = Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH);
        return Money.extend(bushels, ratePerBushelPerMonth.multiply(months, Decimals.WORKING));
    }
}
