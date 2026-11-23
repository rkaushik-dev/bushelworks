package io.github.rkaushikdev.bushelworks.domain.commodity;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.math.BigDecimal;
import java.util.Comparator;

/**
 * Grade factors a grader can record on a probe sample.
 *
 * <p>The values match the FGIS certificate columns rather than anything internal, because the
 * grader is reading them off a certificate. Stones and animal filth are percentages of the sample's
 * weight; glass, crotalaria, castor beans and unknown foreign substance are counts in the work
 * portion.
 */
public enum Factor implements ValueEnum {
    TEST_WEIGHT("test_weight", FactorDirection.LOW_IS_BAD),
    MOISTURE("moisture", FactorDirection.HIGH_IS_BAD),
    DOCKAGE("dockage", FactorDirection.HIGH_IS_BAD),
    FOREIGN_MATERIAL("foreign_material", FactorDirection.HIGH_IS_BAD),
    BCFM("bcfm", FactorDirection.HIGH_IS_BAD),
    DAMAGE_TOTAL("damage_total", FactorDirection.HIGH_IS_BAD),
    DAMAGE_HEAT("damage_heat", FactorDirection.HIGH_IS_BAD),
    SPLITS("splits", FactorDirection.HIGH_IS_BAD),
    SHRUNKEN_BROKEN("shrunken_broken", FactorDirection.HIGH_IS_BAD),
    TOTAL_DEFECTS("total_defects", FactorDirection.HIGH_IS_BAD),
    PROTEIN("protein", FactorDirection.LOW_IS_BAD),
    OIL("oil", FactorDirection.LOW_IS_BAD),
    FALLING_NUMBER("falling_number", FactorDirection.LOW_IS_BAD),
    STONES("stones", FactorDirection.HIGH_IS_BAD),
    GLASS("glass", FactorDirection.HIGH_IS_BAD),
    CROTALARIA("crotalaria", FactorDirection.HIGH_IS_BAD),
    CASTOR_BEANS("castor_beans", FactorDirection.HIGH_IS_BAD),
    UNKNOWN_FOREIGN("unknown_foreign_substance", FactorDirection.HIGH_IS_BAD),
    ANIMAL_FILTH("animal_filth", FactorDirection.HIGH_IS_BAD);

    /** Factors in the order of their values, which is the order every listing prints them in. */
    public static final Comparator<Factor> BY_VALUE = Comparator.comparing(Factor::value);

    private final String value;
    private final FactorDirection direction;

    Factor(String value, FactorDirection direction) {
        this.value = value;
        this.direction = direction;
    }

    @Override
    public String value() {
        return value;
    }

    /** Which way this factor has to move before it costs the producer money. */
    public FactorDirection direction() {
        return direction;
    }

    /**
     * Whether a reading is on the bad side of a limit: below a floor for a factor that is bad when
     * low, above a ceiling for one that is bad when high. A reading exactly at the limit is not.
     */
    public boolean isPast(BigDecimal limit, BigDecimal reading) {
        int against = reading.compareTo(limit);
        return direction == FactorDirection.LOW_IS_BAD ? against < 0 : against > 0;
    }

    public static Factor of(String value) {
        return ValueEnum.of(Factor.class, value);
    }
}
