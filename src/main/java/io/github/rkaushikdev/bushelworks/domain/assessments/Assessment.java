package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One checkoff levy, as published by one commission.
 *
 * <p>Two bases are in use. Per bushel is the common one. Percent of net value shows up in the
 * soybean orders and it is levied on the value <em>before</em> discounts in some states and after
 * in others, which is why the basis carries a flag for it rather than being inferred.
 *
 * @param code the levy's code on the statement
 * @param name the levy's name
 * @param authority the commission it is remitted to
 * @param commodityCode the commodity it is levied on
 * @param rate dollars per bushel, or a percentage of value
 * @param basis which of the two the rate is
 * @param valueBasis which value a percentage is taken of
 * @param refundable whether the producer may ask for it back
 * @param stateCode the state it applies in, or {@code *} for every state
 */
public record Assessment(
        String code,
        String name,
        String authority,
        String commodityCode,
        BigDecimal rate,
        AssessmentBasis basis,
        ValueBasis valueBasis,
        boolean refundable,
        String stateCode) {

    private static final BigDecimal LARGEST_PERCENT = new BigDecimal("5");

    public Assessment {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(valueBasis, "valueBasis");
        Objects.requireNonNull(stateCode, "stateCode");
        if (rate.signum() < 0) {
            throw new AssessmentError(code + ": an assessment rate cannot be negative");
        }
        if (basis == AssessmentBasis.PERCENT_OF_VALUE && rate.compareTo(LARGEST_PERCENT) > 0) {
            throw new AssessmentError(code + ": " + rate + "% is too large to be a checkoff");
        }
    }

    /** A per bushel levy on the net value, not refundable, in every state. */
    public static Assessment of(String code, String name, String authority, String commodityCode, BigDecimal rate) {
        return new Assessment(
                code,
                name,
                authority,
                commodityCode,
                rate,
                AssessmentBasis.PER_BUSHEL,
                ValueBasis.NET_OF_DISCOUNTS,
                false,
                "*");
    }

    public Assessment withBasis(AssessmentBasis value) {
        return new Assessment(code, name, authority, commodityCode, rate, value, valueBasis, refundable, stateCode);
    }

    public Assessment withValueBasis(ValueBasis value) {
        return new Assessment(code, name, authority, commodityCode, rate, basis, value, refundable, stateCode);
    }

    public Assessment withRefundable(boolean value) {
        return new Assessment(code, name, authority, commodityCode, rate, basis, valueBasis, value, stateCode);
    }

    public Assessment withStateCode(String value) {
        return new Assessment(code, name, authority, commodityCode, rate, basis, valueBasis, refundable, value);
    }

    public boolean appliesIn(String state) {
        return stateCode.equals("*") || stateCode.equals(state);
    }

    /** The dollars to withhold from this settlement for this levy. */
    public BigDecimal withhold(BigDecimal bushels, BigDecimal grossValue, BigDecimal netValue) {
        if (basis == AssessmentBasis.PER_BUSHEL) {
            return Money.extend(bushels, rate);
        }
        BigDecimal base = valueBasis == ValueBasis.GROSS ? grossValue : netValue;
        if (base.signum() <= 0) {
            return Money.amount("0.00");
        }
        return Money.amount(Decimals.divide(base.multiply(rate), Decimals.HUNDRED));
    }
}
