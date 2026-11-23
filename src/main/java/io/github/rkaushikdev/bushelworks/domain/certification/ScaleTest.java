package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One test of one scale with known weights.
 *
 * <p>Whether the scale passed is a question about divisions, not percentages: Handbook 44 states a
 * vehicle scale's tolerance as so many of its divisions for so large a test load, and see {@link
 * Tolerance} for the table. A scale reading in twenty pound divisions with thirty tons on the deck
 * may be out by six divisions in service and three on acceptance.
 *
 * @param scaleId the scale
 * @param locationCode where it stands
 * @param testedOn the day of the test
 * @param testLoadPounds the known load put on it
 * @param indicatedPounds what the indicator said
 * @param testedBy who ran the test
 * @param result how it came out
 * @param certificateNumber the state's certificate, for an official test
 * @param divisionPounds the scale's division, the smallest step its indicator shows
 * @param tolerance which column of the tolerance table the test is held to
 */
public record ScaleTest(
        String scaleId,
        String locationCode,
        LocalDate testedOn,
        BigDecimal testLoadPounds,
        BigDecimal indicatedPounds,
        String testedBy,
        Result result,
        Optional<String> certificateNumber,
        BigDecimal divisionPounds,
        Tolerance tolerance) {

    /** The division almost every truck scale in the country reads in. */
    public static final BigDecimal STANDARD_DIVISION = new BigDecimal("20");

    /** How long a certification stands, in days. */
    public static final int CERTIFICATION_LIFE_DAYS = 365;

    private static final BigDecimal FRACTION_PLACES = new BigDecimal("0.000001");

    public ScaleTest {
        Objects.requireNonNull(scaleId, "scaleId");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(testedOn, "testedOn");
        Objects.requireNonNull(testedBy, "testedBy");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(certificateNumber, "certificateNumber");
        Objects.requireNonNull(divisionPounds, "divisionPounds");
        Objects.requireNonNull(tolerance, "tolerance");
        if (divisionPounds.signum() <= 0) {
            throw new CertificationError(scaleId + ": a scale division has to be more than nothing");
        }
        if (testLoadPounds.signum() <= 0) {
            throw new CertificationError(scaleId + ": a test needs a known load");
        }
        if (indicatedPounds.signum() < 0) {
            throw new CertificationError(scaleId + ": an indicated weight cannot be negative");
        }
        if (testedBy.isEmpty()) {
            throw new CertificationError(scaleId + ": a test needs a tester");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .scaleId(scaleId)
                .locationCode(locationCode)
                .testedOn(testedOn)
                .testLoadPounds(testLoadPounds)
                .indicatedPounds(indicatedPounds)
                .testedBy(testedBy)
                .result(result)
                .certificateNumber(certificateNumber.orElse(null))
                .divisionPounds(divisionPounds)
                .tolerance(tolerance);
    }

    /** Indicated less known. Positive means the scale reads heavy. */
    public BigDecimal errorPounds() {
        return Units.quantize(indicatedPounds.subtract(testLoadPounds), BigDecimal.ONE);
    }

    public BigDecimal errorFraction() {
        return Units.quantize(Decimals.divide(errorPounds(), testLoadPounds), FRACTION_PLACES);
    }

    /**
     * The test load in the scale's own divisions.
     *
     * <p>A test load that is not a whole number of divisions is counted up to the next division, so
     * the part division falls in the band above rather than being let off.
     */
    public long testLoadDivisions() {
        return testLoadPounds.divide(divisionPounds, 0, RoundingMode.CEILING).longValueExact();
    }

    /** The most the indicator may be out by on this test load, either way, in pounds. */
    public BigDecimal tolerancePounds() {
        return tolerance.pounds(testLoadDivisions(), divisionPounds);
    }

    /** Whether the error is inside the tolerance; an error exactly at the limit passes. */
    public boolean isWithinTolerance() {
        return errorPounds().abs().compareTo(tolerancePounds()) <= 0;
    }

    public boolean isOfficial() {
        return certificateNumber.isPresent();
    }

    /** The day an official test's certificate runs out; a house test has none. */
    public Optional<LocalDate> expiresOn() {
        return isOfficial() ? Optional.of(testedOn.plusDays(CERTIFICATION_LIFE_DAYS)) : Optional.empty();
    }

    public boolean isCurrentOn(LocalDate day) {
        return expiresOn()
                .filter(expiry -> !day.isBefore(testedOn) && !day.isAfter(expiry))
                .isPresent();
    }

    /**
     * A scale test assembled a field at a time: a house test on twenty pound divisions, held to the
     * maintenance tolerance, unless told. Left unset, the result is a pass when the error is inside
     * the tolerance and a failure when it is not.
     */
    public static final class Builder {

        private String scaleId;
        private String locationCode;
        private LocalDate testedOn;
        private BigDecimal testLoadPounds;
        private BigDecimal indicatedPounds;
        private String testedBy;
        private Result result;
        private String certificateNumber;
        private BigDecimal divisionPounds = STANDARD_DIVISION;
        private Tolerance tolerance = Tolerance.MAINTENANCE;

        private Builder() {}

        public Builder scaleId(String value) {
            this.scaleId = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder testedOn(LocalDate value) {
            this.testedOn = value;
            return this;
        }

        public Builder testLoadPounds(BigDecimal value) {
            this.testLoadPounds = value;
            return this;
        }

        public Builder indicatedPounds(BigDecimal value) {
            this.indicatedPounds = value;
            return this;
        }

        public Builder testedBy(String value) {
            this.testedBy = value;
            return this;
        }

        /** What the tester wrote down, which overrides the tolerance: an adjustment is not a pass. */
        public Builder result(Result value) {
            this.result = value;
            return this;
        }

        /** The certificate, or null for a house test. */
        public Builder certificateNumber(String value) {
            this.certificateNumber = value;
            return this;
        }

        public Builder divisionPounds(BigDecimal value) {
            this.divisionPounds = value;
            return this;
        }

        /** Hold the test to acceptance after an installation or an adjustment, maintenance otherwise. */
        public Builder tolerance(Tolerance value) {
            this.tolerance = value;
            return this;
        }

        public ScaleTest build() {
            ScaleTest test = assemble(result != null ? result : Result.PASS);
            if (result == null && !test.isWithinTolerance()) {
                return assemble(Result.FAIL);
            }
            return test;
        }

        private ScaleTest assemble(Result outcome) {
            return new ScaleTest(
                    scaleId,
                    locationCode,
                    testedOn,
                    testLoadPounds,
                    indicatedPounds,
                    testedBy,
                    outcome,
                    Optional.ofNullable(certificateNumber),
                    divisionPounds,
                    tolerance);
        }
    }
}
