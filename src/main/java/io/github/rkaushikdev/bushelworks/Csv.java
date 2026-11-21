package io.github.rkaushikdev.bushelworks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Comma separated text, written and read the way every export in the house does it.
 *
 * <p>Rows end in a bare line feed. A field is quoted only when it has to be: when it holds a comma,
 * a double quote or a line break, and a quote inside a quoted field is doubled. A row of one empty
 * field is written as {@code ""} so that it is not mistaken for a blank line. A missing value is an
 * empty field; a decimal is written with its exact places.
 */
public final class Csv {

    private Csv() {}

    /** One row, with its line feed. */
    public static String row(List<?> fields) {
        if (fields.size() == 1 && text(fields.get(0)).isEmpty()) {
            return "\"\"\n";
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(field(text(fields.get(index))));
        }
        return out.append('\n').toString();
    }

    /** One row, with its line feed. */
    public static String row(Object... fields) {
        return row(Arrays.asList(fields));
    }

    /** How a value is written in a field before any quoting. */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Optional<?> maybe) {
            return text(maybe.orElse(null));
        }
        if (value instanceof ValueEnum coded) {
            return coded.value();
        }
        return value.toString();
    }

    private static String field(String text) {
        boolean needsQuotes =
                text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * Split comma separated text into rows of fields. Quoted fields may hold commas, doubled quotes
     * and line breaks; a line ending is a bare line feed or a carriage return and line feed.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int index = 0;
        while (index < text.length()) {
            char letter = text.charAt(index);
            if (quoted) {
                if (letter == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    field.append(letter);
                }
                index++;
                continue;
            }
            switch (letter) {
                case '"' -> {
                    quoted = true;
                    fieldStarted = true;
                }
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    fieldStarted = true;
                }
                case '\r' -> {
                    // A carriage return before a line feed ends nothing on its own.
                }
                case '\n' -> {
                    if (fieldStarted || field.length() > 0 || !current.isEmpty()) {
                        current.add(field.toString());
                    }
                    rows.add(List.copyOf(current));
                    current = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                }
                default -> {
                    field.append(letter);
                    fieldStarted = true;
                }
            }
            index++;
        }
        if (fieldStarted || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(List.copyOf(current));
        }
        return rows;
    }
}
