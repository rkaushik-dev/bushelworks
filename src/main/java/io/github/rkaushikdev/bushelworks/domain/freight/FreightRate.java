package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One carrier's quote on one lane, with its fuel surcharge.
 *
 * <p>Fuel surcharge is a percentage of the line haul and it moves weekly with the price of diesel.
 * It is a separate figure rather than folded into the rate because the carrier's invoice shows it
 * separately and the two have to reconcile. A carrier that works its surcharge off a {@link FuelSchedule} files the table
 * with the quote, and each week's percent is read off it at that week's diesel price.
 *
 * @param carrier who quoted
 * @param lane the lane quoted on
 * @param basis what the rate is per
 * @param rate the quote
 * @param effectiveFrom when it starts
 * @param fuelSurchargePercent the week's fuel surcharge on the line haul
 * @param minimumCharge the least a haul costs
 * @param paidBy who pays
 * @param fuelSchedule the table the surcharge is worked from, when the carrier files one
 */
public record FreightRate(
        String carrier,
        Lane lane,
        RateBasis basis,
        BigDecimal rate,
        LocalDate effectiveFrom,
        BigDecimal fuelSurchargePercent,
        BigDecimal minimumCharge,
        PaidBy paidBy,
        Optional<FuelSchedule> fuelSchedule) {

    private static final BigDecimal LARGEST_SURCHARGE = new BigDecimal("60");
    private static final BigDecimal CWT_PLACES = new BigDecimal("0.01");

    public FreightRate {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(paidBy, "paidBy");
        Objects.requireNonNull(fuelSchedule, "fuelSchedule");
        if (rate.signum() < 0) {
            throw new FreightError(carrier + ": a rate cannot be negative");
        }
        if (fuelSurchargePercent.signum() < 0 || fuelSurchargePercent.compareTo(LARGEST_SURCHARGE) > 0) {
            throw new FreightError(carrier + ": fuel surcharge out of range");
        }
        if (minimumCharge.signum() < 0) {
            throw new FreightError(carrier + ": a minimum cannot be negative");
        }
    }

    /** A quote with no surcharge and no minimum, the house paying. */
    public static FreightRate of(String carrier, Lane lane, RateBasis basis, BigDecimal rate, LocalDate from) {
        return new FreightRate(
                carrier, lane, basis, rate, from, BigDecimal.ZERO, Money.ZERO_AMOUNT, PaidBy.HOUSE, Optional.empty());
    }

    public FreightRate withCarrier(String value) {
        return new FreightRate(
                value, lane, basis, rate, effectiveFrom, fuelSurchargePercent, minimumCharge, paidBy, fuelSchedule);
    }

    public FreightRate withBasis(RateBasis value, BigDecimal quoted) {
        return new FreightRate(
                carrier, lane, value, quoted, effectiveFrom, fuelSurchargePercent, minimumCharge, paidBy, fuelSchedule);
    }

    public FreightRate withRate(BigDecimal value) {
        return new FreightRate(
                carrier, lane, basis, value, effectiveFrom, fuelSurchargePercent, minimumCharge, paidBy, fuelSchedule);
    }

    public FreightRate withFuelSurchargePercent(BigDecimal value) {
        return new FreightRate(carrier, lane, basis, rate, effectiveFrom, value, minimumCharge, paidBy, fuelSchedule);
    }

    public FreightRate withMinimumCharge(BigDecimal value) {
        return new FreightRate(
                carrier, lane, basis, rate, effectiveFrom, fuelSurchargePercent, value, paidBy, fuelSchedule);
    }

    /** The quote with the table its surcharge is worked from. */
    public FreightRate withFuelSchedule(FuelSchedule value) {
        return new FreightRate(
                carrier,
                lane,
                basis,
                rate,
                effectiveFrom,
                fuelSurchargePercent,
                minimumCharge,
                paidBy,
                Optional.of(value));
    }

    /**
     * The quote as it stands in a week whose diesel averaged {@code dieselPrice}: the surcharge read
     * off the filed table, held to the most a surcharge can be.
     *
     * @throws FreightError when the carrier filed no table to read
     */
    public FreightRate atDieselPrice(BigDecimal dieselPrice) {
        FuelSchedule table = fuelSchedule.orElseThrow(
                () -> new FreightError(carrier + ": no fuel table on file to read a surcharge off"));
        return withFuelSurchargePercent(Decimals.min(table.percentAt(dieselPrice), LARGEST_SURCHARGE));
    }

    public FreightRate withPaidBy(PaidBy value) {
        return new FreightRate(
                carrier, lane, basis, rate, effectiveFrom, fuelSurchargePercent, minimumCharge, value, fuelSchedule);
    }

    /** The charge before fuel, on whatever basis this rate is quoted. */
    public BigDecimal lineHaul(BigDecimal bushels, BigDecimal pounds) {
        if (bushels.signum() < 0 || pounds.signum() < 0) {
            throw new FreightError(carrier + ": cannot rate a negative load");
        }
        return switch (basis) {
            case FLAT -> Money.amount(rate);
            case PER_MILE -> Money.amount(rate.multiply(lane.billedMiles()));
            case PER_BUSHEL -> Money.extend(bushels, rate);
            case PER_HUNDREDWEIGHT ->
                Money.extend(Units.quantize(Decimals.divide(pounds, Units.POUNDS_PER_CWT), CWT_PLACES), rate);
        };
    }

    /** The fuel surcharge on the line haul. */
    public BigDecimal fuel(BigDecimal bushels, BigDecimal pounds) {
        if (fuelSurchargePercent.signum() == 0) {
            return Money.amount("0.00");
        }
        BigDecimal base = lineHaul(bushels, pounds);
        return Money.amount(Decimals.divide(base.multiply(fuelSurchargePercent), Decimals.HUNDRED));
    }

    /** Line haul plus fuel, floored at the minimum. */
    public BigDecimal total(BigDecimal bushels, BigDecimal pounds) {
        BigDecimal charge = Money.amount(lineHaul(bushels, pounds).add(fuel(bushels, pounds)));
        return Money.amount(Decimals.max(charge, minimumCharge));
    }

    /**
     * What this haul works out to per bushel, for comparing quotes.
     *
     * <p>The only honest way to compare a per mile quote with a per bushel one, and it needs a real
     * load to do it.
     */
    public BigDecimal perBushel(BigDecimal bushels, BigDecimal pounds) {
        if (bushels.signum() <= 0) {
            throw new FreightError(carrier + ": cannot rate per bushel on no bushels");
        }
        return Units.quantize(Decimals.divide(total(bushels, pounds), bushels), Money.PRICE_PLACES);
    }
}
