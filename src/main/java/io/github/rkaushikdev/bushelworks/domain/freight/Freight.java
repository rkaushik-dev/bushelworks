package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** Comparing freight quotes and reconciling a carrier's invoice. */
public final class Freight {

    private Freight() {}

    /** The cheapest quote for a real load, compared per bushel; the first of several equal ones. */
    public static FreightRate cheapest(List<FreightRate> rates, BigDecimal bushels, BigDecimal pounds) {
        if (rates.isEmpty()) {
            throw new FreightError("no rates to compare");
        }
        return rates.stream()
                .min(Comparator.comparing(rate -> rate.perBushel(bushels, pounds)))
                .orElseThrow();
    }

    public static BigDecimal invoiceTotal(List<Haul> hauls) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Haul haul : hauls) {
            sum = sum.add(haul.total());
        }
        return Money.amount(sum);
    }

    /**
     * The gap between what the carrier billed and what the rates say.
     *
     * <p>Positive means the carrier billed more than the rate. That is the one the office chases,
     * and it is almost always a fuel surcharge from the wrong week.
     */
    public static BigDecimal reconcileInvoice(List<Haul> hauls, BigDecimal invoiced) {
        return Money.amount(invoiced.subtract(invoiceTotal(hauls)));
    }
}
