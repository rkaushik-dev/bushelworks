package io.github.rkaushikdev.bushelworks.devices.scale;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reading the truck scale indicator.
 *
 * <p>Instruments on the driveway talk over serial and none of them agree about anything. Nothing
 * here opens a port: the adapter takes bytes and returns values, which is what makes it testable
 * without a truck, and it refuses anything it cannot read, because a weight guessed from a
 * corrupted line is worse than no weight at all.
 *
 * <p>The indicator on the pit sends a continuous stream at 9600 baud whether or not anybody is
 * listening. Each frame is
 *
 * <pre>
 *     STX  S  sign  d d d d d d  unit unit  CR
 * </pre>
 *
 * <p>where the status byte {@code S} is {@code S} for stable, {@code M} for in motion and {@code O}
 * for over capacity, the six digits are the displayed weight with no decimal point, and the unit is
 * {@code lb} or {@code kg}. Three rules, all of them learned the hard way:
 *
 * <ul>
 *   <li>only a stable frame is a weight. A frame in motion is the truck rocking and taking it is how
 *       a load ends up two hundred pounds light.
 *   <li>the stream is read from the end. The indicator buffers, and the first complete frame in the
 *       buffer may be thirty seconds old.
 *   <li>a frame that does not parse is dropped in silence. The line picks up noise from the auger
 *       motor every time it starts, and an operator who is told about it stops reading the messages.
 * </ul>
 */
public final class Scales {

    public static final byte STX = 0x02;
    public static final byte CR = 0x0D;
    public static final int FRAME_LENGTH = 12;

    public static final BigDecimal POUNDS_PER_KILOGRAM = new BigDecimal("2.20462262");

    private static final int LARGEST_WEIGHT = 999_999;

    private Scales() {}

    /**
     * Read one frame, or say exactly what is wrong with it.
     *
     * <p>A kilogram weight is converted to whole pounds, a half going to the even pound.
     *
     * @throws IndicatorError naming the part of the frame that would not read
     */
    public static ScaleReading parseReading(byte[] frame) {
        if (frame.length != FRAME_LENGTH) {
            throw new IndicatorError("a frame is " + FRAME_LENGTH + " bytes, got " + frame.length);
        }
        if (frame[0] != STX) {
            throw new IndicatorError("frame does not start with STX");
        }
        if (frame[frame.length - 1] != CR) {
            throw new IndicatorError("frame does not end with CR");
        }

        String status = String.valueOf((char) (frame[1] & 0xFF));
        if (!ValueEnum.isValue(Stability.class, status)) {
            throw new IndicatorError("'" + status + "' is not a status byte");
        }
        Stability stability = Stability.of(status);

        char sign = (char) (frame[2] & 0xFF);
        if (sign != '+' && sign != '-') {
            throw new IndicatorError("'" + sign + "' is not a sign");
        }

        String digits = ascii(frame, 3, 9);
        if (!allDigits(digits)) {
            throw new IndicatorError("'" + digits + "' is not a weight");
        }

        String unit = ascii(frame, 9, 11).strip().toLowerCase(Locale.ROOT);
        if (!unit.equals("lb") && !unit.equals("kg")) {
            throw new IndicatorError("'" + unit + "' is not a unit this indicator sends");
        }

        BigDecimal value = new BigDecimal(digits);
        if (sign == '-') {
            value = value.negate();
        }
        if (unit.equals("kg")) {
            value = value.multiply(POUNDS_PER_KILOGRAM).setScale(0, RoundingMode.HALF_EVEN);
        }
        return new ScaleReading(value, stability, unit);
    }

    /** The bytes from {@code from} up to {@code to}, any byte outside ASCII read as U+FFFD. */
    private static String ascii(byte[] frame, int from, int to) {
        return new String(frame, from, to - from, StandardCharsets.US_ASCII);
    }

    private static boolean allDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(letter -> letter >= '0' && letter <= '9');
    }

    /** Split a buffer into whole frames, dropping anything partial and any noise between them. */
    public static List<byte[]> frames(byte[] buffer) {
        List<byte[]> found = new ArrayList<>();
        int start = 0;
        while (true) {
            int index = indexOf(buffer, STX, start);
            if (index < 0) {
                break;
            }
            int end = index + FRAME_LENGTH;
            if (end > buffer.length) {
                break;
            }
            byte[] candidate = Arrays.copyOfRange(buffer, index, end);
            if (candidate[FRAME_LENGTH - 1] == CR) {
                found.add(candidate);
                start = end;
            } else {
                start = index + 1;
            }
        }
        return List.copyOf(found);
    }

    private static int indexOf(byte[] buffer, byte wanted, int from) {
        for (int index = from; index < buffer.length; index++) {
            if (buffer[index] == wanted) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The most recent usable weight in a buffer, or nothing.
     *
     * <p>Reads backwards. The indicator buffers while nobody is asking, so the first frame in the
     * buffer can be a minute old and belong to the truck before this one.
     */
    public static Optional<ScaleReading> parseStream(byte[] buffer) {
        List<byte[]> found = frames(buffer);
        for (int index = found.size() - 1; index >= 0; index--) {
            Optional<ScaleReading> reading = readable(found.get(index));
            if (reading.isPresent() && reading.get().isUsable()) {
                return reading;
            }
        }
        return Optional.empty();
    }

    /**
     * The most recent frame that parsed at all, stable or not.
     *
     * <p>Used by the display on the scale house wall, which shows the weight moving while the truck
     * settles.
     */
    public static Optional<ScaleReading> latest(byte[] buffer) {
        List<byte[]> found = frames(buffer);
        for (int index = found.size() - 1; index >= 0; index--) {
            Optional<ScaleReading> reading = readable(found.get(index));
            if (reading.isPresent()) {
                return reading;
            }
        }
        return Optional.empty();
    }

    /** The frame's reading, or nothing when it is line noise. */
    private static Optional<ScaleReading> readable(byte[] frame) {
        try {
            return Optional.of(parseReading(frame));
        } catch (IndicatorError noise) {
            return Optional.empty();
        }
    }

    /** A stable frame in pounds. */
    public static byte[] buildFrame(int pounds) {
        return buildFrame(pounds, Stability.STABLE, "lb");
    }

    /** A frame in pounds with the status given. */
    public static byte[] buildFrame(int pounds, Stability stability) {
        return buildFrame(pounds, stability, "lb");
    }

    /**
     * Make a frame. Used by the tests and by the driveway simulator.
     *
     * @throws IndicatorError when the weight does not fit in six digits
     */
    public static byte[] buildFrame(int pounds, Stability stability, String unit) {
        if (Math.abs((long) pounds) > LARGEST_WEIGHT) {
            throw new IndicatorError(pounds + " does not fit in six digits");
        }
        String body = stability.value()
                + (pounds < 0 ? "-" : "+")
                + String.format(Locale.ROOT, "%06d", Math.abs((long) pounds))
                + String.format(Locale.ROOT, "%-2s", unit);
        ByteArrayOutputStream out = new ByteArrayOutputStream(FRAME_LENGTH);
        out.write(STX);
        out.writeBytes(body.getBytes(StandardCharsets.US_ASCII));
        out.write(CR);
        return out.toByteArray();
    }
}
