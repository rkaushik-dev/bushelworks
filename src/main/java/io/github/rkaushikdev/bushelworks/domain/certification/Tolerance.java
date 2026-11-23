package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.math.BigDecimal;

/**
 * Which column of the Handbook 44 tolerance table a vehicle scale test is judged against.
 *
 * <p>A truck scale is a Class III L device, and its tolerance is stated in scale divisions rather
 * than as a share of the load: one division up to five hundred divisions of test load, two up to a
 * thousand, and one more for every further five hundred or part of five hundred. That is the
 * maintenance tolerance, which a scale in service has to hold. A scale newly installed, or just
 * put back after an adjustment or a rejection, is held to the acceptance tolerance instead, which
 * is half the maintenance figure.
 */
public enum Tolerance implements ValueEnum {
    ACCEPTANCE("acceptance"),
    MAINTENANCE("maintenance");

    /** Test load, in divisions, that the first division of tolerance covers. */
    static final long FIRST_BAND = 500;

    /** Test load, in divisions, that the second division of tolerance covers. */
    static final long SECOND_BAND = 1000;

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final String value;

    Tolerance(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Tolerance of(String value) {
        return ValueEnum.of(Tolerance.class, value);
    }

    /**
     * The maintenance tolerance, in divisions, for a test load of so many divisions.
     *
     * @throws CertificationError for a test load of no divisions
     */
    public static long maintenanceDivisions(long testLoadDivisions) {
        if (testLoadDivisions <= 0) {
            throw new CertificationError("a test load has to be at least one division");
        }
        if (testLoadDivisions <= FIRST_BAND) {
            return 1;
        }
        if (testLoadDivisions <= SECOND_BAND) {
            return 2;
        }
        long beyond = testLoadDivisions - SECOND_BAND;
        return 2 + (beyond + FIRST_BAND - 1) / FIRST_BAND;
    }

    /** The tolerance in pounds for a test load of so many divisions of {@code divisionPounds} each. */
    public BigDecimal pounds(long testLoadDivisions, BigDecimal divisionPounds) {
        BigDecimal maintenance = divisionPounds.multiply(BigDecimal.valueOf(maintenanceDivisions(testLoadDivisions)));
        return this == ACCEPTANCE ? maintenance.multiply(HALF) : maintenance;
    }
}
