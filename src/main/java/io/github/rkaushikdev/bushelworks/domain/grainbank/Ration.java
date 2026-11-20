package io.github.rkaushikdev.bushelworks.domain.grainbank;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One feed formula, by how much of the account's grain it uses.
 *
 * <p>Withdrawals are measured in tons of finished feed, not bushels of corn, and the conversion is
 * the ration's inclusion rate. A ration that is eighty percent corn takes eight tenths of a ton of
 * corn out of the account per ton of feed, plus what the mill loses.
 *
 * @param code the ration's code
 * @param name its name
 * @param inclusionPercent how much of a ton of feed is the producer's grain
 * @param processingPerTon what the mill charges per ton made
 * @param standardTestWeight pounds per bushel of the grain going in
 */
public record Ration(
        String code,
        String name,
        BigDecimal inclusionPercent,
        BigDecimal processingPerTon,
        BigDecimal standardTestWeight) {

    /** What the mill loses grinding and mixing, as a percentage of what goes in. */
    public static final BigDecimal MILL_SHRINK_PERCENT = new BigDecimal("0.5");

    public Ration {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(standardTestWeight, "standardTestWeight");
        if (inclusionPercent.signum() <= 0 || inclusionPercent.compareTo(Decimals.HUNDRED) > 0) {
            throw new GrainBankError(code + ": inclusion out of range");
        }
        if (processingPerTon.signum() < 0) {
            throw new GrainBankError(code + ": processing cannot be negative");
        }
    }

    /** A ration of corn at fifty six pounds a bushel. */
    public Ration(String code, String name, BigDecimal inclusionPercent, BigDecimal processingPerTon) {
        this(code, name, inclusionPercent, processingPerTon, new BigDecimal("56"));
    }

    /** Bushels of the producer's corn one order of feed takes out. */
    public BigDecimal bushelsFor(BigDecimal tons) {
        if (tons.signum() <= 0) {
            throw new GrainBankError(code + ": cannot make " + tons + " tons");
        }
        BigDecimal grainPounds =
                Decimals.divide(tons.multiply(Units.POUNDS_PER_TON).multiply(inclusionPercent), Decimals.HUNDRED);
        BigDecimal withShrink =
                Decimals.divide(grainPounds.multiply(Decimals.HUNDRED.add(MILL_SHRINK_PERCENT)), Decimals.HUNDRED);
        return Units.quantize(Decimals.divide(withShrink, standardTestWeight), Units.BUSHEL_PLACES);
    }

    public BigDecimal processingCharge(BigDecimal tons) {
        return Money.extend(tons, processingPerTon);
    }

    /** The three rations the mill runs, by code. */
    public static Map<String, Ration> standard() {
        Map<String, Ration> rations = new LinkedHashMap<>();
        for (Ration ration : List.of(
                new Ration("GROW", "Growing ration", new BigDecimal("70"), new BigDecimal("14.00")),
                new Ration("FIN", "Finishing ration", new BigDecimal("85"), new BigDecimal("12.50")),
                new Ration("CRACK", "Cracked corn", new BigDecimal("100"), new BigDecimal("8.00")))) {
            rations.put(ration.code(), ration);
        }
        return rations;
    }
}
