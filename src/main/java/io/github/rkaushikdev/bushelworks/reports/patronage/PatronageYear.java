package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everybody who did business with one house over one year.
 *
 * <p>The two rankings answer different questions. The man with four hundred thousand bushels of
 * contracted corn that moved straight through on rail is worth less to the house than the man with
 * sixty thousand bushels that sat in a bin for nine months and went through the dryer, so the year
 * ranks on bushels and on margin side by side.
 *
 * @param locationCode the house, or a code standing for several rolled into one
 * @param opens the first day of the year
 * @param closes the last day of the year
 * @param lines one per producer, no producer twice
 */
public record PatronageYear(String locationCode, LocalDate opens, LocalDate closes, List<PatronageLine> lines) {

    /** How many of the biggest producers {@link #concentration()} counts. */
    public static final int CONCENTRATION_COUNT = 10;

    private static final BigDecimal PERCENT_PLACES = new BigDecimal("0.0001");
    private static final BigDecimal NO_SHARE = new BigDecimal("0.0000");

    private static final Comparator<PatronageLine> BY_BUSHELS = Comparator.comparing(
                    PatronageLine::bushels, Comparator.reverseOrder())
            .thenComparing(PatronageLine::partyCode);

    private static final Comparator<PatronageLine> BY_MARGIN = Comparator.comparing(
                    PatronageLine::margin, Comparator.reverseOrder())
            .thenComparing(PatronageLine::partyCode);

    public PatronageYear {
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        lines = List.copyOf(lines);
        if (closes.isBefore(opens)) {
            throw new PatronageError(locationCode + ": the year closes before it opens");
        }
        long producers = lines.stream().map(PatronageLine::partyCode).distinct().count();
        if (producers != lines.size()) {
            throw new PatronageError(locationCode + ": a producer appears twice");
        }
    }

    /** A year nobody delivered in. */
    public PatronageYear(String locationCode, LocalDate opens, LocalDate closes) {
        this(locationCode, opens, closes, List.of());
    }

    /** Every producer's bushels, at bushel places. */
    public BigDecimal bushels() {
        return Units.quantize(sumOfBushels(lines), Units.BUSHEL_PLACES);
    }

    /** Every producer's gross dollars. */
    public BigDecimal gross() {
        return Money.total(lines.stream().map(PatronageLine::gross).toList());
    }

    /** What the house kept off everybody. */
    public BigDecimal margin() {
        return Money.total(lines.stream().map(PatronageLine::margin).toList());
    }

    /** The producers, the most bushels first, a tie going to the lower code. */
    public List<PatronageLine> byBushels() {
        return lines.stream().sorted(BY_BUSHELS).toList();
    }

    /** The producers, the most margin first, a tie going to the lower code. */
    public List<PatronageLine> byMargin() {
        return lines.stream().sorted(BY_MARGIN).toList();
    }

    /**
     * The first {@code count} producers by bushels. A negative count leaves that many off the end of
     * the ranking instead.
     */
    public List<PatronageLine> top(int count) {
        return List.copyOf(head(byBushels(), count));
    }

    /** The members, in the order the lines were given. */
    public List<PatronageLine> members() {
        return lines.stream().filter(PatronageLine::isMember).toList();
    }

    /**
     * One producer's bushels as a percentage of the year, at four places.
     *
     * @throws PatronageError when the producer did not deliver in this year
     */
    public BigDecimal shareOfBushels(String partyCode) {
        for (PatronageLine line : lines) {
            if (line.partyCode().equals(partyCode)) {
                return share(line.bushels(), bushels());
            }
        }
        throw new PatronageError(partyCode + " did not deliver in this year");
    }

    /** What share of the year's bushels the ten biggest producers account for. */
    public BigDecimal concentration() {
        return concentration(CONCENTRATION_COUNT);
    }

    /**
     * What share of the year's bushels the biggest {@code count} producers account for, as a
     * percentage at four places.
     *
     * <p>A house where ten producers are eighty percent of the bushels has a different risk than one
     * where they are thirty, and the board should be told which one it is running.
     */
    public BigDecimal concentration(int count) {
        return share(sumOfBushels(head(byBushels(), count)), bushels());
    }

    /** Split a patronage pool across the members by their bushels, in member order. */
    public Map<String, BigDecimal> allocations(BigDecimal pool) {
        return allocations(pool, AllocationBasis.BUSHELS);
    }

    /**
     * Split a patronage pool across the members on {@code basis}, in member order.
     *
     * <p>Each share is rounded to the cent, and whatever rounding leaves over goes to the member who
     * weighs the most, because it has to go somewhere and that is where it is least visible. A year
     * in which no member weighs anything splits the pool nowhere.
     */
    public Map<String, BigDecimal> allocations(BigDecimal pool, AllocationBasis basis) {
        BigDecimal whole = Money.amount(pool);
        List<PatronageLine> members = members();
        BigDecimal base = BigDecimal.ZERO;
        for (PatronageLine line : members) {
            base = base.add(basis.weightOf(line));
        }
        if (base.signum() <= 0) {
            return Map.of();
        }
        Map<String, BigDecimal> split = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (PatronageLine line : members) {
            BigDecimal share = Money.amount(Decimals.divide(whole.multiply(basis.weightOf(line)), base));
            split.put(line.partyCode(), share);
            allocated = allocated.add(share);
        }
        BigDecimal drift = whole.subtract(allocated);
        if (drift.signum() != 0) {
            PatronageLine heaviest = members.stream()
                    .max(Comparator.comparing(basis::weightOf).thenComparing(PatronageLine::partyCode))
                    .orElseThrow();
            split.computeIfPresent(heaviest.partyCode(), (code, share) -> Money.amount(share.add(drift)));
        }
        return Ordered.copyOf(split);
    }

    /**
     * Each member's refund out of a pool, allocated on {@code basis} and split between cash and
     * retained equity at {@code cashPercent}, in member order.
     *
     * @throws PatronageError when the notices are qualified and the cash is under a fifth
     */
    public List<PatronageRefund> refunds(
            BigDecimal pool, AllocationBasis basis, NoticeKind notice, BigDecimal cashPercent) {
        return allocations(pool, basis).entrySet().stream()
                .map(share -> PatronageRefund.of(share.getKey(), notice, share.getValue(), cashPercent))
                .toList();
    }

    /** {@code part} as a percentage of {@code total} at four places; nothing of a total of nothing. */
    static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total.signum() <= 0) {
            return NO_SHARE;
        }
        return Units.quantize(Decimals.divide(part, total).multiply(Decimals.HUNDRED), PERCENT_PLACES);
    }

    /**
     * The first {@code count} items, or all but the last {@code -count} when the count is negative,
     * and never fewer than none.
     */
    static <T> List<T> head(List<T> items, int count) {
        int end = count >= 0 ? Math.min(count, items.size()) : Math.max(0, items.size() + count);
        return items.subList(0, end);
    }

    private static BigDecimal sumOfBushels(List<PatronageLine> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PatronageLine line : lines) {
            sum = sum.add(line.bushels());
        }
        return sum;
    }
}
