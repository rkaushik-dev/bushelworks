package io.github.rkaushikdev.bushelworks;

import java.util.Locale;

/**
 * How a refusal quotes the text it was handed.
 *
 * <p>A message that says {@code '' is not a routing number} or {@code '\t' leaves nothing to name a
 * file after} has to show exactly what came in, including the text that is empty or invisible, so the
 * text goes in quotes with anything unprintable spelled out as an escape.
 */
public final class Quoting {

    private Quoting() {}

    /**
     * {@code text} in single quotes, or in double ones when it holds a single quote and no double
     * quote. A backslash and the quote in use are escaped with a backslash; a tab, line feed and
     * carriage return are written {@code \t}, {@code \n} and {@code \r}; any other control or
     * unprintable character is written as a backslash and its code in hex: {@code x} and two digits up
     * to U+00FF, {@code u} and four up to U+FFFF, {@code U} and eight beyond. Printable characters,
     * accented letters among them, are kept as they are.
     */
    public static String quoted(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        text.codePoints().forEach(point -> {
            if (point == quote || point == '\\') {
                out.append('\\').appendCodePoint(point);
            } else if (point == '\t') {
                out.append("\\t");
            } else if (point == '\n') {
                out.append("\\n");
            } else if (point == '\r') {
                out.append("\\r");
            } else if (isPrintable(point)) {
                out.appendCodePoint(point);
            } else if (point <= 0xff) {
                out.append(String.format(Locale.ROOT, "\\x%02x", point));
            } else if (point <= 0xffff) {
                out.append(String.format(Locale.ROOT, "\\u%04x", point));
            } else {
                out.append(String.format(Locale.ROOT, "\\U%08x", point));
            }
        });
        return out.append(quote).toString();
    }

    /** Whether a character prints as itself: not a control, format, private, unassigned or separator one, bar the space. */
    private static boolean isPrintable(int point) {
        if (point == ' ') {
            return true;
        }
        return switch (Character.getType(point)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.SURROGATE,
                    Character.PRIVATE_USE,
                    Character.UNASSIGNED,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }
}
