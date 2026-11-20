package io.github.rkaushikdev.bushelworks.domain.commodity;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One row of the commodity master.
 *
 * <p>The standard test weight decides how many bushels a load is, the base moisture decides how
 * much of it shrinks away, and the factor set decides which columns the grader has to fill in
 * before a ticket can be graded at all.
 *
 * @param code the commodity code every other rule hangs off
 * @param name the name a statement prints
 * @param standardTestWeight pounds per bushel
 * @param baseMoisture the moisture percent the price assumes
 * @param priceUnit what the posted price is quoted per
 * @param requiredFactors factors a sample must carry before it can be graded
 * @param optionalFactors factors a sample may carry
 */
public record Commodity(
        String code,
        String name,
        BigDecimal standardTestWeight,
        BigDecimal baseMoisture,
        PriceUnit priceUnit,
        Set<Factor> requiredFactors,
        Set<Factor> optionalFactors) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Commodity {
        Objects.requireNonNull(code, "code");
        requiredFactors = Set.copyOf(requiredFactors);
        optionalFactors = Set.copyOf(optionalFactors);
        if (standardTestWeight.signum() <= 0) {
            throw new IllegalArgumentException(code + ": standard test weight must be positive");
        }
        if (baseMoisture.signum() <= 0 || baseMoisture.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException(code + ": base moisture out of range");
        }
        Set<Factor> overlap = EnumSet.noneOf(Factor.class);
        overlap.addAll(requiredFactors);
        overlap.retainAll(optionalFactors);
        if (!overlap.isEmpty()) {
            String names = overlap.stream().map(Factor::value).sorted().collect(Collectors.joining(", "));
            throw new IllegalArgumentException(code + ": factor listed twice: " + names);
        }
    }

    /** A commodity with no factors listed. */
    public Commodity(String code, String name, BigDecimal standardTestWeight, BigDecimal baseMoisture, PriceUnit unit) {
        this(code, name, standardTestWeight, baseMoisture, unit, Set.of(), Set.of());
    }

    /** Every factor the grader may record: the required ones and the optional ones. */
    public Set<Factor> gradedFactors() {
        Set<Factor> all = EnumSet.noneOf(Factor.class);
        all.addAll(requiredFactors);
        all.addAll(optionalFactors);
        return Set.copyOf(all);
    }

    public boolean accepts(Factor factor) {
        return requiredFactors.contains(factor) || optionalFactors.contains(factor);
    }
}
