package io.github.rkaushikdev.bushelworks.reports;

/**
 * Fixed width text for the forms the office prints.
 *
 * <p>Every printed report is laid out in columns of a stated width: a field is padded with spaces
 * to its column and never wraps, and a heading is centred on the form. Widths count characters, so
 * an accented producer name takes the same room as a plain one.
 */
public final class Columns {

    private Columns() {}

    /** {@code text} padded on the right to {@code width}; text already that wide is left alone. */
    public static String left(String text, int width) {
        int missing = width - length(text);
        return missing > 0 ? text + " ".repeat(missing) : text;
    }

    /** {@code text} padded on the left to {@code width}; text already that wide is left alone. */
    public static String right(String text, int width) {
        int missing = width - length(text);
        return missing > 0 ? " ".repeat(missing) + text : text;
    }

    /** The first {@code length} characters of {@code text}, or all of it when it is shorter. */
    public static String clip(String text, int length) {
        if (length <= 0) {
            return "";
        }
        if (length(text) <= length) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, length));
    }

    /**
     * {@code text} centred on a form {@code width} wide, with nothing after it.
     *
     * <p>When the spare room is odd, the extra space goes on the left only if the width is odd too.
     * Text as wide as the form or wider is printed as it is.
     */
    public static String centred(String text, int width) {
        int margin = width - length(text);
        if (margin <= 0) {
            return text.stripTrailing();
        }
        int before = margin / 2 + (margin & width & 1);
        return (" ".repeat(before) + text).stripTrailing();
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
