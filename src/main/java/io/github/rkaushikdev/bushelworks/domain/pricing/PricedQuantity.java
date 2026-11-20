package io.github.rkaushikdev.bushelworks.domain.pricing;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Bushels at a price, which is what every settlement line reduces to.
 *
 * @param bushels how many
 * @param unitPrice dollars per bushel
 */
public record PricedQuantity(BigDecimal bushels, BigDecimal unitPrice) {

    public PricedQuantity {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (bushels.signum() < 0) {
            throw new PricingError("cannot price a negative quantity");
        }
    }

    /** The lot's value in dollars and cents. */
    public BigDecimal value() {
        return Money.extend(bushels, unitPrice);
    }

    /**
     * The weighted average price of several priced lots.
     *
     * <p>Used when a producer prices half a bin in March and the rest in July and then asks what he
     * averaged.
     */
    public static BigDecimal blend(List<PricedQuantity> lots) {
        BigDecimal totalBushels = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (PricedQuantity lot : lots) {
            totalBushels = totalBushels.add(lot.bushels());
            weighted = weighted.add(lot.bushels().multiply(lot.unitPrice()));
        }
        if (totalBushels.signum() <= 0) {
            throw new PricingError("cannot blend a price across no bushels");
        }
        return Units.quantize(Decimals.divide(weighted, totalBushels), Money.PRICE_PLACES);
    }
}
