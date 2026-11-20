package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * What a settlement line is, which decides both its sign and where it prints.
 *
 * <p>The members are declared in statement order, and the order is not cosmetic. Assessments are
 * levied on the value after discounts, so the discount lines have to be settled before the
 * assessment lines can be computed, and an advance is recovered after everything else because it
 * is a repayment rather than a charge against the grain:
 *
 * <ol>
 *   <li>gross value of the priced bushels
 *   <li>quality discounts, which reduce the value
 *   <li>drying, which is a service and not a quality discount
 *   <li>storage and service charges, which are also services
 *   <li>assessments, levied on the value left after the discounts
 *   <li>advances and other deductions, recovered last
 * </ol>
 */
public enum LineKind implements ValueEnum {
    GROSS("gross", false),
    DISCOUNT("discount", true),
    DRYING("drying", true),
    STORAGE("storage", true),
    SERVICE("service", true),
    ASSESSMENT("assessment", true),
    ADVANCE("advance", true),
    DEDUCTION("deduction", true),
    ADJUSTMENT("adjustment", false);

    private final String value;
    private final boolean reducing;

    LineKind(String value, boolean reducing) {
        this.value = value;
        this.reducing = reducing;
    }

    @Override
    public String value() {
        return value;
    }

    /** Whether a line of this kind reduces the net. Everything else adds to it. */
    public boolean reducing() {
        return reducing;
    }

    /** Where lines of this kind print: gross first, adjustments last. */
    public int position() {
        return ordinal();
    }

    public static LineKind of(String value) {
        return ValueEnum.of(LineKind.class, value);
    }
}
