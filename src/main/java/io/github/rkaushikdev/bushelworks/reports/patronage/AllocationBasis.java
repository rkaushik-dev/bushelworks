package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.math.BigDecimal;

/**
 * What a member's share of the patronage pool is measured by.
 *
 * <p>A marketing co-op allocates on the bushels each member brought in. One whose earnings come
 * from the dryer, the storage and the discounts allocates on the margin each member's business left
 * behind, because the man whose corn went straight through on rail earned the co-op next to
 * nothing. The board picks one in its bylaws and the year is allocated on it.
 */
public enum AllocationBasis implements ValueEnum {
    BUSHELS("bushels"),
    MARGIN("margin");

    private final String value;

    AllocationBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static AllocationBasis of(String value) {
        return ValueEnum.of(AllocationBasis.class, value);
    }

    /** What one line weighs on this basis; a member whose margin went negative weighs nothing. */
    public BigDecimal weightOf(PatronageLine line) {
        BigDecimal weight = this == BUSHELS ? line.bushels() : line.margin();
        return weight.signum() > 0 ? weight : BigDecimal.ZERO;
    }
}
