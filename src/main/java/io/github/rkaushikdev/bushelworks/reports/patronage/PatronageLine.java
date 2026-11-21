package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One producer's business with the house over one year.
 *
 * @param partyCode the producer
 * @param name the name printed beside the code
 * @param bushels the bushels settled
 * @param gross the gross dollars on the producer's statements
 * @param charges what the house kept off them: the discounts, drying, storage and service charges
 * @param isMember whether the producer is a member of the co-op
 * @param loads how many loads the bushels came in on
 */
public record PatronageLine(
        String partyCode,
        String name,
        BigDecimal bushels,
        BigDecimal gross,
        BigDecimal charges,
        boolean isMember,
        int loads) {

    private static final BigDecimal NO_AVERAGE = new BigDecimal("0.00");

    public PatronageLine {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(charges, "charges");
        if (bushels.signum() < 0) {
            throw new PatronageError(partyCode + ": negative bushels");
        }
        if (loads < 0) {
            throw new PatronageError(partyCode + ": negative loads");
        }
    }

    /** A line to fill in; a producer is a member with no loads until told otherwise. */
    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .partyCode(partyCode)
                .name(name)
                .bushels(bushels)
                .gross(gross)
                .charges(charges)
                .isMember(isMember)
                .loads(loads);
    }

    /** What the house kept, in dollars and cents. */
    public BigDecimal margin() {
        return Money.amount(charges);
    }

    /** What the house kept on each bushel, at four places; nothing on a producer with no bushels. */
    public BigDecimal marginPerBushel() {
        if (bushels.signum() <= 0) {
            return Money.ZERO_PRICE;
        }
        return Units.quantize(Decimals.divide(charges, bushels), Money.PRICE_PLACES);
    }

    /** The bushels on an average load, at bushel places; nothing on a producer with no loads. */
    public BigDecimal averageLoad() {
        if (loads <= 0) {
            return NO_AVERAGE;
        }
        return Units.quantize(Decimals.divide(bushels, loads), Units.BUSHEL_PLACES);
    }

    /** A patronage line assembled a field at a time. */
    public static final class Builder {

        private String partyCode;
        private String name;
        private BigDecimal bushels;
        private BigDecimal gross;
        private BigDecimal charges;
        private boolean isMember = true;
        private int loads;

        private Builder() {}

        public Builder partyCode(String value) {
            this.partyCode = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder bushels(BigDecimal value) {
            this.bushels = value;
            return this;
        }

        public Builder gross(BigDecimal value) {
            this.gross = value;
            return this;
        }

        public Builder charges(BigDecimal value) {
            this.charges = value;
            return this;
        }

        public Builder isMember(boolean value) {
            this.isMember = value;
            return this;
        }

        public Builder loads(int value) {
            this.loads = value;
            return this;
        }

        public PatronageLine build() {
            return new PatronageLine(partyCode, name, bushels, gross, charges, isMember, loads);
        }
    }
}
