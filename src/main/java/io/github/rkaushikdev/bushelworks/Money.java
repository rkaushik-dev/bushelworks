package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Money for grain settlement.
 *
 * <p>Two kinds of number show up on a settlement statement and they round in different places. A
 * <em>price</em> is dollars per bushel carried to four decimals, because a basis of minus
 * twenty-two and a half cents is a real quote. An <em>amount</em> is dollars and cents, and it is
 * the only thing that ever reaches a cheque. Multiplying a four-decimal price by a four-decimal
 * bushel count and rounding once at the end is the whole reason this class exists: rounding in the
 * middle drifts, and the drift lands on the producer.
 */
public final class Money {

    public static final BigDecimal PRICE_PLACES = new BigDecimal("0.0001");
    public static final BigDecimal AMOUNT_PLACES = new BigDecimal("0.01");

    public static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    public static final BigDecimal ZERO_PRICE = new BigDecimal("0.0000");

    private Money() {}

    /** A dollars-per-unit price at four decimals. Negative is legal: basis. */
    public static BigDecimal price(BigDecimal value) {
        return Decimals.quantize(value, PRICE_PLACES);
    }

    /** A dollars-per-unit price at four decimals, from its text. */
    public static BigDecimal price(String value) {
        return price(Decimals.parse(value));
    }

    /** A dollars-and-cents amount. Negative is legal: a deduction. */
    public static BigDecimal amount(BigDecimal value) {
        return Decimals.quantize(value, AMOUNT_PLACES);
    }

    /** A dollars-and-cents amount, from its text. */
    public static BigDecimal amount(String value) {
        return amount(Decimals.parse(value));
    }

    /** A dollars-and-cents amount of a whole number of dollars. */
    public static BigDecimal amount(long value) {
        return amount(BigDecimal.valueOf(value));
    }

    /** Quantity times price, rounded to cents exactly once. */
    public static BigDecimal extend(BigDecimal quantity, BigDecimal unitPrice) {
        return amount(quantity.multiply(unitPrice, Decimals.WIDE));
    }

    /** Sum a run of already-rounded amounts without re-rounding each one. */
    public static BigDecimal total(Iterable<BigDecimal> amounts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal each : amounts) {
            sum = sum.add(each);
        }
        return amount(sum);
    }

    /**
     * Split an amount across weights so the parts add back to the whole.
     *
     * <p>Largest remainder. A two way fifty-fifty split of a settlement ending in an odd cent has to
     * give that cent to somebody, and doing it consistently is the difference between a ledger that
     * balances and one that is off by a penny every third ticket. Ties go to the earlier share,
     * which by convention is the landlord on a crop share lease.
     *
     * <p>Each part is its count of cents over a hundred, with no more places than it needs.
     */
    public static List<BigDecimal> allocate(BigDecimal value, List<BigDecimal> weights) {
        if (weights.isEmpty()) {
            throw new MoneyError("cannot allocate across an empty set of weights");
        }
        BigDecimal weightSum = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            weightSum = weightSum.add(weight);
        }
        if (weightSum.signum() <= 0) {
            throw new MoneyError("allocation weights must sum to more than zero: " + weightSum);
        }

        BigDecimal target = amount(value);
        long cents = target.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long sign = cents < 0 ? -1 : 1;
        cents = Math.abs(cents);

        List<BigDecimal> raw = new ArrayList<>(weights.size());
        long[] floors = new long[weights.size()];
        long floorSum = 0;
        for (int index = 0; index < weights.size(); index++) {
            BigDecimal share = Decimals.divide(BigDecimal.valueOf(cents).multiply(weights.get(index)), weightSum);
            raw.add(share);
            floors[index] = share.setScale(0, RoundingMode.FLOOR).longValueExact();
            floorSum += floors[index];
        }
        long remainder = cents - floorSum;

        Comparator<Integer> largestFraction = Comparator.comparing(
                (Integer index) -> raw.get(index).subtract(BigDecimal.valueOf(floors[index])),
                Comparator.reverseOrder());
        List<Integer> order = IntStream.range(0, weights.size())
                .boxed()
                .sorted(largestFraction.thenComparing(Comparator.naturalOrder()))
                .toList();
        for (long step = 0; step < remainder; step++) {
            floors[order.get((int) (step % order.size()))] += 1;
        }

        List<BigDecimal> parts = new ArrayList<>(floors.length);
        for (long part : floors) {
            parts.add(Decimals.divide(BigDecimal.valueOf(sign * part), Decimals.HUNDRED));
        }
        return List.copyOf(parts);
    }

    /** The same amount the other way round, in cents. */
    public static BigDecimal negate(BigDecimal value) {
        return amount(value.negate());
    }

    /** Render an amount the way the statement printer wants it: grouped, a debit in brackets. */
    public static String formatAmount(BigDecimal value) {
        BigDecimal rounded = amount(value);
        if (rounded.signum() < 0) {
            return "(" + String.format(Locale.ROOT, "%,.2f", rounded.negate()) + ")";
        }
        return String.format(Locale.ROOT, "%,.2f", rounded);
    }

    /** A price at its four places. */
    public static String formatPrice(BigDecimal value) {
        return price(value).toPlainString();
    }
}
