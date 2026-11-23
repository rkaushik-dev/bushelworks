package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A table the house files as text, read the same way whichever table it is.
 *
 * <p>The commodity master, the grade ladders, the discount board and the levy sheet are all kept as
 * the page somebody would print and pin up, and they share one set of conventions: a line a row,
 * blank lines and lines starting with {@code #} passed over, cells either lined up on spaces or
 * set between bars, and a dash in a cell for nothing. A number that does not read is refused with
 * the whole line it was on, so the office can find it on the page.
 */
public final class FiledSheet {

    /** What a cell holds when there is nothing to say. */
    public static final String NOTHING = "-";

    private FiledSheet() {}

    /** The lines that carry something, stripped, in the order the sheet has them. */
    public static List<String> rows(String sheet) {
        return sheet.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /** The lines that carry something, less the heading that starts with {@code headingWord}. */
    public static List<String> rows(String sheet, String headingWord) {
        return rows(sheet).stream()
                .filter(line -> !line.equals(headingWord) && !line.startsWith(headingWord + " "))
                .toList();
    }

    /** A row lined up on spaces, cut at every run of white space. */
    public static String[] spaced(String row) {
        return row.split("\\s+");
    }

    /** A row lined up on spaces, cut into no more than {@code limit} cells, the last keeping the rest. */
    public static String[] spaced(String row, int limit) {
        return row.split("\\s+", limit);
    }

    /** A row with its cells between bars, each cell stripped. An empty cell stays a cell. */
    public static String[] barred(String row) {
        return Arrays.stream(row.split("\\|", -1)).map(String::strip).toArray(String[]::new);
    }

    /**
     * A cell read as a number.
     *
     * @param refusal what to raise, handed the sentence that names the cell and its line
     */
    public static BigDecimal number(String cell, String row, Function<String, ? extends RuntimeException> refusal) {
        try {
            return new BigDecimal(cell);
        } catch (NumberFormatException unreadable) {
            throw refusal.apply("'" + cell + "' is not a number: " + row);
        }
    }

    /** A cell read as a number, or nothing for a dash. */
    public static Optional<BigDecimal> numberOrNothing(
            String cell, String row, Function<String, ? extends RuntimeException> refusal) {
        return cell.equals(NOTHING) ? Optional.empty() : Optional.of(number(cell, row, refusal));
    }
}
