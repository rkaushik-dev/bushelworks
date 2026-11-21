package io.github.rkaushikdev.bushelworks.reports.licence;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One site's warehouse return for one month.
 *
 * <p>The obligation is the figure that matters, and it is not what is in the bins. It is storage
 * plus deferred price plus warehouse receipts outstanding, and a house that reports bin contents
 * instead has reported the wrong number in the producer's favour and its own disfavour.
 *
 * @param locationCode the site, or {@code *} for the whole house
 * @param licenceNumber the warehouse licence the site holds
 * @param asOf the last day of the month the return is for
 * @param licensedCapacity the bushels the licence allows the site to hold
 * @param storageObligation bushels held in storage for producers
 * @param deferredPrice bushels delivered and not yet priced
 * @param receiptsOutstanding bushels under warehouse receipts not yet cancelled
 * @param houseOwned bushels the house owns outright
 */
public record LicenceReturn(
        String locationCode,
        String licenceNumber,
        LocalDate asOf,
        BigDecimal licensedCapacity,
        BigDecimal storageObligation,
        BigDecimal deferredPrice,
        BigDecimal receiptsOutstanding,
        BigDecimal houseOwned) {

    private static final BigDecimal PERCENT_PLACES = new BigDecimal("0.1");

    public LicenceReturn {
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(licenceNumber, "licenceNumber");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(licensedCapacity, "licensedCapacity");
        if (licensedCapacity.signum() <= 0) {
            throw new LicenceError(locationCode + ": no licensed capacity");
        }
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put("storage_obligation", storageObligation);
        components.put("deferred_price", deferredPrice);
        components.put("receipts_outstanding", receiptsOutstanding);
        components.put("house_owned", houseOwned);
        components.forEach((name, value) -> {
            if (value.signum() < 0) {
                throw new LicenceError(locationCode + ": " + name + " is negative");
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .locationCode(locationCode)
                .licenceNumber(licenceNumber)
                .asOf(asOf)
                .licensedCapacity(licensedCapacity)
                .storageObligation(storageObligation)
                .deferredPrice(deferredPrice)
                .receiptsOutstanding(receiptsOutstanding)
                .houseOwned(houseOwned);
    }

    /** Everything the house owes somebody in grain, at bushel places. */
    public BigDecimal obligation() {
        return Units.quantize(storageObligation.add(deferredPrice).add(receiptsOutstanding), Units.BUSHEL_PLACES);
    }

    /** The obligation and the house's own grain together, at bushel places. */
    public BigDecimal totalHeld() {
        return Units.quantize(obligation().add(houseOwned), Units.BUSHEL_PLACES);
    }

    /** What the licence still allows, at bushel places; below zero when the site is over. */
    public BigDecimal unusedCapacity() {
        return Units.quantize(licensedCapacity.subtract(totalHeld()), Units.BUSHEL_PLACES);
    }

    /** Holding more than the licence allows, which is a licence problem and not a logistics one. */
    public boolean isOverCapacity() {
        return totalHeld().compareTo(licensedCapacity) > 0;
    }

    /** Over capacity, or with no more than {@link Licence#TIGHT_MARGIN} of the licence left. */
    public boolean isTight() {
        if (isOverCapacity()) {
            return true;
        }
        return unusedCapacity().compareTo(licensedCapacity.multiply(Licence.TIGHT_MARGIN)) <= 0;
    }

    /** What is held as a percentage of the licence, to a tenth. */
    public BigDecimal percentUsed() {
        return Units.quantize(
                Decimals.divide(totalHeld(), licensedCapacity).multiply(Decimals.HUNDRED), PERCENT_PLACES);
    }

    /** A warehouse return assembled a field at a time. */
    public static final class Builder {

        private String locationCode;
        private String licenceNumber;
        private LocalDate asOf;
        private BigDecimal licensedCapacity;
        private BigDecimal storageObligation;
        private BigDecimal deferredPrice;
        private BigDecimal receiptsOutstanding;
        private BigDecimal houseOwned;

        private Builder() {}

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder licenceNumber(String value) {
            this.licenceNumber = value;
            return this;
        }

        public Builder asOf(LocalDate value) {
            this.asOf = value;
            return this;
        }

        public Builder licensedCapacity(BigDecimal value) {
            this.licensedCapacity = value;
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

        public Builder receiptsOutstanding(BigDecimal value) {
            this.receiptsOutstanding = value;
            return this;
        }

        public Builder houseOwned(BigDecimal value) {
            this.houseOwned = value;
            return this;
        }

        public LicenceReturn build() {
            return new LicenceReturn(
                    locationCode,
                    licenceNumber,
                    asOf,
                    licensedCapacity,
                    storageObligation,
                    deferredPrice,
                    receiptsOutstanding,
                    houseOwned);
        }
    }
}
