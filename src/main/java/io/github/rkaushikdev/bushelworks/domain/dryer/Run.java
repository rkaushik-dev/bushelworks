package io.github.rkaushikdev.bushelworks.domain.dryer;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One dryer run, from lighting the burner to shutting it down.
 *
 * <p>A run that dried nothing still costs money, because the burner was lit. Runs with zero
 * bushels are kept for that reason rather than dropped.
 *
 * <p>Whether a run burned too much is judged on the water it drove off, not on its point bushels.
 * A point off a sixty pound bushel of soybeans is more water than a point off a fifty six pound
 * bushel of corn, and a rule of thumb per point bushel calls the soybean run thirsty for doing the
 * same job. The water is the bushels at the commodity's standard weight, times the points, over
 * what is left once the grain is down to its base moisture.
 *
 * @param runId the run
 * @param locationCode where the dryer is
 * @param commodityCode what went through it
 * @param startedAt when the burner was lit
 * @param endedAt when it was shut down
 * @param bushels how many bushels went through
 * @param pointsRemoved points of moisture taken out
 * @param propaneGallons fuel burned
 * @param kwh power used
 * @param operator who ran it
 */
public record Run(
        String runId,
        String locationCode,
        String commodityCode,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        BigDecimal bushels,
        BigDecimal pointsRemoved,
        BigDecimal propaneGallons,
        BigDecimal kwh,
        String operator) {

    /** The heat a gallon of propane gives up. */
    public static final BigDecimal BTU_PER_GALLON = new BigDecimal("91500");

    /** What a well kept high temperature dryer spends driving off a pound of water. */
    public static final BigDecimal STANDARD_BTU_PER_POUND_OF_WATER = new BigDecimal("2780");

    /** The rule of thumb for power, per bushel per point. */
    public static final BigDecimal KWH_PER_POINT_PER_BUSHEL = new BigDecimal("0.012");

    private static final BigDecimal SECONDS_AN_HOUR = new BigDecimal("3600");
    private static final BigDecimal CENTS = new BigDecimal("0.01");
    private static final BigDecimal RATE_PLACES = new BigDecimal("0.0001");
    private static final BigDecimal THIRST_ALLOWANCE = new BigDecimal("1.25");
    private static final BigDecimal POUND_PLACES = BigDecimal.ONE;

    public Run {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(operator, "operator");
        if (runId.isEmpty()) {
            throw new DryerError("a run needs an identifier");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new DryerError(runId + ": ended before it started");
        }
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        quantities.put("bushels", bushels);
        quantities.put("points_removed", pointsRemoved);
        quantities.put("propane_gallons", propaneGallons);
        quantities.put("kwh", kwh);
        quantities.forEach((name, value) -> {
            if (value.signum() < 0) {
                throw new DryerError(runId + ": " + name + " is negative");
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .runId(runId)
                .locationCode(locationCode)
                .commodityCode(commodityCode)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .bushels(bushels)
                .pointsRemoved(pointsRemoved)
                .propaneGallons(propaneGallons)
                .kwh(kwh)
                .operator(operator);
    }

    /** How long the burner was lit, in hours to the hundredth. */
    public BigDecimal hours() {
        Duration lit = Duration.between(startedAt, endedAt);
        BigDecimal seconds = BigDecimal.valueOf(lit.getSeconds()).add(BigDecimal.valueOf(lit.getNano(), 9));
        return Units.quantize(Decimals.divide(seconds, SECONDS_AN_HOUR), CENTS);
    }

    /** Bushels times points, which is what everything is charged on. */
    public BigDecimal pointBushels() {
        return Units.quantize(bushels.multiply(pointsRemoved), CENTS);
    }

    public boolean isIdle() {
        return bushels.signum() == 0;
    }

    /** What the house charged for the run, at the published rate per point. */
    public BigDecimal revenue(BigDecimal ratePerPoint) {
        return Money.extend(pointBushels(), ratePerPoint);
    }

    /** What the run cost: propane plus power. */
    public BigDecimal cost(BigDecimal propanePrice, BigDecimal powerPrice) {
        return Money.amount(propaneGallons.multiply(propanePrice).add(kwh.multiply(powerPrice)));
    }

    public BigDecimal margin(BigDecimal ratePerPoint, BigDecimal propanePrice, BigDecimal powerPrice) {
        return Money.amount(revenue(ratePerPoint).subtract(cost(propanePrice, powerPrice)));
    }

    /**
     * Gallons per point bushel, the figure the office quoted before it judged runs on their water.
     * It is still what the propane supplier asks for, so it stays on the run sheet.
     *
     * <p>Nothing on an idle run: dividing the burner's fuel by no bushels is a number with no meaning.
     */
    public Optional<BigDecimal> gallonsPerPointBushel() {
        BigDecimal pointBushels = pointBushels();
        if (pointBushels.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Units.quantize(Decimals.divide(propaneGallons, pointBushels), RATE_PLACES));
    }

    /** Pounds of water the run drove off, to the pound. */
    public BigDecimal waterRemovedPounds() {
        Commodity commodity = Commodities.get(commodityCode);
        BigDecimal wetPounds = bushels.multiply(commodity.standardTestWeight());
        BigDecimal dryShare = Decimals.HUNDRED.subtract(commodity.baseMoisture());
        return Units.quantize(Decimals.divide(wetPounds.multiply(pointsRemoved), dryShare), POUND_PLACES);
    }

    /** The propane a dryer of this efficiency should have burned on the run, to the gallon. */
    public BigDecimal expectedGallons(BigDecimal btuPerPoundOfWater) {
        if (btuPerPoundOfWater.signum() <= 0) {
            throw new DryerError(runId + ": a dryer cannot drive off water for nothing");
        }
        BigDecimal heat = waterRemovedPounds().multiply(btuPerPoundOfWater);
        return Units.quantize(Decimals.divide(heat, BTU_PER_GALLON), POUND_PLACES);
    }

    /** What the run actually spent on a pound of water, or nothing when it drove none off. */
    public Optional<BigDecimal> btuPerPoundOfWater() {
        BigDecimal water = waterRemovedPounds();
        if (water.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(
                Units.quantize(Decimals.divide(propaneGallons.multiply(BTU_PER_GALLON), water), POUND_PLACES));
    }

    /** Whether the run burned more than a quarter over what a standard dryer would on its water. */
    public boolean isThirsty() {
        return isThirsty(STANDARD_BTU_PER_POUND_OF_WATER);
    }

    /** Whether the run burned more than a quarter over what a dryer of this efficiency would. */
    public boolean isThirsty(BigDecimal btuPerPoundOfWater) {
        if (waterRemovedPounds().signum() <= 0) {
            return false;
        }
        return propaneGallons.compareTo(expectedGallons(btuPerPoundOfWater).multiply(THIRST_ALLOWANCE)) > 0;
    }

    /** A dryer run assembled a field at a time. */
    public static final class Builder {

        private String runId;
        private String locationCode;
        private String commodityCode;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private BigDecimal bushels;
        private BigDecimal pointsRemoved;
        private BigDecimal propaneGallons;
        private BigDecimal kwh;
        private String operator = "";

        private Builder() {}

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder startedAt(LocalDateTime value) {
            this.startedAt = value;
            return this;
        }

        public Builder endedAt(LocalDateTime value) {
            this.endedAt = value;
            return this;
        }

        public Builder bushels(BigDecimal value) {
            this.bushels = value;
            return this;
        }

        public Builder pointsRemoved(BigDecimal value) {
            this.pointsRemoved = value;
            return this;
        }

        public Builder propaneGallons(BigDecimal value) {
            this.propaneGallons = value;
            return this;
        }

        public Builder kwh(BigDecimal value) {
            this.kwh = value;
            return this;
        }

        public Builder operator(String value) {
            this.operator = value;
            return this;
        }

        public Run build() {
            return new Run(
                    runId,
                    locationCode,
                    commodityCode,
                    startedAt,
                    endedAt,
                    bushels,
                    pointsRemoved,
                    propaneGallons,
                    kwh,
                    operator);
        }
    }
}
