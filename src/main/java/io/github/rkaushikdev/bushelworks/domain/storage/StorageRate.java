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
 * <p>A tariff with a {@link SeasonalFlat} charges the flat once, the first time a lot owes
 * anything after its free days, and accrues by the month only from the day after the flat period
 * ends. The minimum charge is a floor on the whole bill, flat and monthly together.
 *
 * @param commodityCode the commodity
 * @param ratePerBushelPerMonth dollars per bushel per month
 * @param method how the months are counted
 * @param freeDays days at the front that cost nothing
 * @param inChargePerBushel the once-only fee at receipt
 * @param minimumCharge the least a lot that owes anything pays
 * @param seasonalFlat the flat charge through a day of the year, when the tariff has one
 */
public record StorageRate(
        String commodityCode,
        BigDecimal ratePerBushelPerMonth,
        AccrualMethod method,
        int freeDays,
        BigDecimal inChargePerBushel,
        BigDecimal minimumCharge,
        Optional<SeasonalFlat> seasonalFlat) {

    /** A month of storage is thirty days. */
    public static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30");

    private static final BigDecimal MONTH_PLACES = new BigDecimal("0.000001");

    public StorageRate {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(minimumCharge, "minimumCharge");
        Objects.requireNonNull(seasonalFlat, "seasonalFlat");
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

    /** Daily accrual, no free days, no in-charge, no minimum and no flat period. */
    public static StorageRate of(String commodityCode, BigDecimal ratePerBushelPerMonth) {
        return new StorageRate(
                commodityCode,
                ratePerBushelPerMonth,
                AccrualMethod.DAILY,
                0,
                BigDecimal.ZERO,
                Money.ZERO_AMOUNT,
                Optional.empty());
    }

    public StorageRate withMethod(AccrualMethod value) {
        return new StorageRate(
                commodityCode, ratePerBushelPerMonth, value, freeDays, inChargePerBushel, minimumCharge, seasonalFlat);
    }

    public StorageRate withFreeDays(int value) {
        return new StorageRate(
                commodityCode, ratePerBushelPerMonth, method, value, inChargePerBushel, minimumCharge, seasonalFlat);
    }

    public StorageRate withInChargePerBushel(BigDecimal value) {
        return new StorageRate(
                commodityCode, ratePerBushelPerMonth, method, freeDays, value, minimumCharge, seasonalFlat);
    }

    public StorageRate withMinimumCharge(BigDecimal value) {
        return new StorageRate(
                commodityCode, ratePerBushelPerMonth, method, freeDays, inChargePerBushel, value, seasonalFlat);
    }

    /** Charge a flat rate through a day of the year, and the monthly rate only after it. */
    public StorageRate withSeasonalFlat(SeasonalFlat value) {
        return new StorageRate(
                commodityCode,
                ratePerBushelPerMonth,
                method,
                freeDays,
                inChargePerBushel,
                minimumCharge,
                Optional.of(value));
    }

    /** Days of storage after the free period, never below zero. */
    public int chargeableDays(LocalDate storedOn, LocalDate through) {
        if (through.isBefore(storedOn)) {
            throw new StorageError("storage cannot be charged to a date before receipt");
        }
        return Math.max((int) ChronoUnit.DAYS.between(storedOn, through) - freeDays, 0);
    }

    /**
     * Months to charge at the monthly rate, in whatever unit this method counts in.
     *
     * <p>Under a flat period they are counted from the day the flat period ends, with no free days,
     * because the flat already paid for the front of the term.
     */
    public BigDecimal chargeableMonths(LocalDate storedOn, LocalDate through) {
        if (chargeableDays(storedOn, through) <= 0) {
            return BigDecimal.ZERO;
        }
        if (seasonalFlat.isPresent()) {
            LocalDate flatEnds = seasonalFlat.get().coversThrough(storedOn);
            return through.isAfter(flatEnds) ? monthsAfter(flatEnds, through, 0) : BigDecimal.ZERO;
        }
        return monthsAfter(storedOn, through, freeDays);
    }

    /** The flat charge a lot owes as of {@code through}: once anything is owed, and nothing before. */
    public BigDecimal flatCharge(BigDecimal bushels, LocalDate storedOn, LocalDate through) {
        if (seasonalFlat.isEmpty() || chargeableDays(storedOn, through) <= 0) {
            return Money.ZERO_AMOUNT;
        }
        return Money.extend(bushels, seasonalFlat.get().perBushel());
    }

    /** Dollars of storage owed on this lot as of {@code through}. */
    public BigDecimal charge(BigDecimal bushels, LocalDate storedOn, LocalDate through) {
        if (bushels.signum() < 0) {
            throw new StorageError("cannot charge storage on negative bushels");
        }
        return bill(bushels, flatCharge(bushels, storedOn, through), chargeableMonths(storedOn, through));
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
     * so many bushels, for so many days. A flat charge goes on the first period that owes anything.
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
        BigDecimal flat = flatCharge(bushels, storedOn, through).subtract(flatCharge(bushels, storedOn, billed));
        return bill(bushels, flat, monthsFor(storedOn, billed, through));
    }

    /** The flat and the monthly storage together, floored at the minimum once anything is owed. */
    private BigDecimal bill(BigDecimal bushels, BigDecimal flat, BigDecimal months) {
        if (months.signum() <= 0 && flat.signum() <= 0) {
            return Money.amount("0.00");
        }
        BigDecimal monthly =
                months.signum() > 0 ? Money.extend(bushels, ratePerBushelPerMonth.multiply(months)) : Money.ZERO_AMOUNT;
        return Money.amount(Decimals.max(flat.add(monthly), minimumCharge));
    }

    /** The period's length in whatever unit this accrual method counts in. */
    private BigDecimal monthsFor(LocalDate storedOn, LocalDate billedThrough, LocalDate through) {
        if (method == AccrualMethod.DAILY) {
            int days = accruingDays(storedOn, through) - accruingDays(storedOn, billedThrough);
            return days <= 0 ? BigDecimal.ZERO : dailyMonths(days);
        }
        return chargeableMonths(storedOn, through).subtract(chargeableMonths(storedOn, billedThrough));
    }

    /** Days that accrue at the monthly rate: after the free days, or after the flat period. */
    private int accruingDays(LocalDate storedOn, LocalDate through) {
        if (seasonalFlat.isEmpty()) {
            return chargeableDays(storedOn, through);
        }
        LocalDate flatEnds = seasonalFlat.get().coversThrough(storedOn);
        return Math.max((int) ChronoUnit.DAYS.between(flatEnds, through), 0);
    }

    /** Months between {@code start} and {@code through} once {@code free} days have come off. */
    private BigDecimal monthsAfter(LocalDate start, LocalDate through, int free) {
        int days = Math.max((int) ChronoUnit.DAYS.between(start, through) - free, 0);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (method) {
            case DAILY -> dailyMonths(days);
            case ANNIVERSARY ->
                Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH).setScale(0, RoundingMode.CEILING);
            case CALENDAR -> BigDecimal.valueOf(calendarMonths(start, through, free));
        };
    }

    private static BigDecimal dailyMonths(int days) {
        return Units.quantize(Decimals.divide(BigDecimal.valueOf(days), DAYS_PER_MONTH), MONTH_PLACES);
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
