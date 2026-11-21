package io.github.rkaushikdev.bushelworks.devices.moisture;

import io.github.rkaushikdev.bushelworks.Decimals;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reading the moisture meter on the grading bench.
 *
 * <p>The meter prints a short report over serial after every test. It is line oriented and human
 * readable, which makes it easy to parse and easy to get wrong, because the operator can key a
 * sample id with a colon in it and the naive split on the first colon then produces nonsense. A
 * report looks like this:
 *
 * <pre>
 *     GAC2500-UGMA
 *     SAMPLE: A-10441-2
 *     CALIB: YELLOW CORN 12.4
 *     MOIST: 18.2 %
 *     TEST WT: 56.5 LB/BU
 *     TEMP: 14.2 C
 *     END
 * </pre>
 *
 * <p>The calibration line matters as much as the reading. A corn sample measured on the soybean
 * calibration reads about a point and a half low, and the only way to catch it afterwards is to
 * have kept the calibration name.
 */
public final class MoistureMeters {

    public static final String END_MARKER = "END";

    /** The meter's calibration names against the commodity codes the house uses. */
    public static final Map<String, String> CALIBRATIONS = Map.of(
            "YELLOW CORN", "CORN",
            "SOYBEANS", "SOYB",
            "HRW WHEAT", "HRW",
            "GRAIN SORGHUM", "MILO",
            "SUNFLOWER OIL", "SUNF");

    private static final BigDecimal DRIEST = BigDecimal.ZERO;
    private static final BigDecimal WETTEST = new BigDecimal("60");
    private static final BigDecimal LIGHTEST = new BigDecimal("20");
    private static final BigDecimal HEAVIEST = new BigDecimal("70");
    private static final BigDecimal COLDEST = BigDecimal.ZERO;
    private static final BigDecimal WARMEST = new BigDecimal("35");

    private MoistureMeters() {}

    /**
     * Read one report, or say which line could not be read.
     *
     * @throws MeterError when the report is empty or truncated, a line has no label, a required line
     *     is missing, the calibration is not one the house uses, or a reading is not a number or is
     *     outside what the meter can read
     */
    public static MeterReading parseReport(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : splitLines(text)) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        if (lines.isEmpty()) {
            throw new MeterError("empty report");
        }
        if (!lines.get(lines.size() - 1).equals(END_MARKER)) {
            throw new MeterError("report does not end with END, it may be truncated");
        }

        String instrument = lines.get(0);
        Map<String, String> fields = new HashMap<>();
        for (String line : lines.subList(1, lines.size() - 1)) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new MeterError("cannot read '" + line + "'");
            }
            // Split once only. A sample id can contain a colon and often does.
            String key = line.substring(0, colon).strip().toUpperCase(Locale.ROOT);
            fields.put(key, line.substring(colon + 1).strip());
        }

        for (String required : List.of("SAMPLE", "CALIB", "MOIST")) {
            if (!fields.containsKey(required)) {
                throw new MeterError("report has no " + required + " line");
            }
        }

        String calibration = withoutVersion(fields.get("CALIB"));
        String commodityCode = CALIBRATIONS.get(calibration.toUpperCase(Locale.ROOT));
        if (commodityCode == null) {
            throw new MeterError("'" + calibration + "' is not a calibration this house uses");
        }

        BigDecimal moisture = number(fields.get("MOIST"), "MOIST");
        if (!(moisture.compareTo(DRIEST) > 0 && moisture.compareTo(WETTEST) < 0)) {
            throw new MeterError("moisture of " + moisture + " is outside what the meter can read");
        }

        Optional<BigDecimal> testWeight = Optional.empty();
        if (fields.containsKey("TEST WT")) {
            BigDecimal value = number(fields.get("TEST WT"), "TEST WT");
            if (!(value.compareTo(LIGHTEST) > 0 && value.compareTo(HEAVIEST) < 0)) {
                throw new MeterError("test weight of " + value + " is implausible");
            }
            testWeight = Optional.of(value);
        }

        Optional<BigDecimal> temperature =
                fields.containsKey("TEMP") ? Optional.of(number(fields.get("TEMP"), "TEMP")) : Optional.empty();

        return new MeterReading(
                fields.get("SAMPLE"), commodityCode, calibration, moisture, testWeight, temperature, instrument);
    }

    /** {@code YELLOW CORN 12.4} as {@code YELLOW CORN}: everything before the last space. */
    private static String withoutVersion(String calibrationLine) {
        int space = calibrationLine.lastIndexOf(' ');
        return (space < 0 ? calibrationLine : calibrationLine.substring(0, space)).strip();
    }

    /** The number a field opens with, {@code 18.2} out of {@code 18.2 %}. */
    private static BigDecimal number(String text, String label) {
        String stripped = text.strip();
        int space = stripped.indexOf(' ');
        String cleaned = space < 0 ? stripped : stripped.substring(0, space);
        try {
            return Decimals.parse(cleaned);
        } catch (NumberFormatException notANumber) {
            throw new MeterError(label + ": '" + stripped + "' is not a number");
        }
    }

    /**
     * Split a spool of reports and read each one.
     *
     * <p>Anything that will not parse is dropped rather than failing the batch. The spool is what the
     * bench printed all morning and one bad report should not cost the other forty.
     */
    public static List<MeterReading> parseBatch(String text) {
        List<MeterReading> readings = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : splitLines(text)) {
            current.add(line);
            if (line.strip().equals(END_MARKER)) {
                readable(String.join("\n", current)).ifPresent(readings::add);
                current = new ArrayList<>();
            }
        }
        return List.copyOf(readings);
    }

    private static Optional<MeterReading> readable(String report) {
        try {
            return Optional.of(parseReport(report));
        } catch (MeterError unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Refuse a reading taken on the wrong calibration.
     *
     * @throws MeterError naming the calibration the sample was tested on
     */
    public static void checkCalibration(MeterReading reading, String commodityCode) {
        if (!reading.matches(commodityCode)) {
            throw new MeterError(reading.sampleId() + " was tested on " + reading.calibration() + ", which is "
                    + reading.commodityCode() + ", not " + commodityCode);
        }
    }

    /**
     * Whether the sample was too cold or too hot to trust.
     *
     * <p>Below zero the meter reads low and above thirty five it reads high, both by enough to move a
     * settlement. The bench is supposed to let a sample sit. A report with no temperature cannot be
     * flagged.
     */
    public static Optional<String> temperatureWarning(MeterReading reading) {
        if (reading.temperature().isEmpty()) {
            return Optional.empty();
        }
        BigDecimal temperature = reading.temperature().get();
        if (temperature.compareTo(COLDEST) < 0) {
            return Optional.of(reading.sampleId() + " was tested at " + temperature + "C, too cold");
        }
        if (temperature.compareTo(WARMEST) > 0) {
            return Optional.of(reading.sampleId() + " was tested at " + temperature + "C, too warm");
        }
        return Optional.empty();
    }

    /**
     * Split text into lines at any line boundary: a line feed, a carriage return with or without a
     * line feed after it, a vertical tab, a form feed, the file, group and record separators, a next
     * line, and the Unicode line and paragraph separators. A final line ending ends the last line
     * rather than starting an empty one.
     */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char letter = text.charAt(index);
            if (isLineBoundary(letter)) {
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

    private static boolean isLineBoundary(char letter) {
        return switch (letter) {
            case '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }
}
