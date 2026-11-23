package io.github.rkaushikdev.bushelworks.domain.position;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One commodity at one location on one day: what the house owns and what it owes.
 *
 * <p>Grain in the bin is not the same as grain the house owns. The identity the position report
 * has to satisfy every night:
 *
 * <pre>
 *     owned    = on_hand - storage_obligation - grain_bank - deferred_price
 *     net_long = owned + deferred_price + purchased_forward - sold_forward + in_transit
 * </pre>
 *
 * <p>Storage bushels belong to the producer and carry no price risk for the house. Deferred price
 * bushels have been delivered and title has passed, but the price has not been agreed, so the house
 * is long them and must hedge them. Missing that puts a house short into a rally with no idea it is
 * there. Grain bank bushels are the cattlemen's, held to come back as feed, and they are as far
 * outside the house's price risk as storage is; counting them as owned hedges somebody else's corn.
 *
 * @param asOf the day
 * @param commodityCode the commodity
 * @param locationCode the site, or {@code *} for a rolled up line
 * @param onHand bushels in the bins
 * @param storageObligation bushels held for producers
 * @param deferredPrice bushels delivered and not yet priced
 * @param purchasedForward bushels bought and not yet delivered
 * @param soldForward bushels sold and not yet shipped
 * @param inTransit bushels on the road
 * @param grainBank bushels held in feed accounts, to go back out as ground feed
 */
public record PositionLine(
        LocalDate asOf,
        String commodityCode,
        String locationCode,
        BigDecimal onHand,
        BigDecimal storageObligation,
        BigDecimal deferredPrice,
        BigDecimal purchasedForward,
        BigDecimal soldForward,
        BigDecimal inTransit,
        BigDecimal grainBank) {

    public PositionLine {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put("on_hand", onHand);
        components.put("storage_obligation", storageObligation);
        components.put("deferred_price", deferredPrice);
        components.put("purchased_forward", purchasedForward);
        components.put("sold_forward", soldForward);
        components.put("in_transit", inTransit);
        components.put("grain_bank", grainBank);
        components.forEach((name, value) -> {
            if (value.signum() < 0) {
                throw new PositionError(commodityCode + "/" + locationCode + ": " + name + " is negative");
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .asOf(asOf)
                .commodityCode(commodityCode)
                .locationCode(locationCode)
                .onHand(onHand)
                .storageObligation(storageObligation)
                .deferredPrice(deferredPrice)
                .purchasedForward(purchasedForward)
                .soldForward(soldForward)
                .inTransit(inTransit)
                .grainBank(grainBank);
    }

    /** Bushels in the bin that belong to the house: not storage, not grain bank, not unpriced. */
    public BigDecimal owned() {
        return Units.quantize(
                onHand.subtract(storageObligation).subtract(grainBank).subtract(deferredPrice), Units.BUSHEL_PLACES);
    }

    /**
     * The number the hedge desk works from.
     *
     * <p>Deferred price bushels are added back because the house carries their price risk even
     * though it has not paid for them yet.
     */
    public BigDecimal netLong() {
        return Units.quantize(
                owned().add(deferredPrice)
                        .add(purchasedForward)
                        .subtract(soldForward)
                        .add(inTransit),
                Units.BUSHEL_PLACES);
    }

    public boolean isShort() {
        return netLong().signum() < 0;
    }

    /** Everything the house owes somebody in bushels, the feed accounts included. */
    public BigDecimal obligation() {
        return Units.quantize(
                storageObligation.add(grainBank).add(deferredPrice).add(soldForward), Units.BUSHEL_PLACES);
    }

    /** Add two lines for the same commodity on the same day. Two sites roll up under {@code *}. */
    public PositionLine combine(PositionLine other) {
        if (!commodityCode.equals(other.commodityCode)) {
            throw new PositionError("cannot combine positions in different commodities");
        }
        if (!asOf.equals(other.asOf)) {
            throw new PositionError("cannot combine positions from different days");
        }
        String location = locationCode.equals(other.locationCode) ? locationCode : "*";
        return new PositionLine(
                asOf,
                commodityCode,
                location,
                onHand.add(other.onHand),
                storageObligation.add(other.storageObligation),
                deferredPrice.add(other.deferredPrice),
                purchasedForward.add(other.purchasedForward),
                soldForward.add(other.soldForward),
                inTransit.add(other.inTransit),
                grainBank.add(other.grainBank));
    }

    /** A position line assembled a field at a time; every quantity starts at zero. */
    public static final class Builder {

        private LocalDate asOf;
        private String commodityCode;
        private String locationCode;
        private BigDecimal onHand = BigDecimal.ZERO;
        private BigDecimal storageObligation = BigDecimal.ZERO;
        private BigDecimal deferredPrice = BigDecimal.ZERO;
        private BigDecimal purchasedForward = BigDecimal.ZERO;
        private BigDecimal soldForward = BigDecimal.ZERO;
        private BigDecimal inTransit = BigDecimal.ZERO;
        private BigDecimal grainBank = BigDecimal.ZERO;

        private Builder() {}

        public Builder asOf(LocalDate value) {
            this.asOf = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder onHand(BigDecimal value) {
            this.onHand = value;
            return this;
        }

        public Builder storageObligation(BigDecimal value) {
            this.storageObligation = value;
            return this;
        }

        public Builder deferredPrice(BigDecimal value) {
            this.deferredPrice = value;
            return this;
        }

        public Builder purchasedForward(BigDecimal value) {
            this.purchasedForward = value;
            return this;
        }

        public Builder soldForward(BigDecimal value) {
            this.soldForward = value;
            return this;
        }

        public Builder inTransit(BigDecimal value) {
            this.inTransit = value;
            return this;
        }

        public Builder grainBank(BigDecimal value) {
            this.grainBank = value;
            return this;
        }

        public PositionLine build() {
            return new PositionLine(
                    asOf,
                    commodityCode,
                    locationCode,
                    onHand,
                    storageObligation,
                    deferredPrice,
                    purchasedForward,
                    soldForward,
                    inTransit,
                    grainBank);
        }
    }
}
