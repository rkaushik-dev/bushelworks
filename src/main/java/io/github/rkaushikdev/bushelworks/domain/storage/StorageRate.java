package io.github.rkaushikdev.bushelworks.domain.storage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * One filed storage tariff, for grain the house is holding for someone else.
 *
 * <p>The free period comes off the front under every accrual method and it is counted in days,
 * never in months. The in-charge is a separate per bushel fee taken once at receipt and it is not
 * storage at all, though every producer calls it that.
 *
 * @param commodityCode the commodity
 * @param ratePerBushelPerMonth dollars per bushel per month
 * @param method how the months are counted
 * @param freeDays days at the front that cost nothing
 * @param inChargePerBushel the once-only fee at receipt
 * @param minimumCharge the least a lot that owes anything pays
 */
public record StorageRate(
        String commodityCode,
        BigDecimal ratePerBushelPerMonth,
        AccrualMethod method,
        int freeDays,
        BigDecimal inChargePerBushel,
        BigDecimal minimumCharge) {

    /** A month of storage is thirty days. */
    public static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30");

    private static final BigDecimal MONTH_PLACES = new BigDecimal("0.000001");

    public StorageRate {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(minimumCharge, "minimumCharge");
        if (ratePerBushelPerMonth.signum() < 0) {
            throw new StorageError(commodityCode + ": storage rate cannot be negative");
        }
        if (freeDays < 0) {
            throw new StorageError(commodityCode + ": free days cannot be negative");
        }
        if (inChargePerBushel.signum() < 0) {
            throw new StorageError(commodityCode + ": in charge cannot be negative");
        }
    }

    /** Daily accrual, no free days, no in-charge and no minimum. */
    public static StorageRate of(String commodityCode, BigDecimal ratePerBushelPerMonth) {
        return new StorageRate(
                commodityCode, ratePerBushelPerMonth, AccrualMethod.DAILY, 0, BigDecimal.ZERO, Money.ZERO_AMOUNT);
    }

    public StorageRate withMethod(AccrualMethod value) {
        return new StorageRate(commodityCode, ratePerBushelPerMonth, value, freeDays, inChargePerBushel, minimumCharge);
    }

    public StorageRate withFreeDays(int value) {
        return new StorageRate(commodityCode, ratePerBushelPerMonth, method, value, inChargePerBushel, minimumCharge);
    }

    public StorageRate withInChargePerBushel(BigDecimal value) {
        return new StorageRate(commodityCode, ratePerBushelPerMonth, method, freeDays, value, minimumCharge);
    }

    public StorageRate withMinimumCharge(BigDecimal value) {
        return new StorageRate(commodityCode, ratePerBushelPerMonth, method, freeDays, inChargePerBushel, value);
    }

    /** Days of storage after the free period, never below zero. */
    public int chargeableDays(LocalDate storedOn, LocalDate through) {
        if (through.isBefore(storedOn)) {
            throw new StorageError("storage cannot be charged to a date before receipt");
        }
        return Math.max((int) ChronoUnit.DAYS.between(storedOn, through) - freeDays, 0);
    }

    /** Months to charge, in whatever unit this method counts in. */
    public BigDecimal chargeableMonths(LocalDate storedOn, LocalDate through) {
        int days = chargeableDays(storedOn, through);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (method) {
            case DAILY -> Units.quantize(Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH), MONTH_PLACES);
            case ANNIVERSARY ->
                Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH).setScale(0, RoundingMode.CEILING);
            case CALENDAR -> BigDecimal.valueOf(calendarMonths(storedOn, through, freeDays));
        };
    }

    /** Dollars of storage owed on this lot as of {@code through}. */
    public BigDecimal charge(BigDecimal bushels, LocalDate storedOn, LocalDate through) {
        if (bushels.signum() < 0) {
            throw new StorageError("cannot charge storage on negative bushels");
        }
        BigDecimal months = chargeableMonths(storedOn, through);
        if (months.signum() <= 0) {
            return Money.amount("0.00");
        }
        BigDecimal gross = Money.extend(bushels, ratePerBushelPerMonth.multiply(months));
        return Money.amount(Decimals.max(gross, minimumCharge));
    }

    public BigDecimal inCharge(BigDecimal bushels) {
        return Money.extend(bushels, inChargePerBushel);
    }

    /**
     * Storage for one billing period, on the bushels open during it.
     *
     * <p>Not the difference between two charges measured from receipt: that under recovers whenever
     * bushels leave the lot between runs, because the already billed half is recomputed on the
     * smaller balance, comes out lower than what was actually charged, and the difference
     * disappears. Charging the period directly is right in both cases and is what the tariff says:
     * so many bushels, for so many days.
     *
     * @param billedThrough the day the last run billed to, or nothing for a lot never billed
     */
    public BigDecimal chargeBetween(
            BigDecimal bushels, LocalDate storedOn, Optional<LocalDate> billedThrough, LocalDate through) {
        if (bushels.signum() < 0) {
            throw new StorageError("cannot charge storage on negative bushels");
        }
        if (billedThrough.isEmpty()) {
            return charge(bushels, storedOn, through);
        }
        LocalDate billed = billedThrough.get();
        if (!through.isAfter(billed)) {
            return Money.amount("0.00");
        }
        int days = chargeableDays(storedOn, through) - chargeableDays(storedOn, billed);
        if (days <= 0) {
            return Money.amount("0.00");
        }
        BigDecimal months = monthsFor(days, storedOn, billed, through);
        if (months.signum() <= 0) {
            return Money.amount("0.00");
        }
        BigDecimal gross = Money.extend(bushels, ratePerBushelPerMonth.multiply(months));
        return Money.amount(Decimals.max(gross, minimumCharge));
    }

    /** The period's length in whatever unit this accrual method counts in. */
    private BigDecimal monthsFor(int days, LocalDate storedOn, LocalDate billedThrough, LocalDate through) {
        if (method == AccrualMethod.DAILY) {
            return Units.quantize(Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH), MONTH_PLACES);
        }
        return chargeableMonths(storedOn, through).subtract(chargeableMonths(storedOn, billedThrough));
    }

    /** Whole calendar months touched, counted from the end of the free period. */
    private static int calendarMonths(LocalDate storedOn, LocalDate through, int freeDays) {
        LocalDate start = storedOn.plusDays(freeDays);
        if (through.isBefore(start)) {
            return 0;
        }
        int months = (through.getYear() - start.getYear()) * 12 + (through.getMonthValue() - start.getMonthValue()) + 1;
        return Math.max(months, 1);
    }
}
