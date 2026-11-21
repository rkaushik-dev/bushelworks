package io.github.rkaushikdev.bushelworks.services.legacy;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the old system's ticket file.
 *
 * <p>The system this replaced wrote a fixed width file every night and the house has eleven years of
 * them. They are the only record of anything before go live, so they have to come in, and they come
 * in dirty:
 *
 * <ul>
 *   <li>weights are in the file as text with leading spaces and the occasional comma
 *   <li>moisture is sometimes blank, which the old system meant as "not tested" and sometimes meant
 *       as zero
 *   <li>a void is a flag in column 78 and the voided row is still there
 *   <li>the party code is the old six character one, which has to be mapped
 * </ul>
 *
 * <p>Nothing here touches the database. It reads rows, reports what it cannot read, and hands back
 * what it could. A migration that silently drops the rows it does not understand is worse than one
 * that fails.
 */
public final class LegacyReader {

    /** Where a field sits on a line: zero based, the end excluded. */
    public record Span(int start, int end) {}

    /** The fields of a line, in column order. */
    public static final Map<String, Span> LAYOUT = Ordered.copyOf(layout());

    /** How long a line is. */
    public static final int RECORD_LENGTH = 79;

    /** The old system's disposition codes, as they sit in their eight columns, and what they mean. */
    public static final Map<String, Disposition> DISPOSITIONS = Ordered.copyOf(dispositions());

    /**
     * A year of four digits, then a month and a day. The month and the day may each be a single
     * digit and the day may be a space and a digit; the first reading that fits, from the left, is
     * the one taken, and the whole field has to be used up by it.
     */
    private static final Pattern SCALE_DATE =
            Pattern.compile("([0-9]{4})(1[0-2]|0[1-9]|[1-9])(3[01]|[12][0-9]|0[1-9]|[1-9]| [1-9])");

    private LegacyReader() {}

    private static Map<String, Span> layout() {
        Map<String, Span> fields = new LinkedHashMap<>();
        fields.put("ticket_number", new Span(0, 10));
        fields.put("scale_date", new Span(10, 18));
        fields.put("party_code", new Span(18, 24));
        fields.put("commodity_code", new Span(24, 28));
        fields.put("location_code", new Span(28, 32));
        fields.put("gross_pounds", new Span(32, 42));
        fields.put("tare_pounds", new Span(42, 52));
        fields.put("moisture", new Span(52, 58));
        fields.put("test_weight", new Span(58, 64));
        fields.put("damage", new Span(64, 70));
        fields.put("disposition", new Span(70, 78));
        fields.put("void_flag", new Span(78, 79));
        return fields;
    }

    private static Map<String, Disposition> dispositions() {
        Map<String, Disposition> codes = new LinkedHashMap<>();
        codes.put("CASH    ", Disposition.CASH_SALE);
        codes.put("STORE   ", Disposition.STORE);
        codes.put("PRICELTR", Disposition.PRICE_LATER);
        codes.put("CONTRACT", Disposition.CONTRACT);
        codes.put("FEED    ", Disposition.FEED);
        return codes;
    }

    /**
     * One field of a line, exactly as it sits, padding and all. A line too short for the field gives
     * what there is of it.
     *
     * @throws IllegalArgumentException for a field the layout does not have
     */
    public static String fieldAt(String line, String name) {
        Span span = LAYOUT.get(name);
        if (span == null) {
            throw new IllegalArgumentException("the old file has no field " + name);
        }
        int length = line.length();
        return line.substring(Math.min(span.start(), length), Math.min(span.end(), length));
    }

    /** Read one fixed width row, keeping the old party code. */
    public static LegacyRow parseLine(String line, int lineNumber) {
        return parseLine(line, lineNumber, Map.of());
    }

    /**
     * Read one fixed width row, or say which field beat it.
     *
     * @param partyMap old party codes to new ones; a code it does not name comes across as it is
     * @throws LegacyImportError naming the line and the field that could not be read
     */
    public static LegacyRow parseLine(String line, int lineNumber, Map<String, String> partyMap) {
        if (line.length() < RECORD_LENGTH) {
            throw new LegacyImportError(
                    "line " + lineNumber + ": " + line.length() + " characters, expected " + RECORD_LENGTH);
        }

        String ticketNumber = trimmed(fieldAt(line, "ticket_number"));
        if (ticketNumber.isEmpty()) {
            throw new LegacyImportError("line " + lineNumber + ": no ticket number");
        }

        String rawDate = trimmed(fieldAt(line, "scale_date"));
        LocalDate scaleDate = scaleDate(rawDate)
                .orElseThrow(
                        () -> new LegacyImportError("line " + lineNumber + ": " + quoted(rawDate) + " is not a date"));

        String oldCode = trimmed(fieldAt(line, "party_code"));
        String partyCode = partyMap.getOrDefault(oldCode, oldCode);
        if (partyCode.isEmpty()) {
            throw new LegacyImportError("line " + lineNumber + ": no party code");
        }

        String rawDisposition = fieldAt(line, "disposition");
        Disposition disposition = DISPOSITIONS.get(rawDisposition);
        if (disposition == null) {
            throw new LegacyImportError(
                    "line " + lineNumber + ": " + quoted(trimmed(rawDisposition)) + " is not a disposition");
        }

        BigDecimal gross = weight(fieldAt(line, "gross_pounds"), "gross", lineNumber);
        BigDecimal tare = weight(fieldAt(line, "tare_pounds"), "tare", lineNumber);
        if (tare.compareTo(gross) > 0) {
            throw new LegacyImportError("line " + lineNumber + ": tare " + tare + " exceeds gross " + gross);
        }

        return new LegacyRow(
                lineNumber,
                ticketNumber,
                scaleDate,
                partyCode,
                trimmed(fieldAt(line, "commodity_code")),
                trimmed(fieldAt(line, "location_code")),
                gross,
                tare,
                disposition,
                fieldAt(line, "void_flag").equals("V"),
                reading(fieldAt(line, "moisture")),
                reading(fieldAt(line, "test_weight")),
                reading(fieldAt(line, "damage")));
    }

    /** Read a whole night's file, keeping the old party codes. */
    public static ParsedFile parseFile(String text) {
        return parseFile(text, Map.of());
    }

    /**
     * Read a whole night's file, keeping what fails and why. Blank lines are skipped; lines are
     * numbered as they stand in the file, blank ones included.
     */
    public static ParsedFile parseFile(String text, Map<String, String> partyMap) {
        List<LegacyRow> rows = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        List<String> lines = lines(text);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (trimmed(line).isEmpty()) {
                continue;
            }
            try {
                rows.add(parseLine(line, index + 1, partyMap));
            } catch (LegacyImportError error) {
                rejected.add(new Rejected(index + 1, line, error.getMessage()));
            }
        }
        return new ParsedFile(rows, rejected);
    }

    /**
     * Ticket numbers that appear more than once at the same site, as {@code site/number}, sorted.
     *
     * <p>The old file repeats a ticket when it was corrected, and the correction is the later row.
     * Nothing is dropped here; the caller decides.
     */
    public static List<String> duplicates(ParsedFile parsed) {
        Map<List<String>, Integer> seen = new LinkedHashMap<>();
        for (LegacyRow row : parsed.rows()) {
            seen.merge(List.of(row.locationCode(), row.ticketNumber()), 1, Integer::sum);
        }
        List<String> repeated = new ArrayList<>();
        seen.forEach((key, count) -> {
            if (count > 1) {
                repeated.add(key.get(0) + "/" + key.get(1));
            }
        });
        repeated.sort(Comparator.naturalOrder());
        return List.copyOf(repeated);
    }

    /** One paragraph for the migration log, naming up to five of the reasons rows were refused. */
    public static String summary(ParsedFile parsed) {
        if (parsed.isClean()) {
            return parsed.read() + " tickets read, none refused";
        }
        TreeSet<String> reasons = new TreeSet<>();
        for (Rejected item : parsed.rejected()) {
            String reason = item.reason();
            int colon = reason.indexOf(':');
            reasons.add(trimmed(colon < 0 ? reason : reason.substring(colon + 1)));
        }
        String named = String.join(", ", reasons.stream().limit(5).toList());
        return parsed.read() + " tickets read, " + parsed.refused() + " refused: " + named;
    }

    private static Optional<LocalDate> scaleDate(String text) {
        Matcher matcher = SCALE_DATE.matcher(text);
        if (!matcher.lookingAt() || matcher.end() != text.length()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3).strip());
        if (year < 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException impossible) {
            return Optional.empty();
        }
    }

    private static BigDecimal weight(String text, String label, int lineNumber) {
        String cleaned = trimmed(text.replace(",", ""));
        if (cleaned.isEmpty()) {
            throw new LegacyImportError("line " + lineNumber + ": " + label + " is blank");
        }
        try {
            return Decimals.parse(cleaned);
        } catch (NumberFormatException unreadable) {
            throw new LegacyImportError("line " + lineNumber + ": " + label + " is " + quoted(trimmed(text)));
        }
    }

    /**
     * A meter reading, where blank means not tested, which is not the same as zero.
     *
     * <p>The old system used the same blank for both and there is no way to tell them apart, so
     * everything blank comes across as not tested, and so does anything that is not a number. That
     * loses a genuine zero reading, and it is the safe direction: a missing factor cannot be graded,
     * and a false zero would grade as perfect.
     */
    private static Optional<BigDecimal> reading(String text) {
        String cleaned = trimmed(text.replace(",", ""));
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Decimals.parse(cleaned));
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The file's lines, without their endings. A line ends at a line feed, a carriage return, the
     * pair of them, or any of the other breaks text can carry: a vertical tab, a form feed, the file,
     * group and record separators, a next line, a line separator or a paragraph separator. A break at
     * the very end starts no further line.
     */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char letter = text.charAt(index);
            if (isLineBreak(letter)) {
                lines.add(text.substring(start, index));
                if (letter == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
            index++;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean isLineBreak(char letter) {
        return switch (letter) {
            case '\n', '\r', '\u000B', '\f', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }

    /** {@code text} without white space at either end, no-break and other Unicode spaces included. */
    static String trimmed(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char letter) {
        return Character.isWhitespace(letter) || Character.isSpaceChar(letter) || letter == '\u0085';
    }

    private static String quoted(String text) {
        return "'" + text + "'";
    }
}
