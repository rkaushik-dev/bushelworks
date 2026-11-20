package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One test of one scale with known weights.
 *
 * @param scaleId the scale
 * @param locationCode where it stands
 * @param testedOn the day of the test
 * @param testLoadPounds the known load put on it
 * @param indicatedPounds what the indicator said
 * @param testedBy who ran the test
 * @param result how it came out
 * @param certificateNumber the state's certificate, for an official test
 */
public record ScaleTest(
        String scaleId,
        String locationCode,
        LocalDate testedOn,
        BigDecimal testLoadPounds,
        BigDecimal indicatedPounds,
        String testedBy,
        Result result,
        Optional<String> certificateNumber) {

    /** What the state allows on a vehicle scale, as a fraction of the test load. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.001");

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
                .certificateNumber(certificateNumber.orElse(null));
    }

    /** Indicated less known. Positive means the scale reads heavy. */
    public BigDecimal errorPounds() {
        return Units.quantize(indicatedPounds.subtract(testLoadPounds), BigDecimal.ONE);
    }

    public BigDecimal errorFraction() {
        return Units.quantize(Decimals.divide(errorPounds(), testLoadPounds), FRACTION_PLACES);
    }

    public boolean isWithinTolerance() {
        return errorFraction().abs().compareTo(TOLERANCE) <= 0;
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

    /** A scale test assembled a field at a time; a pass with no certificate unless told. */
    public static final class Builder {

        private String scaleId;
        private String locationCode;
        private LocalDate testedOn;
        private BigDecimal testLoadPounds;
        private BigDecimal indicatedPounds;
        private String testedBy;
        private Result result = Result.PASS;
        private String certificateNumber;

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

        public Builder result(Result value) {
            this.result = value;
            return this;
        }

        /** The certificate, or null for a house test. */
        public Builder certificateNumber(String value) {
            this.certificateNumber = value;
            return this;
        }

        public ScaleTest build() {
            return new ScaleTest(
                    scaleId,
                    locationCode,
                    testedOn,
                    testLoadPounds,
                    indicatedPounds,
                    testedBy,
                    result,
                    Optional.ofNullable(certificateNumber));
        }
    }
}
