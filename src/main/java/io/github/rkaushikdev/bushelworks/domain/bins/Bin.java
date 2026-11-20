package io.github.rkaushikdev.bushelworks.domain.bins;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One bin and what is physically in it.
 *
 * <p>A bin holds one commodity at a time and its contents are a running blend. Every load in
 * changes the average of what is in there, and that average is what a draw out of the bin will
 * test.
 *
 * <p>Capacity is in bushels at a nominal test weight, which is how the licence states it, and a bin
 * filled with light corn will hit its capacity in bushels before it hits it in feet.
 *
 * @param binId the bin
 * @param locationCode the site it stands at
 * @param capacityBushels its licensed capacity
 * @param commodityCode what is in it, when anything is
 * @param bushels how much is in it
 * @param sample what the contents test, as a blend of every load in
 * @param isAerated whether it has fans
 * @param outOfService whether it is taking nothing
 */
public record Bin(
        String binId,
        String locationCode,
        BigDecimal capacityBushels,
        Optional<String> commodityCode,
        BigDecimal bushels,
        Map<Factor, BigDecimal> sample,
        boolean isAerated,
        boolean outOfService) {

    /**
     * A bin is called full at this fraction of capacity. Above it the house stops putting grain in,
     * because the last four feet are where the spoilage is.
     */
    public static final BigDecimal WORKING_FILL = new BigDecimal("0.97");

    private static final BigDecimal BLEND_PLACES = new BigDecimal("0.01");
    private static final BigDecimal PERCENT_PLACES = new BigDecimal("0.1");

    public Bin {
        Objects.requireNonNull(binId, "binId");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        sample = Ordered.copyOf(sample);
        if (capacityBushels.signum() <= 0) {
            throw new BinError(binId + ": a bin needs a capacity");
        }
        if (bushels.signum() < 0) {
            throw new BinError(binId + ": contents cannot be negative");
        }
        if (bushels.signum() > 0 && commodityCode.isEmpty()) {
            throw new BinError(binId + ": grain in a bin has to be something");
        }
        if (bushels.compareTo(capacityBushels) > 0) {
            throw new BinError(binId + ": " + bushels + " bushels will not fit in " + capacityBushels);
        }
    }

    /** An empty bin in service. */
    public static Bin empty(String binId, String locationCode, BigDecimal capacityBushels) {
        return new Bin(binId, locationCode, capacityBushels, Optional.empty(), BigDecimal.ZERO, Map.of(), false, false);
    }

    /** The same bin holding something else. */
    public Bin holding(String commodity, BigDecimal amount, Map<Factor, BigDecimal> tested) {
        return new Bin(
                binId,
                locationCode,
                capacityBushels,
                Optional.ofNullable(commodity),
                amount,
                tested,
                isAerated,
                outOfService);
    }

    public Bin withAerated(boolean value) {
        return new Bin(binId, locationCode, capacityBushels, commodityCode, bushels, sample, value, outOfService);
    }

    private Bin withOutOfService(boolean value) {
        return new Bin(binId, locationCode, capacityBushels, commodityCode, bushels, sample, isAerated, value);
    }

    public boolean isEmpty() {
        return bushels.signum() == 0;
    }

    public BigDecimal workingCapacity() {
        return Units.quantize(capacityBushels.multiply(WORKING_FILL), Units.BUSHEL_PLACES);
    }

    /** What can still go in before the bin is called full. */
    public BigDecimal headroom() {
        BigDecimal room = workingCapacity().subtract(bushels);
        return room.signum() > 0 ? room : new BigDecimal("0.0000");
    }

    public BigDecimal percentFull() {
        return Units.quantize(Decimals.divide(bushels, capacityBushels).multiply(Decimals.HUNDRED), PERCENT_PLACES);
    }

    public boolean isFull() {
        return headroom().signum() == 0;
    }

    private boolean holdsOtherThan(String commodity) {
        return commodityCode.isPresent() && !commodityCode.get().equals(commodity);
    }

    public boolean accepts(String commodity, BigDecimal amount) {
        if (outOfService || holdsOtherThan(commodity)) {
            return false;
        }
        return amount.compareTo(headroom()) <= 0;
    }

    /** Put a load in, blending it with what is already there. */
    public Bin fill(String commodity, BigDecimal amount, Map<Factor, BigDecimal> tested) {
        BigDecimal wanted = Units.quantize(amount, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new BinError(binId + ": nothing to put in");
        }
        if (outOfService) {
            throw new ConflictError(binId + " is out of service");
        }
        if (holdsOtherThan(commodity)) {
            throw new ConflictError(binId + " holds " + commodityCode.get() + ", not " + commodity);
        }
        if (wanted.compareTo(headroom()) > 0) {
            throw new BinError(binId + ": only " + headroom() + " bushels of room, asked for " + wanted);
        }
        return holding(
                commodity,
                Units.quantize(bushels.add(wanted), Units.BUSHEL_PLACES),
                blend(sample, bushels, tested, wanted));
    }

    /** Take grain out. What is left tests the same as what came out. */
    public Bin draw(BigDecimal amount) {
        BigDecimal wanted = Units.quantize(amount, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new BinError(binId + ": nothing to draw");
        }
        if (wanted.compareTo(bushels) > 0) {
            throw new BinError(binId + ": only " + bushels + " bushels in it, asked for " + wanted);
        }
        BigDecimal left = Units.quantize(bushels.subtract(wanted), Units.BUSHEL_PLACES);
        if (left.signum() == 0) {
            return holding(null, BigDecimal.ZERO, Map.of());
        }
        return holding(commodityCode.orElse(null), left, sample);
    }

    public Bin takeOutOfService(String reason) {
        if (reason.isEmpty()) {
            throw new BinError(binId + ": needs a reason");
        }
        if (outOfService) {
            throw new ConflictError(binId + " is already out of service");
        }
        return withOutOfService(true);
    }

    public Bin returnToService() {
        if (!outOfService) {
            throw new ConflictError(binId + " is already in service");
        }
        return withOutOfService(false);
    }

    /** Weighted average of the two samples, on the factors both have. */
    static Map<Factor, BigDecimal> blend(
            Map<Factor, BigDecimal> existing,
            BigDecimal existingBushels,
            Map<Factor, BigDecimal> incoming,
            BigDecimal incomingBushels) {
        if (existingBushels.signum() <= 0 || existing.isEmpty()) {
            return incoming;
        }
        BigDecimal total = existingBushels.add(incomingBushels);
        Map<Factor, BigDecimal> blended = new LinkedHashMap<>();
        existing.forEach((factor, value) -> {
            BigDecimal other = incoming.get(factor);
            if (other != null) {
                BigDecimal weighted = value.multiply(existingBushels).add(other.multiply(incomingBushels));
                blended.put(factor, Units.quantize(Decimals.divide(weighted, total), BLEND_PLACES));
            }
        });
        return blended;
    }
}
