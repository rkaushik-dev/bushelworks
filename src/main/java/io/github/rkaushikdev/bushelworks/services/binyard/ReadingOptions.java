package io.github.rkaushikdev.bushelworks.services.binyard;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a cable reading carries beyond its moment and its temperature.
 *
 * <pre>{@code
 * BinYard.recordReading(uow, "HOME", "B-02", takenAt, temperature, ReadingOptions.defaults().withCable(2));
 * }</pre>
 *
 * @param cable which cable in the bin it came off
 * @param moisture the moisture the cable read, when it reads one
 * @param fanRunning whether the aeration fan was on
 * @param note anything written against the reading
 */
public record ReadingOptions(int cable, Optional<BigDecimal> moisture, boolean fanRunning, Optional<String> note) {

    public ReadingOptions {
        Objects.requireNonNull(moisture, "moisture");
        Objects.requireNonNull(note, "note");
    }

    /** Cable one, no moisture, the fan off, no note. */
    public static ReadingOptions defaults() {
        return new ReadingOptions(1, Optional.empty(), false, Optional.empty());
    }

    public ReadingOptions withCable(int value) {
        return new ReadingOptions(value, moisture, fanRunning, note);
    }

    /** Null clears it. */
    public ReadingOptions withMoisture(BigDecimal value) {
        return new ReadingOptions(cable, Optional.ofNullable(value), fanRunning, note);
    }

    public ReadingOptions withFanRunning(boolean value) {
        return new ReadingOptions(cable, moisture, value, note);
    }

    /** Null clears it. */
    public ReadingOptions withNote(String value) {
        return new ReadingOptions(cable, moisture, fanRunning, Optional.ofNullable(value));
    }
}
