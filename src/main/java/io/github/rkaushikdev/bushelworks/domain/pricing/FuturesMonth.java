package io.github.rkaushikdev.bushelworks.domain.pricing;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A futures contract month such as CZ4, written here as a commodity, a month code and a year.
 *
 * <p>Month codes are the CME grain codes. Months are ordered by year then by the month letter's
 * position in the delivery cycle, never alphabetically, which is the mistake that puts December
 * before July.
 *
 * @param commodityCode the commodity the month trades
 * @param monthCode the delivery month letter
 * @param year the four digit year
 */
public record FuturesMonth(String commodityCode, String monthCode, int year) implements Comparable<FuturesMonth> {

    /** Each delivery month letter's calendar month. */
    public static final Map<String, Integer> MONTH_CODES = Map.ofEntries(
            Map.entry("F", 1),
            Map.entry("G", 2),
            Map.entry("H", 3),
            Map.entry("J", 4),
            Map.entry("K", 5),
            Map.entry("M", 6),
            Map.entry("N", 7),
            Map.entry("Q", 8),
            Map.entry("U", 9),
            Map.entry("V", 10),
            Map.entry("X", 11),
            Map.entry("Z", 12));

    /** The months each commodity trades, in cycle order. */
    public static final Map<String, List<String>> DELIVERY_MONTHS = Map.of(
            "CORN", List.of("H", "K", "N", "U", "Z"),
            "SOYB", List.of("F", "H", "K", "N", "Q", "U", "X"),
            "HRW", List.of("H", "K", "N", "U", "Z"),
            "MILO", List.of("H", "K", "N", "U", "Z"));

    /** Year first, then the month's place in the calendar. */
    public static final Comparator<FuturesMonth> ORDER =
            Comparator.comparingInt(FuturesMonth::year).thenComparingInt(FuturesMonth::monthNumber);

    public FuturesMonth {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(monthCode, "monthCode");
        if (!MONTH_CODES.containsKey(monthCode)) {
            throw new PricingError(monthCode + " is not a delivery month code");
        }
        List<String> allowed = DELIVERY_MONTHS.get(commodityCode);
        if (allowed != null && !allowed.contains(monthCode)) {
            throw new PricingError(commodityCode + " does not trade a " + monthCode + " contract");
        }
        if (year < 2000 || year > 2100) {
            throw new PricingError("implausible contract year " + year);
        }
    }

    public int monthNumber() {
        return MONTH_CODES.get(monthCode);
    }

    /** The exchange symbol: commodity, month letter, two digit year. */
    public String symbol() {
        return commodityCode + monthCode + String.format("%02d", year % 100);
    }

    /**
     * The first of the delivery month.
     *
     * <p>The exchange calendar lives elsewhere. What this package needs is an ordering and a rough
     * cut off for rolling a hedge to arrive contract, and the first of the delivery month is the
     * convention the office uses.
     */
    public LocalDate firstNotice() {
        return LocalDate.of(year, monthNumber(), 1);
    }

    public boolean isBefore(FuturesMonth other) {
        return ORDER.compare(this, other) < 0;
    }

    @Override
    public int compareTo(FuturesMonth other) {
        return ORDER.compare(this, other);
    }

    /** The next tradable month for the same commodity. */
    public FuturesMonth next() {
        List<String> cycle = DELIVERY_MONTHS.get(commodityCode);
        if (cycle == null || cycle.isEmpty()) {
            throw new PricingError("no delivery cycle known for " + commodityCode);
        }
        int index = cycle.indexOf(monthCode);
        if (index + 1 < cycle.size()) {
            return new FuturesMonth(commodityCode, cycle.get(index + 1), year);
        }
        return new FuturesMonth(commodityCode, cycle.get(0), year + 1);
    }

    /** Months in delivery order. */
    public static List<FuturesMonth> order(List<FuturesMonth> months) {
        return months.stream().sorted(ORDER).toList();
    }
}
