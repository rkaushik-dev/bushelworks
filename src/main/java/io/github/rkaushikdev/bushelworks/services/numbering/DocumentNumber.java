package io.github.rkaushikdev.bushelworks.services.numbering;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A parsed document number, such as {@code S-2024-0417}, so a string can be checked rather than
 * trusted.
 *
 * <p>The sequence prints with at least four digits and as many more as it needs.
 *
 * @param prefix the series, such as {@code S} for settlements
 * @param year the year the number was issued in
 * @param sequence its place in that year's run, from one
 */
public record DocumentNumber(String prefix, int year, int sequence) {

    public DocumentNumber {
        Objects.requireNonNull(prefix, "prefix");
    }

    /**
     * Read a number back from its printed form.
     *
     * @throws NumberingError when the text is not three parts joined by hyphens, has no prefix, no
     *     four digit year or no sequence
     */
    public static DocumentNumber parse(String text) {
        List<String> parts = List.of(text.split("-", -1));
        if (parts.size() != 3) {
            throw new NumberingError(quoted(text) + " is not a document number");
        }
        String prefix = parts.get(0);
        String year = parts.get(1);
        String sequence = parts.get(2);
        if (prefix.isEmpty()) {
            throw new NumberingError(quoted(text) + " has no prefix");
        }
        if (!isDigits(year) || year.length() != 4) {
            throw new NumberingError(quoted(text) + " has no four digit year");
        }
        if (!isDigits(sequence)) {
            throw new NumberingError(quoted(text) + " has no sequence");
        }
        try {
            return new DocumentNumber(prefix, Integer.parseInt(year), Integer.parseInt(sequence));
        } catch (NumberFormatException tooLong) {
            throw new NumberingError(quoted(text) + " has a sequence no series reaches");
        }
    }

    /** The number after this one, in the same series and year. */
    public DocumentNumber next() {
        return new DocumentNumber(prefix, year, sequence + 1);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s-%d-%04d", prefix, year, sequence);
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(letter -> letter >= '0' && letter <= '9');
    }

    private static String quoted(String text) {
        return "'" + text + "'";
    }
}
