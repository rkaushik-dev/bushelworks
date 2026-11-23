package io.github.rkaushikdev.bushelworks.domain.fleet;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * What kind of outfit pulls onto the scale, which decides what an empty one can weigh.
 *
 * <p>A semi with a hopper bottom trailer weighs thirteen tons empty and a farm truck with a single
 * rear axle weighs five, so one plausible range for every tare either lets a keying mistake through
 * on the small truck or refuses an honest tare on the big one. Each rig carries its own range, and
 * its own idea of how far a tare can move over a season before it is a different truck: two
 * thousand pounds on a semi is a tarp, a toolbox and wet mud; on a single axle truck it is a new
 * box.
 */
public enum Rig implements ValueEnum {
    HOPPER_BOTTOM("hopper_bottom", "18000", "36000", "2000"),
    TANDEM_STRAIGHT("tandem_straight", "14000", "30000", "1500"),
    SINGLE_AXLE("single_axle", "7000", "18000", "1000"),
    TRACTOR_AND_WAGONS("tractor_and_wagons", "15000", "40000", "2500");

    private final String value;
    private final BigDecimal lightest;
    private final BigDecimal heaviest;
    private final BigDecimal driftThreshold;

    Rig(String value, String lightest, String heaviest, String driftThreshold) {
        this.value = value;
        this.lightest = new BigDecimal(lightest);
        this.heaviest = new BigDecimal(heaviest);
        this.driftThreshold = new BigDecimal(driftThreshold);
    }

    @Override
    public String value() {
        return value;
    }

    public static Rig of(String value) {
        return ValueEnum.of(Rig.class, value);
    }

    /** The lightest plausible empty weighing of this rig. */
    public BigDecimal lightest() {
        return lightest;
    }

    /** The heaviest plausible empty weighing of this rig. */
    public BigDecimal heaviest() {
        return heaviest;
    }

    /** How far the tare may move over a season before the scale house asks what changed. */
    public BigDecimal driftThreshold() {
        return driftThreshold;
    }

    /** Whether an empty weighing of this many pounds could be this rig. */
    public boolean isPlausibleTare(BigDecimal pounds) {
        return pounds.compareTo(lightest) >= 0 && pounds.compareTo(heaviest) <= 0;
    }

    /** The lightest any rig weighs empty: below this a tare is a keying mistake whatever came in. */
    public static BigDecimal lightestOfAll() {
        return Arrays.stream(values())
                .map(Rig::lightest)
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    /** The heaviest any rig weighs empty. */
    public static BigDecimal heaviestOfAll() {
        return Arrays.stream(values())
                .map(Rig::heaviest)
                .max(BigDecimal::compareTo)
                .orElseThrow();
    }
}
