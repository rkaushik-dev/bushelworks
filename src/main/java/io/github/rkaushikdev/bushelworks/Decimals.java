package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Exact decimal arithmetic with one set of rules for the whole package.
 *
 * <p>Sums and products are exact. A quotient is carried to 28 significant digits, a tie going to
 * the even digit, and keeps the scale the operands imply when it comes out exact, so {@code 6 / 2}
 * is {@code 3} and {@code 6.0 / 2} is {@code 3.0}. Every value that reaches a statement is then
 * rounded once, at a stated number of places, a half going up.
 */
public final class Decimals {

    /** Twenty-eight significant digits, ties to even: the working precision of a quotient. */
    public static final MathContext WORKING = new MathContext(28, RoundingMode.HALF_EVEN);

    /** Thirty-four significant digits, for the money arithmetic that wants more headroom. */
    public static final MathContext WIDE = new MathContext(34, RoundingMode.HALF_EVEN);

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Decimals() {}

    /**
     * Read decimal text. Surrounding white space is ignored and an underscore may group digits, as
     * people write numbers on a form.
     *
     * @throws NumberFormatException when the text is not a number
     */
    public static BigDecimal parse(String text) {
        String trimmed = text.strip();
        if (trimmed.indexOf('_') >= 0) {
            trimmed = underscoresDropped(trimmed);
        }
        return new BigDecimal(trimmed);
    }

    private static String underscoresDropped(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char previous = 0;
        for (int index = 0; index < text.length(); index++) {
            char letter = text.charAt(index);
            if (letter == '_') {
                boolean between = Character.isDigit(previous)
                        && index + 1 < text.length()
                        && Character.isDigit(text.charAt(index + 1));
                if (!between) {
                    throw new NumberFormatException("misplaced underscore in " + text);
                }
            } else {
                out.append(letter);
            }
            previous = letter;
        }
        return out.toString();
    }

    /** {@code dividend / divisor} at the working precision. */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, WORKING);
    }

    /** {@code dividend / divisor} at the working precision. */
    public static BigDecimal divide(BigDecimal dividend, long divisor) {
        return divide(dividend, BigDecimal.valueOf(divisor));
    }

    /** Round to the places {@code places} carries, a half going up. */
    public static BigDecimal quantize(BigDecimal value, BigDecimal places) {
        return value.setScale(places.scale(), RoundingMode.HALF_UP);
    }

    /** Round to the places {@code places} carries, with the rounding asked for. */
    public static BigDecimal quantize(BigDecimal value, BigDecimal places, RoundingMode mode) {
        return value.setScale(places.scale(), mode);
    }

    /** The whole number nearest {@code value}, a half going up, kept as a decimal. */
    public static BigDecimal integral(BigDecimal value) {
        return value.scale() <= 0 ? value : value.setScale(0, RoundingMode.HALF_UP);
    }

    /** The larger of two values; the first when they are equal. */
    public static BigDecimal max(BigDecimal first, BigDecimal second) {
        return second.compareTo(first) > 0 ? second : first;
    }

    /** The smaller of two values; the first when they are equal. */
    public static BigDecimal min(BigDecimal first, BigDecimal second) {
        return second.compareTo(first) < 0 ? second : first;
    }

    /** Whether two values are the same number, whatever places each carries. */
    public static boolean same(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) == 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value.signum() == 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value.signum() < 0;
    }

    public static boolean isPositive(BigDecimal value) {
        return value.signum() > 0;
    }
}
